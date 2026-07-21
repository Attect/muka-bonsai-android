package app.muka.bonsai

import app.muka.bonsai.ui.markdown.ThinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkParserTest {

    @Test
    fun `no think tag returns text as answer`() {
        val result = ThinkParser.split("你好，世界！")
        assertNull(result.think)
        assertFalse(result.isThinking)
        assertFalse(result.hasThink)
        assertEquals("你好，世界！", result.answer)
    }

    @Test
    fun `closed think block is extracted`() {
        val result = ThinkParser.split("<think>让我想想</think>答案是 42")
        assertEquals("让我想想", result.think)
        assertFalse(result.isThinking)
        assertEquals("答案是 42", result.answer)
    }

    @Test
    fun `unclosed think block marks streaming state`() {
        val result = ThinkParser.split("<think>正在推理")
        assertEquals("正在推理", result.think)
        assertTrue(result.isThinking)
        assertTrue(result.hasThink)
        assertEquals("", result.answer)
    }

    @Test
    fun `empty unclosed think still shows section`() {
        val result = ThinkParser.split("<think>")
        assertNull(result.think)
        assertTrue(result.isThinking)
        assertTrue(result.hasThink)
        assertEquals("", result.answer)
    }

    @Test
    fun `multiple think blocks are merged`() {
        val result = ThinkParser.split("<think>第一段</think>中间<think>第二段</think>结尾")
        assertEquals("第一段第二段", result.think)
        assertFalse(result.isThinking)
        assertEquals("中间结尾", result.answer)
    }

    @Test
    fun `stray close tag is kept as plain text`() {
        val result = ThinkParser.split("前面</think>后面")
        assertNull(result.think)
        assertFalse(result.isThinking)
        assertEquals("前面</think>后面", result.answer)
    }

    @Test
    fun `think content is trimmed`() {
        val result = ThinkParser.split("<think>\n  内容 \n</think>\n\n答案\n")
        assertEquals("内容", result.think)
        assertEquals("答案", result.answer)
    }
}
