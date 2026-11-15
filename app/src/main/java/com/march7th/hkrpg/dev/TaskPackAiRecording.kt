package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.PackStep
import java.util.Locale

/** 一次点击位置上匹配到的图标模板候选。 */
data class AiTemplateHit(
    val path: String,
    val name: String,
    /** CCOEFF 相关系数，0~1，越大越像。 */
    val score: Double,
)

/**
 * AI 生成期间静默录到的一个动作。
 *
 * 与「静默录制」的区别是这里的**每条动作都带全部候选**：
 * - [texts]：点击点附近 OCR 到的所有文字（可能多条：按钮文字 + 旁边说明 + 上一行标题）；
 * - [templates]：点击点附近匹配到的图标模板（可能多个：同一位置叠了底板与图标）。
 *
 * 之所以不合并、不去重，是因为录制阶段无法确定用户到底要哪个：候选保留下来交给模型判断，
 * 模型看到「OCR 同时读到『挑战』和『开始挑战』」「模板命中开始挑战按钮 0.93 / 关闭 0.41」
 * 才可能写对。冲突（和前面某条一模一样）也只标记 [duplicateOf]，不丢弃。
 */
data class AiRecordedAction(
    val seq: Int,
    /** tap / longPress / swipe。 */
    val op: String,
    /** 「用户」= 玩家自己点的；「AI」= 模型边点边录的。 */
    val source: String = SOURCE_USER,
    val x: Double,
    val y: Double,
    val toX: Double = x,
    val toY: Double = y,
    val durationMs: Long = 0L,
    val texts: List<String> = emptyList(),
    val templates: List<AiTemplateHit> = emptyList(),
    /** 动作发生时的缩略图（JPEG），用于确认面板；超出上限时为空。 */
    val thumb: ByteArray? = null,
    val at: Long = 0L,
    /** 与前面第几条完全相同（保留，但标记出来给模型与用户看）。 */
    val duplicateOf: Int? = null,
    /** 摇杆判定附带的信息（按住时长 / 推杆幅度）。 */
    val note: String = "",
) {
    val primaryText: String? get() = texts.firstOrNull()
    val primaryTemplate: AiTemplateHit? get() = templates.firstOrNull()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AiRecordedAction) return false
        return seq == other.seq
    }

    override fun hashCode(): Int = seq

    companion object {
        const val SOURCE_USER = "用户"
        const val SOURCE_AI = "AI"
    }
}

/** 录制序列 → 给模型 / 界面的紧凑文本，以及 → 可执行步骤的兜底转换。 */
object AiRecording {
    const val MAX_ACTIONS = 300
    private const val MAX_KEEP_ALTERNATIVES = 3

    /** 一段动作的坐标描述，例如 `(0.5120, 0.7330)`。 */
    fun point(x: Double, y: Double): String =
        "(" + String.format(Locale.US, "%.4f", x) + ", " + String.format(Locale.US, "%.4f", y) + ")"

    /**
     * 录制时间线：编号 + 动作 + 全部候选。
     *
     * 这段文本进 prompt，是「录制完由 AI 完善」的输入。刻意写全候选而不是只给第一个：
     * 模型需要自己判断哪个候选才是按钮文字，猜错也还有机会在最终包改。
     */
    fun timelineText(actions: List<AiRecordedAction>, limit: Int = MAX_ACTIONS): String {
        if (actions.isEmpty()) return ""
        return buildString {
            appendLine("以下是录制序列（按发生顺序，含全部 OCR / 模板候选，未做去重）：")
            actions.take(limit).forEach { action ->
                append("#${action.seq} ${action.op} · ${action.source} · ")
                if (action.op == "swipe") {
                    append("${point(action.x, action.y)} → ${point(action.toX, action.toY)}")
                } else {
                    append(point(action.x, action.y))
                }
                append(" · ${action.durationMs}ms")
                if (action.note.isNotBlank()) append(" · ${action.note}")
                appendLine()
                if (action.texts.isNotEmpty()) {
                    appendLine("    OCR 候选：" + action.texts.take(MAX_KEEP_ALTERNATIVES).joinToString(" ｜ "))
                }
                if (action.templates.isNotEmpty()) {
                    appendLine(
                        "    模板候选：" + action.templates.take(MAX_KEEP_ALTERNATIVES)
                            .joinToString(" ｜ ") {
                                "${it.path}（${it.name}，${(it.score * 100).toInt()}%）"
                            },
                    )
                }
                if (action.duplicateOf != null) {
                    appendLine("    ⚠ 与 #${action.duplicateOf} 位置/类型完全相同，可能是重复操作")
                }
            }
        }
    }

    /**
     * 兜底：把录制序列直接转成步骤。
     *
     * 优先用模板（最稳，但要素材已在包里）→ 其次文字（运行时 OCR 定位）→ 最后原始坐标。
     * 候选一多就产生歧义，这里只取「第一候选」，把其余候选写进 note，让开发者能看出录到了什么。
     */
    fun toSteps(
        actions: List<AiRecordedAction>,
        availableAssets: Set<String> = emptySet(),
    ): List<PackStep> = actions.mapNotNull { action ->
        val note = buildString {
            append("录制 #${action.seq}（${action.source}）")
            if (action.texts.size > 1) {
                append(" · 文字候选：").append(action.texts.take(MAX_KEEP_ALTERNATIVES).joinToString("/"))
            }
            if (action.templates.size > 1) {
                append(" · 模板候选：").append(
                    action.templates.take(MAX_KEEP_ALTERNATIVES).joinToString("/") { it.name },
                )
            }
            if (action.duplicateOf != null) append(" · 与 #${action.duplicateOf} 重复")
            if (action.note.isNotBlank()) append(" · ").append(action.note)
        }
        when (action.op) {
            "swipe" -> PackStep(
                op = "swipe",
                fromX = action.x,
                fromY = action.y,
                toX = action.toX,
                toY = action.toY,
                durationMs = action.durationMs.coerceIn(120, 10_000),
                note = note,
            )

            "longPress" -> PackStep(
                op = "longPress",
                x = action.x,
                y = action.y,
                durationMs = action.durationMs.coerceIn(400, 10_000),
                note = note,
            )

            else -> {
                val template = action.primaryTemplate?.takeIf { it.path in availableAssets }
                val text = action.primaryText
                when {
                    template != null -> PackStep(
                        op = "tapTemplate",
                        image = template.path,
                        timeoutMs = 10_000,
                        threshold = 0.8,
                        note = "$note · 点击模板「${template.name}」",
                    )

                    !text.isNullOrBlank() -> PackStep(
                        op = "tapText",
                        text = text,
                        timeoutMs = 10_000,
                        note = "$note · 点击文字「$text」",
                    )

                    else -> PackStep(
                        op = "tap",
                        x = action.x,
                        y = action.y,
                        durationMs = 80,
                        note = "$note · 点击坐标",
                    )
                }
            }
        }
    }
}
