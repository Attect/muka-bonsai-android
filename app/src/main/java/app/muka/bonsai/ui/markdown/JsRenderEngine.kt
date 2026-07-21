package app.muka.bonsai.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared headless WebView that converts LaTeX (MathJax) and Mermaid diagrams
 * into SVG strings. Rendering is serialized because MathJax is not reentrant.
 * Must be [init]ed on the main thread before use; when unavailable every
 * render call returns null so callers fall back to showing the source text.
 */
object JsRenderEngine {

    private const val TIMEOUT_MS = 15_000L

    private var webView: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private var nextJobId = 0
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    private val mathCache = LruCache<String, String>(256)
    private val mermaidCache = LruCache<String, String>(64)

    private val bridge = object {
        @JavascriptInterface
        fun onReady() {
            if (!ready.isCompleted) ready.complete(Unit)
        }

        @JavascriptInterface
        fun onResult(id: Int, svg: String) {
            pending.remove(id)?.complete(svg)
        }
    }

    private const val PAGE_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<script src="vendor/tex-svg.js"></script>
<script src="vendor/mermaid.min.js"></script>
<script>
var mermaidSeq = 0;
function renderMathJob(id, tex, display) {
  try {
    var node = MathJax.tex2svg(tex, { display: display });
    var svg = node.querySelector('svg');
    if (!svg) { AndroidBridge.onResult(id, ''); return; }
    // MathJax sizes in ex (1ex = 8px at the default 16px em); normalize to px
    // so the Android side can scale by (fontSize / 16).
    svg.setAttribute('width', (parseFloat(svg.getAttribute('width')) * 8) + 'px');
    svg.setAttribute('height', (parseFloat(svg.getAttribute('height')) * 8) + 'px');
    AndroidBridge.onResult(id, svg.outerHTML);
  } catch (e) {
    AndroidBridge.onResult(id, '');
  }
}
function renderMermaidJob(id, code, theme) {
  try {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'loose',
      theme: theme,
      flowchart: { htmlLabels: false }
    });
    // Parse first so incomplete diagrams (mid-stream) fall back to source view.
    mermaid.parse(code);
    mermaid.render('mmd-' + (++mermaidSeq), code, function (svg) {
      AndroidBridge.onResult(id, svg);
    });
  } catch (e) {
    AndroidBridge.onResult(id, '');
  }
}
</script>
</head>
<body></body>
</html>
"""

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
        if (webView != null) return
        runCatching {
            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = true
            wv.addJavascriptInterface(bridge, "AndroidBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(
                        "MathJax.startup.promise.then(function(){ AndroidBridge.onReady(); });",
                        null
                    )
                }
            }
            wv.loadDataWithBaseURL(
                "file:///android_asset/",
                PAGE_HTML,
                "text/html",
                "utf-8",
                null
            )
            webView = wv
        }
    }

    /** Returns the SVG for [tex], or null when rendering fails or times out. */
    suspend fun renderMath(tex: String, display: Boolean): String? {
        val key = "math|$display|$tex"
        mathCache.get(key)?.let { return it }
        val svg = runJob { id ->
            "renderMathJob($id, ${JSONObject.quote(tex)}, $display);"
        } ?: return null
        mathCache.put(key, svg)
        return svg
    }

    /** Returns the SVG for a mermaid diagram, or null when rendering fails. */
    suspend fun renderMermaid(code: String, dark: Boolean): String? {
        val theme = if (dark) "dark" else "default"
        val key = "mermaid|$theme|$code"
        mermaidCache.get(key)?.let { return it }
        val svg = runJob { id ->
            "renderMermaidJob($id, ${JSONObject.quote(code)}, '$theme');"
        } ?: return null
        mermaidCache.put(key, svg)
        return svg
    }

    private suspend fun runJob(jsForId: (Int) -> String): String? {
        val engine = webView ?: return null
        return mutex.withLock {
            val engineReady = withTimeoutOrNull(TIMEOUT_MS) { ready.await() } != null
            if (!engineReady) return@withLock null
            val id = nextJobId++
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            withContext(Dispatchers.Main) {
                engine.evaluateJavascript(jsForId(id), null)
            }
            val result = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
            if (result == null) pending.remove(id)
            result?.takeIf { it.isNotEmpty() }
        }
    }
}
