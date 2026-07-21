package app.muka.bonsai.llama

import android.content.Context
import app.muka.bonsai.llama.internal.InferenceEngineImpl

/**
 * Main entry point for Arm's AI Chat library.
 */
object AiChat {
    /**
     * Get the inference engine single instance.
     */
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)
}
