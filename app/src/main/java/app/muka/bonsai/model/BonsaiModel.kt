package app.muka.bonsai.model

/**
 * Download mirror used for model GGUF files.
 *
 * huggingface.co is unreachable from some networks (e.g. mainland China), so
 * hf-mirror.com (a Hugging Face mirror) is the default; the same repos are also
 * available on ModelScope.
 */
enum class DownloadSource(val label: String) {
    HfMirror("HF 镜像（hf-mirror.com）"),
    HuggingFace("HuggingFace 官方"),
    ModelScope("ModelScope");

    fun urlFor(repo: String, filename: String): String = when (this) {
        HfMirror -> "https://hf-mirror.com/$repo/resolve/main/$filename"
        HuggingFace -> "https://huggingface.co/$repo/resolve/main/$filename"
        ModelScope -> "https://modelscope.cn/models/$repo/resolve/master/$filename"
    }

    companion object {
        val Default = HfMirror
    }
}

/**
 * Configuration for a downloadable Bonsai GGUF model.
 *
 * @property family Display family name, e.g. "Bonsai 1-bit"
 * @property sizeParam Parameter size suffix, e.g. "27B"
 * @property repo Hugging Face repo, e.g. "prism-ml/Bonsai-27B-gguf"
 * @property filename GGUF filename inside the repo
 * @property footprint Approximate on-disk size in GiB
 * @property mmprojFilename Optional vision projector (mmproj) inside the same
 *                           repo. When set, [ModelManager] downloads it together
 *                           with the model so image input works out of the box.
 * @property mmprojFootprintGiB Approximate on-disk size of the mmproj in GiB
 */
data class BonsaiModel(
    val family: String,
    val sizeParam: String,
    val repo: String,
    val filename: String,
    val footprintGiB: Double,
    val mmprojFilename: String? = null,
    val mmprojFootprintGiB: Double? = null,
) {
    val id: String = "$family-$sizeParam"

    /** Direct download URL for the single GGUF file via [source]. */
    fun downloadUrl(source: DownloadSource): String = source.urlFor(repo, filename)

    /** Direct download URL for the mmproj file via [source], if configured. */
    fun mmprojDownloadUrl(source: DownloadSource): String? =
        mmprojFilename?.let { source.urlFor(repo, it) }
}

val AVAILABLE_MODELS = listOf(
    BonsaiModel(
        family = "Bonsai 1-bit",
        sizeParam = "27B",
        repo = "prism-ml/Bonsai-27B-gguf",
        filename = "Bonsai-27B-Q1_0.gguf",
        footprintGiB = 3.9,
        mmprojFilename = "Bonsai-27B-mmproj-Q8_0.gguf",
        mmprojFootprintGiB = 0.59,
    ),
    BonsaiModel(
        family = "Ternary 1.58-bit",
        sizeParam = "27B",
        repo = "prism-ml/Ternary-Bonsai-27B-gguf",
        filename = "Ternary-Bonsai-27B-Q2_0.gguf",
        footprintGiB = 5.9,
        mmprojFilename = "Ternary-Bonsai-27B-mmproj-Q8_0.gguf",
        mmprojFootprintGiB = 0.59,
    ),
    BonsaiModel(
        family = "Bonsai 1-bit",
        sizeParam = "8B",
        repo = "prism-ml/Bonsai-8B-gguf",
        filename = "Bonsai-8B-Q1_0.gguf",
        footprintGiB = 1.15,
    ),
    BonsaiModel(
        family = "Ternary 1.58-bit",
        sizeParam = "8B",
        repo = "prism-ml/Ternary-Bonsai-8B-gguf",
        filename = "Ternary-Bonsai-8B-Q2_0.gguf",
        footprintGiB = 1.75,
    ),
    BonsaiModel(
        family = "Bonsai 1-bit",
        sizeParam = "4B",
        repo = "prism-ml/Bonsai-4B-gguf",
        filename = "Bonsai-4B-Q1_0.gguf",
        footprintGiB = 0.6,
    ),
    BonsaiModel(
        family = "Bonsai 1-bit",
        sizeParam = "1.7B",
        repo = "prism-ml/Bonsai-1.7B-gguf",
        filename = "Bonsai-1.7B-Q1_0.gguf",
        footprintGiB = 0.3,
    ),
)
