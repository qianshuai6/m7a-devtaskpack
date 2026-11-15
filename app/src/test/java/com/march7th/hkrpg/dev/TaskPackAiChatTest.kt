package com.march7th.hkrpg.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 对话记录的裁剪规则。
 *
 * 面板按聊天记录渲染，长会话必须能自己瘦身，否则「记录几千条消息」会把
 * 每个状态更新都变成一次大列表拷贝。真正要守住的只有两条：
 * 1. 只从头部丢老消息，最新的一定留着；
 * 2. 正在等待回答的提问不能被丢掉，否则用户没地方回答。
 */
class TaskPackAiChatTest {

    private fun message(id: Long, body: String = "内容", kind: AiChatKind = AiChatKind.ASSISTANT) =
        AiChatMessage(id = id, kind = kind, title = "t$id", body = body)

    @Test
    fun appendKeepsOrderAndNewestMessage() {
        var list = emptyList<AiChatMessage>()
        repeat(5) { list = AiChat.append(list, message(it.toLong())) }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), list.map { it.id })
    }

    @Test
    fun appendDropsOldestWhenOverLimit() {
        var list = emptyList<AiChatMessage>()
        repeat(6) { list = AiChat.append(list, message(it.toLong()), max = 4) }
        assertEquals(listOf(2L, 3L, 4L, 5L), list.map { it.id })
    }

    @Test
    fun pendingQuestionSurvivesTrimming() {
        var list = emptyList<AiChatMessage>()
        val ask = message(999, kind = AiChatKind.ASK)
        list = AiChat.append(list, ask, keep = setOf(999L), max = 3)
        repeat(5) { list = AiChat.append(list, message(it.toLong()), keep = setOf(999L), max = 3) }
        assertEquals(3, list.size)
        assertTrue("等待回答的提问必须保留", list.any { it.id == 999L })
    }

    @Test
    fun clipTruncatesAndSaysHowMuchDropped() {
        val text = "a".repeat(50)
        val clipped = AiChat.clip(text, limit = 10)
        assertTrue(clipped.startsWith("aaaaaaaaaa"))
        assertTrue(clipped.contains("已截断 40 字"))
    }

    @Test
    fun shortBodyIsNotTouched() {
        assertEquals("短消息", AiChat.clip("  短消息  ", limit = 100))
    }

    @Test
    fun jsonSummaryCountsOpsAndParams() {
        val json = """
            {"schemaVersion":1,"params":[{"key":"times"},{"key":"mode"}],
             "steps":[{"op":"tapText"},{"op":"tapText"},{"op":"wait"}]}
        """.trimIndent()
        val summary = AiChat.jsonSummary(json)
        assertTrue(summary.contains("3 个 op"))
        assertTrue(summary.contains("tapText×2"))
        assertTrue(summary.contains("times"))
        assertTrue(summary.contains("mode"))
    }

    @Test
    fun idsAreStrictlyIncreasing() {
        val ids = (0 until 50).map { AiChat.nextId() }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.distinct().size, ids.size)
    }
}
