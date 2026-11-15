package com.march7th.hkrpg.dev

import java.util.concurrent.atomic.AtomicLong

/**
 * AI 生成面板里的一条对话记录。
 *
 * 生成过程本来只有一句 `aiStatus`（「正在生成…」），出问题时完全看不出模型在想什么、
 * 点过哪里、看到了什么。这里把每一步都落成一条消息，面板按聊天记录渲染：
 * 用户目标 → 模型的思考 → 模型的说明 → 真实动作（点击 / 截图 / 模板 / 提问）→
 * 校验回喂 → 最终结果。
 */
enum class AiChatKind {
    /** 用户：任务目标、补充说明、对提问的回答。 */
    USER,

    /** 模型正文（非 JSON、非标记的那部分）。 */
    ASSISTANT,

    /** 模型的思考过程（reasoning_content / reasoning 字段），默认折叠。 */
    THINKING,

    /** App 真实执行的动作：点击、截图、模板检索、画面变化、用户手动点击。 */
    ACTION,

    /** 系统状态与校验回喂。 */
    SYSTEM,

    /** 模型向用户提问，等待回答。 */
    ASK,

    /** 失败 / 被打断。 */
    ERROR,
}

data class AiChatMessage(
    val id: Long,
    val kind: AiChatKind,
    val title: String = "",
    val body: String = "",
    val at: Long = 0L,
    /** 长内容（思考、JSON）默认折叠，点一下展开。 */
    val collapsible: Boolean = kind == AiChatKind.THINKING,
)

/** 聊天记录的生成与裁剪：纯逻辑，方便单测（不依赖 Android / Compose）。 */
object AiChat {
    /** 最多保留多少条：一行一条气泡，超过就丢最早的，避免长会话把状态撑爆。 */
    const val MAX_MESSAGES = 240

    /** 单条正文上限；思考过程经常几千字，但整段塞进 Compose 会明显卡顿。 */
    const val MAX_BODY = 6000

    private val sequence = AtomicLong(System.currentTimeMillis())

    fun nextId(): Long = sequence.updateAndGet { previous ->
        maxOf(previous + 1, System.currentTimeMillis())
    }

    fun clip(text: String, limit: Int = MAX_BODY): String {
        val trimmed = text.trim()
        if (trimmed.length <= limit) return trimmed
        return trimmed.take(limit) + "\n…（已截断 ${trimmed.length - limit} 字）"
    }

    /**
     * 追加一条消息并裁剪长度。
     *
     * 只从头部丢老消息；[keep] 里的消息（例如正在等待回答的提问）永远保留，
     * 否则提问气泡被挤掉后用户就没法回答了。
     */
    fun append(
        current: List<AiChatMessage>,
        message: AiChatMessage,
        keep: Set<Long> = emptySet(),
        max: Int = MAX_MESSAGES,
    ): List<AiChatMessage> {
        val next = current + message.copy(body = clip(message.body), title = message.title.take(120))
        if (next.size <= max) return next
        val overflow = next.size - max
        val dropped = mutableListOf<AiChatMessage>()
        val kept = mutableListOf<AiChatMessage>()
        for ((index, item) in next.withIndex()) {
            if (dropped.size < overflow && index < next.lastIndex && item.id !in keep) {
                dropped += item
            } else {
                kept += item
            }
        }
        return kept
    }

    /** 把模型返回的 JSON 压成一行摘要，避免整份 task.json 刷屏。 */
    fun jsonSummary(json: String): String {
        val steps = Regex("\"op\"\\s*:\\s*\"([A-Za-z]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val params = Regex("\"key\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        return buildString {
            append("task.json ${json.length} 字符，${steps.size} 个 op")
            if (steps.isNotEmpty()) {
                append("：")
                append(steps.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                    .take(8).joinToString("、") { "${it.key}×${it.value}" })
            }
            if (params.isNotEmpty()) {
                append("；配置项 ")
                append(params.take(8).joinToString("、"))
            }
        }
    }
}
