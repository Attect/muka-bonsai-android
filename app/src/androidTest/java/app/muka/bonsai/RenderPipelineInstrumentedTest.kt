package app.muka.bonsai

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muka.bonsai.ui.markdown.JsRenderEngine
import app.muka.bonsai.ui.markdown.rasterizeSvg
import app.muka.bonsai.ui.markdown.svgNaturalSize
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * On-device verification of the render pipeline: MathJax/mermaid -> SVG ->
 * AndroidSVG rasterization. Dumps SVG and PNG artifacts to external files for
 * inspection via adb pull.
 */
@RunWith(AndroidJUnit4::class)
class RenderPipelineInstrumentedTest {

    @Test
    fun renderMathAndMermaid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.Main) { JsRenderEngine.init(context) }

        val mathSvg = JsRenderEngine.renderMath("\\frac{a}{b}+\\sqrt{x^2+y^2}", display = true)
        Log.i(TAG, "math svg: ${mathSvg?.take(400)}")
        assertNotNull("math svg must render", mathSvg)

        val mermaidCode = "flowchart TB\n  A[Start] --> B{Big?}\n  B -->|Yes| C[Go]\n  B -->|No| D[Stop]"
        val mermaidSvg = JsRenderEngine.renderMermaid(mermaidCode, dark = true)
        Log.i(TAG, "mermaid svg len=${mermaidSvg?.length}: ${mermaidSvg?.take(800)}")
        assertNotNull("mermaid svg must render", mermaidSvg)

        val outDir = context.getExternalFilesDir(null)!!
        File(outDir, "test_math.svg").writeText(mathSvg!!)
        File(outDir, "test_mermaid.svg").writeText(mermaidSvg!!)

        // Rasterize both and save PNGs to verify AndroidSVG output.
        val mathSize = svgNaturalSize(mathSvg)
        assertNotNull(mathSize)
        val mathBitmap = rasterizeSvg(mathSvg, androidx.compose.ui.graphics.Color.White, 800, 400)
        assertNotNull("math rasterize", mathBitmap)

        val mmdSize = svgNaturalSize(mermaidSvg)
        assertNotNull(mmdSize)
        val scale = 1200f / mmdSize!!.width
        val mmdBitmap = rasterizeSvg(
            mermaidSvg, null,
            (mmdSize.width * scale).toInt().coerceAtLeast(1),
            (mmdSize.height * scale).toInt().coerceAtLeast(1),
        )
        assertNotNull("mermaid rasterize", mmdBitmap)

        FileOutputStream(File(outDir, "test_math.png")).use {
            android.graphics.Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).also { bg ->
                val canvas = android.graphics.Canvas(bg)
                canvas.drawColor(android.graphics.Color.DKGRAY)
                canvas.drawBitmap(mathBitmap!!, 0f, 0f, null)
                bg.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        FileOutputStream(File(outDir, "test_mermaid.png")).use {
            android.graphics.Bitmap.createBitmap(mmdBitmap!!.width, mmdBitmap.height, Bitmap.Config.ARGB_8888)
                .also { bg ->
                    val canvas = android.graphics.Canvas(bg)
                    canvas.drawColor(android.graphics.Color.DKGRAY)
                    canvas.drawBitmap(mmdBitmap, 0f, 0f, null)
                    bg.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
        }
        assertTrue(mathSvg.contains("<svg"))
        assertTrue(mermaidSvg.contains("<svg"))
    }

    private companion object {
        const val TAG = "RenderPipelineTest"
    }
}
