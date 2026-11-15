package com.march7th.hkrpg.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制序列 → 时间线文本 / 兜底步骤。
 *
 * 「录制完由 AI 完善」的输入就是这里的 timelineText：候选必须全部保留，
 * 重复动作也要在文本里标出来，否则模型没法判断该保留还是删掉。
 */
class TaskPackAiRecordingTest {

    private fun tap(
        seq: Int,
        texts: List<String> = emptyList(),
        templates: List<AiTemplateHit> = emptyList(),
        duplicateOf: Int? = null,
        op: String = "tap",
        x: Double = 0.5,
        y: Double = 0.5,
    ) = AiRecordedAction(
        seq = seq,
        op = op,
        x = x,
        y = y,
        texts = texts,
        templates = templates,
        duplicateOf = duplicateOf,
    )

    @Test
    fun timelineKeepsAllCandidates() {
        val text = AiRecording.timelineText(
            listOf(
                tap(1, texts = listOf("开始挑战", "挑战", "模拟宇宙")),
                tap(
                    2,
                    texts = listOf("确定"),
                    templates = listOf(
                        AiTemplateHit("assets/icon_ok.png", "确定按钮", 0.93),
                        AiTemplateHit("assets/icon_close.png", "关闭", 0.71),
                    ),
                ),
            ),
        )
        assertTrue(text.contains("#1 tap"))
        assertTrue(text.contains("开始挑战"))
        assertTrue(text.contains("挑战"))
        assertTrue(text.contains("assets/icon_ok.png"))
        assertTrue(text.contains("93%"))
    }

    @Test
    fun timelineMarksDuplicatesInsteadOfDropping() {
        val text = AiRecording.timelineText(listOf(tap(1), tap(2, duplicateOf = 1)))
        assertTrue(text.contains("#2"))
        assertTrue(text.contains("与 #1"))
    }

    @Test
    fun emptyTimelineIsEmptyString() {
        assertEquals("", AiRecording.timelineText(emptyList()))
    }

    @Test
    fun timelineDescribesSwipeAsFromTo() {
        val swipe = AiRecordedAction(
            seq = 1, op = "swipe", x = 0.2, y = 0.8, toX = 0.2, toY = 0.5, durationMs = 600,
        )
        val text = AiRecording.timelineText(listOf(swipe))
        assertTrue(text.contains("→"))
        assertTrue(text.contains("600ms"))
    }

    @Test
    fun toStepsPrefersTemplateWhenAssetAvailable() {
        val hit = AiTemplateHit("assets/icon_ok.png", "确定", 0.9)
        val steps = AiRecording.toSteps(
            listOf(tap(1, texts = listOf("确定"), templates = listOf(hit))),
            availableAssets = setOf("assets/icon_ok.png"),
        )
        assertEquals("tapTemplate", steps.single().op)
        assertEquals("assets/icon_ok.png", steps.single().image)
    }

    @Test
    fun toStepsFallsBackToTextWhenTemplateMissing() {
        val hit = AiTemplateHit("assets/icon_missing.png", "确定", 0.9)
        val steps = AiRecording.toSteps(
            listOf(tap(1, texts = listOf("确定"), templates = listOf(hit))),
            availableAssets = emptySet(),
        )
        assertEquals("tapText", steps.single().op)
        assertEquals("确定", steps.single().text)
    }

    @Test
    fun toStepsFallsBackToCoordinatesWithoutText() {
        val steps = AiRecording.toSteps(listOf(tap(1, x = 0.25, y = 0.75)))
        assertEquals("tap", steps.single().op)
        assertEquals(0.25, steps.single().x!!, 1e-9)
        assertEquals(0.75, steps.single().y!!, 1e-9)
    }

    @Test
    fun joystickSwipeKeepsDurationInAllowedRange() {
        val joystick = AiRecordedAction(
            seq = 1, op = "swipe", x = 0.2, y = 0.8, toX = 0.25, toY = 0.8,
            durationMs = 1_800, note = "摇杆：按住 1800ms，推杆 5%",
        )
        val step = AiRecording.toSteps(listOf(joystick)).single()
        assertEquals("swipe", step.op)
        assertEquals(1_800L, step.durationMs)
        assertTrue(step.note!!.contains("摇杆"))
    }

    @Test
    fun longPressKeepsMinimumDuration() {
        val action = AiRecordedAction(seq = 1, op = "longPress", x = 0.5, y = 0.5, durationMs = 40)
        val step = AiRecording.toSteps(listOf(action)).single()
        assertEquals("longPress", step.op)
        assertTrue("长按不能低于可执行的时长", step.durationMs!! >= 400)
    }

    @Test
    fun noteListsAlternativeCandidates() {
        val steps = AiRecording.toSteps(
            listOf(tap(1, texts = listOf("开始挑战", "挑战"), templates = listOf(
                AiTemplateHit("a.png", "按钮A", 0.9),
                AiTemplateHit("b.png", "按钮B", 0.8),
            ))),
        )
        val note = steps.single().note.orEmpty()
        assertTrue(note.contains("文字候选"))
        assertTrue(note.contains("开始挑战/挑战"))
        assertTrue(note.contains("按钮A/按钮B"))
    }

    @Test
    fun primaryAccessorsAreFirstCandidate() {
        val action = tap(1, texts = listOf("一", "二"), templates = listOf(AiTemplateHit("p", "n", 0.5)))
        assertEquals("一", action.primaryText)
        assertNotNull(action.primaryTemplate)
        assertNull(tap(2).primaryText)
    }
}
