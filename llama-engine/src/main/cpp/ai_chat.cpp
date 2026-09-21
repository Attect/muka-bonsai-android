#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iterator>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
// Physical (micro) batch size. 128 is the sweet spot for the Adreno OpenCL
// backend: smaller compute buffer and better kernel utilization than 512.
constexpr int   UBATCH_SIZE             = 128;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
// Multimodal projector context (mmproj). Null when no mmproj was loaded.
static mtmd_context                     * g_mtmd;
// Guard for llama_batch_free: the batch is only initialized in prepare().
static bool                              g_batch_initialized = false;

extern "C"
JNIEXPORT void JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Route mtmd (multimodal projector) logging to Logcat as well.
    mtmd_helper_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_load(
        JNIEnv *env,
        jobject,
        jstring jmodel_path,
        jstring jmmproj_path,
        jint jimage_max_tokens) {
    llama_model_params model_params = llama_model_default_params();
    // Offload as many layers as possible to the GPU (OpenCL/Adreno). Ops that
    // the GPU backend cannot run automatically fall back to the CPU.
    model_params.n_gpu_layers = 99;

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    // Research hook: an optional fa_tune.txt next to the model overrides the
    // Adreno flash-attention tile table without a rebuild. Contents are a
    // GGML_OPENCL_FA_TUNE string, e.g. "256:256:64:32:2:64". Worth sweeping
    // because prefill measures as 15.2 ms/token plus 3.5e-6*P^2, and that
    // quadratic term implies an attention throughput the hardware cannot
    // reach - the shipped 256x256 entry is 16x16 tiles with n_split 16, which
    // is a decode-shaped config for what is really a prefill GEMM. The backend
    // reads the variable lazily on the first FA lookup, so this is early enough.
    {
        const std::string p(model_path);
        const std::string tune_file = p.substr(0, p.find_last_of('/') + 1) + "fa_tune.txt";
        std::ifstream in(tune_file);
        std::string   tune((std::istreambuf_iterator<char>(in)),
                            std::istreambuf_iterator<char>());
        while (!tune.empty() && (tune.back() == '\n' || tune.back() == '\r' || tune.back() == ' ')) {
            tune.pop_back();
        }
        if (!tune.empty()) {
            setenv("GGML_OPENCL_FA_TUNE", tune.c_str(), 1);
            LOGi("%s: FA tile override from %s: %s", __func__, tune_file.c_str(), tune.c_str());
        }
    }

    // Research hook: an optional gpu_layers.txt next to the model overrides how
    // many layers are offloaded. Offload is decided once, at model load, so this
    // is the only way to sweep it on device - needed to attribute decode
    // throughput between the CPU and the Adreno path. A negative value means
    // "as many as possible" (the shipped default).
    {
        const std::string p(model_path);
        const std::string layers_file = p.substr(0, p.find_last_of('/') + 1) + "gpu_layers.txt";
        std::ifstream in(layers_file);
        int layers = 0;
        if (in >> layers) {
            model_params.n_gpu_layers = layers < 0 ? 99 : layers;
            LOGi("%s: n_gpu_layers override from %s: %d", __func__, layers_file.c_str(), layers);
        }
    }

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;

    // Optional multimodal projector (mmproj, e.g. Bonsai-27B-mmproj-Q8_0.gguf).
    // The projector runs on the GPU like the text model; unsupported ops fall
    // back to the CPU inside clip's scheduler.
    const auto *mmproj_path = env->GetStringUTFChars(jmmproj_path, nullptr);
    if (mmproj_path != nullptr && mmproj_path[0] != '\0') {
        LOGi("%s: Loading mmproj from: \n%s\n", __func__, mmproj_path);
        mtmd_context_params mtmd_params = mtmd_context_params_default();
        // Per-op breakdown of one 1920x1080 screenshot on the CPU tower: 108.1 s
        // total, 60.7 s FLASH_ATTN_EXT (27 calls) and 45.0 s MUL_MAT (112 calls).
        // The tower belongs on the GPU, and what kept it off was flash attention:
        // this projector's head dim is 72, which the OpenCL backend did not list
        // as an FA dimension, so clip materialized a [8464, 8464, 16, 1] F32
        // softmax instead and the graph died in ggml_gallocr_alloc_graph. With
        // 72x72 added to the FA tables the fused path fits, so ask for it
        // explicitly - if FA ever becomes unavailable again this fails loudly at
        // the first encode rather than falling back to the giant softmax.
        mtmd_params.use_gpu = true;
        mtmd_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        // Default is 4 threads; the vision tower is a full ViT forward, so it
        // wants the same core count the text model gets.
        const long ncpu = sysconf(_SC_NPROCESSORS_ONLN);
        if (ncpu > 0) {
            mtmd_params.n_threads = (int) ncpu;
        }
        // Prints "image slice encoded in N ms", the only view of this cost.
        mtmd_params.print_timings = true;
        // Caps how many vision tiles one image may produce: clip converts it to a
        // pixel budget and resizes before the tower runs, so encode time and the
        // context the image takes both shrink with it. Non-positive keeps the
        // projector's own ceiling.
        if (jimage_max_tokens > 0) {
            mtmd_params.image_max_tokens = (int) jimage_max_tokens;
            LOGi("%s: capping one image at %d vision tokens", __func__, (int) jimage_max_tokens);
        }
        g_mtmd = mtmd_init_from_file(mmproj_path, model, mtmd_params);
        env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);
        if (!g_mtmd) {
            LOGe("%s: Failed to initialize mtmd context from mmproj", __func__);
            return 2;
        }
        if (!mtmd_support_vision(g_mtmd)) {
            LOGe("%s: mmproj does not support vision input", __func__);
            mtmd_free(g_mtmd);
            g_mtmd = nullptr;
            return 2;
        }
        LOGi("%s: mmproj loaded, marker = %s", __func__, mtmd_get_marker(g_mtmd));
    } else {
        if (mmproj_path != nullptr) {
            env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);
        }
        g_mtmd = nullptr;
    }

    return 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_isVisionEnabled(JNIEnv * /*env*/, jobject /*unused*/) {
    return g_mtmd != nullptr && mtmd_support_vision(g_mtmd);
}

static ggml_type kv_ggml_type(const int kv_type) {
    switch (kv_type) {
        case 1:  return GGML_TYPE_Q8_0;
        case 2:  return GGML_TYPE_Q4_0;
        default: return GGML_TYPE_F16;
    }
}

static llama_context *init_context(llama_model *model, const int n_ctx, const int n_threads, const int kv_type = 0) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    LOGi("%s: Using %d threads, n_ctx=%d, kv_type=%d", __func__, n_threads, n_ctx, kv_type);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = UBATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.type_k = kv_ggml_type(kv_type);
    ctx_params.type_v = kv_ggml_type(kv_type);
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT void JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_setSamplingParams(
        JNIEnv * /*env*/,
        jobject /*unused*/,
        jfloat temp,
        jint top_k,
        jfloat top_p,
        jfloat penalty_repeat,
        jfloat penalty_freq,
        jfloat penalty_present,
        jint seed) {
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_k = top_k;
    sparams.top_p = top_p;
    sparams.penalty_repeat = penalty_repeat;
    sparams.penalty_freq = penalty_freq;
    sparams.penalty_present = penalty_present;
    sparams.seed = seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed;

    // Cheap to rebuild; lets sampling changes apply per-generation without
    // reloading the model.
    common_sampler_free(g_sampler);
    g_sampler = common_sampler_init(g_model, sparams);
    LOGi("%s: temp=%.2f top_k=%d top_p=%.2f rep=%.2f freq=%.2f pres=%.2f seed=%d",
         __func__, temp, top_k, top_p, penalty_repeat, penalty_freq, penalty_present, seed);
}

extern "C"
JNIEXPORT jint JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_prepare(
        JNIEnv * /*env*/,
        jobject /*unused*/,
        jint n_ctx,
        jint n_threads,
        jfloat temperature,
        jint kv_type) {
    auto *context = init_context(g_model, n_ctx, n_threads, kv_type);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_batch_initialized = true;
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(temperature);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp, N_THREADS_MAX);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;
// Kept so a context overflow can rebuild the cache without another round trip.
static llama_tokens g_system_tokens;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache && g_context)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static bool shift_context() {
    const int n_pos_per_embd = llama_model_n_pos_per_embd(g_model);
    if (n_pos_per_embd > 1) {
        // llama_kv_cache::seq_add asserts unless there is one position per
        // embedding, so for mrope models - every multimodal Bonsai among them -
        // moving cached positions here aborts the process. Refuse and let the
        // caller surface a failed request instead.
        LOGe("%s: model has %d positions per embedding, cached positions cannot be"
             " shifted - refusing to touch the KV cache", __func__, n_pos_per_embd);
        return false;
    }
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
    return true;
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - generation length cap (count-based, robust against context shifting:
 *   positions change on shift, generated token count does not)
 * - token chars caching
 * - current assistant message being generated
 */
static int max_new_tokens;
static int generated_token_count;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

// Why the last generation stopped: 0 = still running / not started,
// 1 = max_new_tokens limit, 2 = llama_decode failure, 3 = EOG sampled.
static int last_stop_reason;

static void reset_short_term_states() {
    max_new_tokens = 0;
    generated_token_count = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    last_stop_reason = 0;
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Callers pre-check the window: shifting here would rewind the global
        // current_position while the positions below stay absolute, so every later
        // batch would land past n_ctx and abort the next llama_decode.
        if (start_pos + i + cur_batch_size >= (int) llama_n_ctx(context) - OVERFLOW_HEADROOM) {
            LOGw("%s: batch of %d tokens at position %d does not fit the window",
                 __func__, cur_batch_size, start_pos + i);
            return 3;
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

// Positions cannot be compacted on mrope models (see shift_context), so once the
// window is full the only way to keep the conversation going is to start over:
// clear the cache, replay the system prompt, and let the caller decode whatever
// triggered the overflow into the fresh window. Earlier turns are dropped, and
// the caller is expected to tell the user that happened.
static bool restart_context_and_replay() {
    if (g_system_tokens.empty()) {
        LOGe("%s: no saved system prompt to replay", __func__);
        return false;
    }
    reset_long_term_states();
    reset_short_term_states();
    if (decode_tokens_in_batches(g_context, g_batch, g_system_tokens, 0) != 0) {
        LOGe("%s: replaying the system prompt failed", __func__);
        return false;
    }
    system_prompt_position = current_position = (int) g_system_tokens.size();
    LOGw("%s: context reset, %d system tokens replayed at position 0",
         __func__, (int) g_system_tokens.size());
    return true;
}

extern "C"
JNIEXPORT jint JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    g_system_tokens = system_tokens;
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = (int) llama_n_ctx(g_context) - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // The prompt has to fit in the window that the history leaves free. Feeding
    // a truncated remainder used to leave decode_tokens_in_batches shifting the
    // cache mid-prompt and then writing at stale positions past n_ctx, which
    // aborts inside the backend scheduler.
    const int user_prompt_size = (int) user_tokens.size();
    const size_t usable = (size_t) llama_n_ctx(g_context) - OVERFLOW_HEADROOM;
    bool history_reset = false;
    if ((size_t) user_prompt_size + current_position > usable) {
        if ((size_t) user_prompt_size > usable) {
            LOGe("%s: %d token prompt does not fit an empty %zu position window",
                 __func__, user_prompt_size, usable);
            return 5;
        }
        // Drop the earlier turns and replay the system prompt. Shifting instead
        // (seq_rm/seq_add) is not an option here: on this build the fragmented
        // cache makes the next large decode abort in ggml_backend_sched_split_graph.
        history_reset = restart_context_and_replay();
        if (!history_reset || (size_t) user_prompt_size + current_position > usable) {
            LOGe("%s: %d token prompt still does not fit after reclaiming the window",
                 __func__, user_prompt_size);
            return 5;
        }
        LOGw("%s: window reclaimed, decoding %d tokens at position %d",
             __func__, user_prompt_size, current_position);
    }

    // Decode user tokens in batches
    int rc = decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true);
    if (rc == 3 && restart_context_and_replay()) {
        // mrope models cannot compact cached positions, so the window can only be
        // reclaimed by starting over; retry this message in the fresh window.
        history_reset = true;
        rc = decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true);
    }
    if (rc) {
        LOGe("%s: llama_decode() failed! (%d)", __func__, rc);
        return 2;
    }

    current_position += user_prompt_size;
    max_new_tokens = n_predict;
    generated_token_count = 0;
    return history_reset ? 7 : 0;
}

/**
 * Multimodal user prompt: renders the conversation (which keeps one media
 * marker per attached image in the message content) and sends it through
 * mtmd, which replaces each marker with the corresponding image tile tokens.
 *
 * With a vision model this path is used even for text-only turns: markers from
 * earlier image messages remain in the re-formatted history, so the bitmap
 * count must match the marker count every time.
 *
 * Return codes: 0 = success, 1 = mtmd not loaded, 2 = marker/bitmap mismatch,
 * 3 = image load failure, 4 = tokenization failure, 5 = prompt too long for
 * the context, 6 = decode failure, 7 = succeeded after dropping the earlier
 * turns to reclaim the window.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_processUserPromptMtmd(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jobjectArray jimage_paths,
        jint n_predict) {
    if (!g_mtmd) {
        LOGe("%s: mtmd context is not loaded", __func__);
        return 1;
    }

    reset_short_term_states();

    const auto *user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s\n", __func__, user_prompt);
    std::string content(user_prompt);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    const char *marker = mtmd_get_marker(g_mtmd);
    if (marker == nullptr || marker[0] == '\0') {
        marker = mtmd_default_marker();
    }

    // One marker per image of the current turn, appended after the prompt text.
    // Only the new-message fragment is formatted & decoded each turn (the rest
    // of the conversation stays in the KV cache), so just this turn's images.
    std::vector<std::string> images;
    const jsize n_new_images = env->GetArrayLength(jimage_paths);
    for (jsize i = 0; i < n_new_images; i++) {
        const auto jpath = (jstring) env->GetObjectArrayElement(jimage_paths, i);
        const auto *path = env->GetStringUTFChars(jpath, nullptr);
        images.emplace_back(path);
        env->ReleaseStringUTFChars(jpath, path);
        env->DeleteLocalRef(jpath);
        if (!content.empty() && content.back() != '\n') {
            content += "\n";
        }
        content += marker;
    }

    // Format the user message (with markers) and append it to the history.
    std::string formatted(content);
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted = chat_add_and_format(ROLE_USER, content);
    }

    // Load one bitmap per marker in the current message fragment.
    std::vector<mtmd_bitmap *> bitmaps;
    bitmaps.reserve(images.size());
    for (const auto &path : images) {
        auto img = mtmd_helper_bitmap_init_from_file(g_mtmd, path.c_str(), false);
        if (!img.bitmap) {
            LOGe("%s: Failed to load image file: %s", __func__, path.c_str());
            for (auto *b : bitmaps) {
                mtmd_bitmap_free(b);
            }
            return 3;
        }
        bitmaps.push_back(img.bitmap);
    }

    mtmd_input_text in_text{formatted.data(), formatted.size(), /* add_special */ true, /* parse_special */ true};
    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    const int32_t tokenized = mtmd_tokenize(g_mtmd, chunks.ptr.get(), &in_text,
                                            const_cast<const mtmd_bitmap **>(bitmaps.data()), bitmaps.size());
    for (auto *b : bitmaps) {
        mtmd_bitmap_free(b);
    }
    if (tokenized != 0) {
        LOGe("%s: mtmd_tokenize failed with %d", __func__, tokenized);
        return 4;
    }

    // Image prompts cannot be truncated (marker chunks are tied to bitmaps),
    // so reject an overflow instead of silently dropping content.
    const size_t n_prompt = mtmd_helper_get_n_tokens(chunks.ptr.get());
    const size_t usable = (size_t) llama_n_ctx(g_context) - OVERFLOW_HEADROOM;
    LOGi("%s: mtmd prompt: %zu tokens @ position %d (remaining %llu)",
         __func__, n_prompt, current_position,
         (unsigned long long) (current_position < (llama_pos) usable ? usable - current_position : 0));
    bool history_reset = false;
    if (n_prompt + current_position > usable) {
        if (n_prompt > usable) {
            LOGe("%s: mtmd prompt too long for an empty context! %zu tokens, window: %zu",
                 __func__, n_prompt, usable);
            return 5;
        }
        // The image chunks are fine, only the history is in the way.
        history_reset = restart_context_and_replay();
        if (!history_reset || n_prompt + current_position > usable) {
            LOGe("%s: mtmd prompt too long for context! %zu tokens, remaining: %zu",
                 __func__, n_prompt, usable > (size_t) current_position ? usable - current_position : 0);
            return 5;
        }
        LOGw("%s: window reclaimed, decoding %zu mtmd tokens at position %d",
             __func__, n_prompt, current_position);
    }

    // Decode the chunks: text via llama_decode, image tiles via the projector.
    llama_pos new_past = current_position;
    int32_t res = mtmd_helper_eval_chunks(
            g_mtmd, g_context, chunks.ptr.get(), current_position, /* seq_id */ 0,
            llama_n_batch(g_context), /* logits_last */ true, &new_past);
    if (res != 0 && restart_context_and_replay()) {
        // as in the text path: with mrope the window is only reclaimed by
        // starting over, so this turn's image tiles get encoded a second time
        LOGw("%s: eval failed (%d), retrying on a fresh context", __func__, res);
        history_reset = true;
        new_past = current_position;
        res = mtmd_helper_eval_chunks(
                g_mtmd, g_context, chunks.ptr.get(), current_position, /* seq_id */ 0,
                llama_n_batch(g_context), /* logits_last */ true, &new_past);
    }
    if (res != 0) {
        LOGe("%s: mtmd_helper_eval_chunks failed with %d", __func__, res);
        return 6;
    }
    LOGi("%s: mtmd prompt processed, position %d -> %d", __func__, current_position, new_past);
    current_position = new_past;
    max_new_tokens = n_predict;
    generated_token_count = 0;
    return history_reset ? 7 : 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Infinite text generation via context shifting
    if (current_position >= (int) llama_n_ctx(g_context) - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        if (!shift_context()) {
            // Refused (mrope): decoding at a position past the window aborts the
            // process, and replaying the system prompt would discard the answer
            // that is still being generated. End the turn here instead.
            LOGw("%s: window is full and cannot be compacted, stopping the answer", __func__);
            last_stop_reason = 2;
            return nullptr;
        }
    }

    // Stop if reaching the requested generation length. Count-based, so it
    // stays correct even when context shifting rewinds the position.
    if (generated_token_count >= max_new_tokens) {
        LOGw("%s: STOP: generated %d tokens (limit %d)", __func__, generated_token_count, max_new_tokens);
        last_stop_reason = 1;
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token %d at position %d (after %d tokens)",
             __func__, new_token_id, current_position, generated_token_count);
        last_stop_reason = 2;
        return nullptr;
    }

    // Update position and count
    current_position++;
    generated_token_count++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        // Must stay at WARN: with NDEBUG the debug log is compiled out, making
        // an EOG stop indistinguishable from a silent hang in release builds.
        LOGw("%s: STOP: sampled EOG token %d `%s` after %d tokens",
             __func__, new_token_id,
             common_token_to_piece(g_context, new_token_id).c_str(),
             generated_token_count);
        last_stop_reason = 3;
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT jint JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_getLastStopReasonImpl(JNIEnv * /*unused*/, jobject /*unused*/) {
    return last_stop_reason;
}


// Vocabulary analysis: expose the loaded model's tokenizer so the UI can count how
// its input text fragments, and check whether the same text can be restored.
extern "C"
JNIEXPORT jintArray JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_tokenizeText(JNIEnv * env, jobject /*unused*/, jstring jtext) {
    if (!g_model || !g_context || !jtext) {
        return nullptr;
    }
    const char * utf = env->GetStringUTFChars(jtext, nullptr);
    if (!utf) {
        return nullptr;
    }
    const std::vector<llama_token> tokens =
        common_tokenize(g_context, std::string(utf), /*add_special*/ false, /*parse_special*/ true);
    env->ReleaseStringUTFChars(jtext, utf);

    jintArray out = env->NewIntArray((jsize) tokens.size());
    if (out) {
        env->SetIntArrayRegion(out, 0, (jsize) tokens.size(), (const jint *) tokens.data());
    }
    return out;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_detokenizeText(JNIEnv * env, jobject /*unused*/, jintArray jids) {
    if (!g_model || !jids) {
        return nullptr;
    }
    const jsize n = env->GetArrayLength(jids);
    jint * ids = env->GetIntArrayElements(jids, nullptr);
    if (!ids) {
        return nullptr;
    }
    std::vector<llama_token> tokens((size_t) n);
    for (jsize i = 0; i < n; i++) {
        tokens[i] = (llama_token) ids[i];
    }
    env->ReleaseIntArrayElements(jids, ids, JNI_ABORT);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    // Render specials as their markup: the round trip must reproduce the input verbatim.
    std::vector<char> buf((size_t) n * 8 + 16);
    int32_t wrote = llama_detokenize(
        vocab, tokens.data(), (int32_t) n, buf.data(), (int32_t) buf.size(),
        /*remove_special*/ false, /*special*/ true);
    if (wrote < 0) {
        buf.resize((size_t) (-wrote) + 1);
        wrote = llama_detokenize(
            vocab, tokens.data(), (int32_t) n, buf.data(), (int32_t) buf.size(),
            /*remove_special*/ false, /*special*/ true);
    }
    if (wrote < 0) {
        LOGe("%s: llama_detokenize failed with %d", __func__, wrote);
        return nullptr;
    }
    return env->NewStringUTF(buf.data());
}

// Debug-only numeric parity check: run one prompt with the model entirely on the CPU and
// then entirely on the GPU, and compare the last-position logits. Loading the two variants
// one after the other keeps peak memory at a single copy of the model.
static bool run_logits_with_offload(
        const char * path, int n_gpu_layers, const std::string & prompt,
        std::vector<float> & logits, int32_t & top1, std::string & err) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;

    llama_model * model = llama_model_load_from_file(path, mp);
    if (!model) {
        err = "load_failed";
        return false;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    const int32_t n_vocab = llama_vocab_n_tokens(vocab);

    std::vector<llama_token> tokens(prompt.size() + 16);
    int32_t n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                               tokens.data(), (int32_t) tokens.size(), /*add_special*/ true,
                               /*parse_special*/ true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                           tokens.data(), (int32_t) tokens.size(), true, true);
    }
    if (n <= 0) {
        llama_model_free(model);
        err = "tokenize_failed";
        return false;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx     = (uint32_t) n + 64;
    cp.n_batch   = 256;
    cp.n_threads = 4;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        err = "ctx_failed";
        return false;
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), n);
    const bool ok = llama_decode(ctx, batch) == 0;
    const float * lg = ok ? llama_get_logits(ctx) : nullptr;
    if (lg) {
        logits.assign(lg, lg + n_vocab);
        top1 = (int32_t) std::distance(logits.begin(), std::max_element(logits.begin(), logits.end()));
    }

    llama_free(ctx);
    llama_model_free(model);

    if (!lg) {
        err = "decode_failed";
        return false;
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_debugGpuCpuParityNative(
        JNIEnv * env, jobject /*unused*/, jstring jmodel_path, jstring jprompt) {
    const char * path = env->GetStringUTFChars(jmodel_path, nullptr);
    const char * prompt = env->GetStringUTFChars(jprompt, nullptr);
    const std::string model_path(path), text(prompt);
    env->ReleaseStringUTFChars(jmodel_path, path);
    env->ReleaseStringUTFChars(jprompt, prompt);

    std::vector<float> cpu, gpu;
    int32_t top_cpu = -1, top_gpu = -1;
    std::string err;

    if (!run_logits_with_offload(model_path.c_str(), 0, text, cpu, top_cpu, err) ||
        !run_logits_with_offload(model_path.c_str(), 99, text, gpu, top_gpu, err)) {
        std::string msg = "parity_failed:" + err;
        return env->NewStringUTF(msg.c_str());
    }

    double max_abs = 0.0, sum_abs = 0.0;
    for (size_t i = 0; i < cpu.size(); i++) {
        const double d = std::fabs((double) cpu[i] - (double) gpu[i]);
        max_abs = std::max(max_abs, d);
        sum_abs += d;
    }

    char buf[256];
    snprintf(buf, sizeof(buf),
        "n_vocab=%zu max_abs=%.6f mean_abs=%.6f top1_cpu=%d top1_gpu=%d argmax_match=%d",
        cpu.size(), max_abs, cpu.empty() ? 0.0 : sum_abs / cpu.size(),
        top_cpu, top_gpu, top_cpu == top_gpu ? 1 : 0);
    LOGi("%s: %s", __func__, buf);
    return env->NewStringUTF(buf);
}

extern "C"
JNIEXPORT void JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources. Every pointer is nulled so a second unload (e.g. an
    // activity being destroyed right after another one loaded) cannot touch
    // freed state. Idempotent by construction.
    common_sampler_free(g_sampler);
    g_sampler = nullptr;
    g_chat_templates.reset();
    if (g_batch_initialized) {
        llama_batch_free(g_batch);
        g_batch_initialized = false;
    }
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_app_muka_bonsai_llama_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
