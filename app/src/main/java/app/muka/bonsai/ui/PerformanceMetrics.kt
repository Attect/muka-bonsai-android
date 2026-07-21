package app.muka.bonsai.ui

/**
 * Real-time performance metrics displayed during inference.
 */
data class PerformanceMetrics(
    val tokensGenerated: Int = 0,
    val promptTokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val totalDurationMs: Long = 0,
    val nativeHeapMb: Long = 0,
    val totalPssMb: Long = 0,
)
