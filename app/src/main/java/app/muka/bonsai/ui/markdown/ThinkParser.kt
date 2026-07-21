package app.muka.bonsai.ui.markdown

/**
 * Splits raw assistant output into its `<think>` part and the visible answer.
 * Tolerates an unclosed `<think>` tag while tokens are still streaming in.
 */
object ThinkParser {

    data class Result(
        /** Concatenated think content, or null when there is nothing to show. */
        val think: String?,
        /** True while a `<think>` tag is still open (streaming in progress). */
        val isThinking: Boolean,
        /** The answer with all think blocks removed. */
        val answer: String,
    ) {
        /** Whether the think section should be shown at all. */
        val hasThink: Boolean get() = think != null || isThinking
    }

    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    fun split(text: String): Result {
        if (!text.contains(OPEN)) return Result(think = null, isThinking = false, answer = text)

        val think = StringBuilder()
        val answer = StringBuilder()
        var isThinking = false
        var i = 0
        while (i < text.length) {
            val open = text.indexOf(OPEN, i)
            if (open < 0) {
                answer.append(text, i, text.length)
                break
            }
            answer.append(text, i, open)
            val contentStart = open + OPEN.length
            val close = text.indexOf(CLOSE, contentStart)
            if (close < 0) {
                think.append(text, contentStart, text.length)
                isThinking = true
                i = text.length
            } else {
                think.append(text, contentStart, close)
                i = close + CLOSE.length
            }
        }

        return Result(
            think = think.toString().trim().ifEmpty { null },
            isThinking = isThinking,
            answer = answer.toString().trim(),
        )
    }
}
