package app.muka.bonsai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.muka.bonsai.ui.MainScreen
import app.muka.bonsai.ui.markdown.JsRenderEngine
import app.muka.bonsai.ui.theme.BonsaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        JsRenderEngine.init(this)
        setContent {
            BonsaiTheme {
                MainScreen()
            }
        }
    }
}
