package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.resource.CustomTaskPackValidator
import com.march7th.hkrpg.utils.JsonUtils
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class TaskPackAiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
)

data class TaskPackAiOutcome(
    val pack: CustomTaskPack? = null,
    val attempts: Int = 0,
    /** 本轮 AI 主动索取并成功发送的游戏画面张数。 */
    val screenshotsUsed: Int = 0,
    /** 本轮由 App 替 AI 真实执行的自动点击次数（生成期推进界面，不写进任务包）。 */
    val clicksUsed: Int = 0,
    /** 本轮 AI 按需从模板库索取的次数。 */
    val templateHits: Int = 0,
    /** 被用户主动打断，区别于失败。 */
    val interrupted: Boolean = false,
    val error: String? = null,
    /** 整轮生成的 token 用量（含缓存命中），界面据此显示命中率。 */
    val usage: TaskPackAiUsage = TaskPackAiUsage(),
    /** 模型向用户提问的次数。 */
    val asksUsed: Int = 0,
    /** 生成中途主动推给模型的画面变化 / 用户手动点击次数。 */
    val observationsUsed: Int = 0,
)

/** 一次自动点击的结果；observation 会回喂给模型当作「点击后的画面观察」。 */
data class TaskPackAiClickResult(
    val accepted: Boolean,
    val observation: String,
    /** 实际落点的归一化坐标（可能已由 App 做 clamp 或按 OCR 词框吸附修正）。 */
    val x: Double = 0.0,
    val y: Double = 0.0,
    /** 按文字点击时命中的画面原文，用于写 note 与自动录制。 */
    val text: String? = null,
)

/**
 * 生成期被 App 真实执行、并可以顺手录进任务包的一个动作。
 *
 * 「玩家不插手」模式靠它成立：模型只说做什么（点哪个文字 / 点哪个位置），
 * App 负责定位坐标、真实点击、并把这次点击写成一条步骤。
 */
data class TaskPackAiAction(
    val op: String,
    val x: Double,
    val y: Double,
    val text: String? = null,
    val note: String = "",
)

/**
 * OpenAI 兼容 Chat Completions 客户端，只负责“写任务包”，不参与任务执行。
 *
 * 画面策略（按需视觉）：
 * - 默认只把当前游戏画面的 **OCR 文字**作为上下文发给模型，纯文本端点即可工作；
 * - 模型判断文字不足以理解界面时，可主动输出 [SCREENSHOT_MARKER] 索取截图，
 *   客户端才抓取当前帧并以 `image_url` 多模态消息回填；
 * - 模型没索取就绝不发图，省流量也省视觉 token；
 * - 端点不支持图片或截图失败时自动降级为纯 OCR 文字继续，不让生成整体失败。
 *
 * 规范策略（让模型真正「读到」规范）：
 * - 模型无法自己联网打开 URL，因此由 [TaskPackSpec] 读取规范**正文**（内置全文 / 规范链接拉取），
 *   生成时通过 [generate] 的 specText 注入为独立 system 消息，要求逐条遵守；
 * - 与 SYSTEM_PROMPT 里的概要冲突时以注入的规范全文为准。
 *
 * 生成结果仍走 [CustomTaskPackValidator]；解析或校验失败会把错误回喂给模型，
 * 最多修正三轮。任务包格式、动作白名单和执行器均保持原项目版本。
 */
class TaskPackAiClient(
    private val httpClient: OkHttpClient,
) {
    private val parser = Json(JsonUtils.common) { ignoreUnknownKeys = true }
    /** 当前进行中的 HTTP 请求；打断生成时直接 cancel，避免等满读超时。 */
    private val activeCall = AtomicReference<Call?>(null)

    /** 打断：取消底层请求。协程取消由调用方负责。 */
    fun cancelActive() {
        activeCall.getAndSet(null)?.runCatching { cancel() }
    }

    suspend fun generate(
        config: TaskPackAiConfig,
        instruction: String,
        basePack: CustomTaskPack,
        /** 动态取当前可用素材；AI 索取图标模板后会把素材注册进工程，校验需看到最新集合。 */
        availableAssets: () -> Set<String>,
        appVersionCode: Int,
        screenText: String = "",
        /** 规范正文（task-file.md 全文等），由 [TaskPackSpec] 读取；非空时注入独立 system 消息。 */
        specText: String = "",
        specSourceNote: String = "",
        /** UI 开关：是否允许模型索取游戏画面截图。 */
        allowScreenshot: Boolean = true,
        /** 抓取当前游戏画面并返回 base64 JPEG；null 表示截图失败。 */
        screenshotProvider: (suspend () -> String?)? = null,
        /** 是否允许模型在生成过程中请求 App 自动点击游戏画面。 */
        allowAutoClick: Boolean = false,
        /** 参考坐标清单文本（已录制或用户手动点过）：仅作提示，模型不必受其限制。 */
        knownCoordinates: String = "",
        /** 执行一次真实点击并返回点击后观察；坐标由模型按画面自行决定，只用于推进界面。 */
        clickHandler: (suspend (Double, Double) -> TaskPackAiClickResult)? = null,
        /**
         * 按文字点击：模型只说「点哪个文字」，坐标由 App 用当前画面的 OCR 定位。
         *
         * 这是「玩家不插手」的主路径 —— 模型完全不接触坐标，输入框里只出现按钮文字，
         * 因此不需要用户先提供任何点位；OCR 命中后 App 直接用词框中心点击并录成步骤。
         */
        textClickHandler: (suspend (String) -> TaskPackAiClickResult)? = null,
        /**
         * 每次真实点击成功后回调，用于「边点边录」：把这个动作直接写成任务包步骤。
         * 为 null 时点击只用于探索，不写包。
         */
        onActionRecorded: (suspend (TaskPackAiAction) -> Unit)? = null,
        /** 规范原文检索：模型输出 [[NEED_SPEC:关键词]] 时回喂对应段落（配合摘录模式）。 */
        specProvider: (suspend (String) -> String)? = null,
        /** 调用方是否已把 specText 压成摘录（决定提示词措辞，不改变行为）。 */
        specDigest: Boolean = false,
        /** 画面尺寸：模型给像素坐标时按它换算成 0~1（虚拟屏固定 1920×1080）。 */
        canvasWidth: Int = 1920,
        canvasHeight: Int = 1080,
        /** 模板库索引摘要，注入 prompt 让模型知道有哪些模板可用。 */
        templateIndex: String = "",
        /** AI 按需索取模板：传入关键词，返回匹配模板详情（并已把图标素材注册进工程）。 */
        templateProvider: (suspend (String) -> String)? = null,
        /** 生成过程状态回调，用于界面展示“AI 索取了画面 / 自动点击 / 索取模板”。 */
        onStatus: (String) -> Unit = {},
        /**
         * 生成中途新增的「观察」：画面变了、用户自己点了游戏。
         *
         * 每轮请求前取一次（取完即清空）。这是「画面改动主动告诉 AI」和
         * 「用户手动点击也告诉 AI」的通道 —— 模型看不见屏幕，只能靠这些文字。
         */
        observations: (() -> List<String>)? = null,
        /** 模型输出 [[ASK:问题]] 时调用；返回用户回答，返回 null 表示取消/超时。 */
        askHandler: (suspend (String) -> String?)? = null,
        /** 聊天记录：生成过程的每条消息都推给界面，按对话记录渲染。 */
        onChat: (AiChatMessage) -> Unit = {},
    ): TaskPackAiOutcome = withContext(Dispatchers.IO) {
        val endpoint = runCatching { endpoint(config.baseUrl) }.getOrElse {
            return@withContext TaskPackAiOutcome(error = it.message)
        }
        if (config.model.isBlank()) {
            return@withContext TaskPackAiOutcome(error = "请填写模型名")
        }

        val templateAllowed = templateProvider != null && templateIndex.isNotBlank()
        val clickAllowed = allowAutoClick && (clickHandler != null || textClickHandler != null)
        val recordAllowed = clickAllowed && onActionRecorded != null

        val messages = mutableListOf<JsonObject>()
        // 只发一条 system，且内容严格确定：顺序固定、不含时间戳 / 项目名 / 当前步骤数。
        // 服务端前缀缓存按「从头开始的连续 token」匹配，前缀里只要有一个字符变了后面全废；
        // 合并成一条稳定 system 是提高命中率最有效的一步。
        messages += message(
            "system",
            buildStableSystem(specText, templateIndex, specDigest, specProvider != null),
        )
        messages += message(
            "user",
            buildUserPrompt(
                instruction, basePack, availableAssets(), screenText,
                clickAllowed, textClickHandler != null, recordAllowed,
                knownCoordinates,
                screenshotProvider != null && allowScreenshot, templateAllowed,
            ),
        )

        var screenAllowed = allowScreenshot && screenshotProvider != null
        var screenshots = 0
        var clicks = 0
        var templateHits = 0
        var repairs = 0
        var rounds = 0
        var lastWasImage = false
        var clickNudged = false
        var usageTotal = TaskPackAiUsage()
        var asks = 0
        var observationCount = 0
        val sentObservations = LinkedHashSet<String>()
        val repairLog = mutableListOf<String>()

        onChat(
            AiChatMessage(
                id = AiChat.nextId(),
                kind = AiChatKind.USER,
                title = "任务目标",
                body = instruction.trim(),
                at = System.currentTimeMillis(),
            ),
        )

        while (rounds < MAX_ROUNDS) {
            // 打断后立刻收尾：activeCall 被 cancel 会抛 IOException，这里转成「已打断」而不是失败。
            if (!currentCoroutineContext().isActive) {
                return@withContext TaskPackAiOutcome(
                    attempts = rounds, screenshotsUsed = screenshots, clicksUsed = clicks,
                    templateHits = templateHits, interrupted = true,
                )
            }
            rounds++
            // 画面变了、用户自己点了游戏：模型看不到屏幕，只能每轮把这些补进对话。
            // 去重后限量，避免同一屏反复变化把上下文撑爆。
            val provider = observations
            val pending: List<String> = if (provider == null) {
                emptyList()
            } else {
                runCatching { provider.invoke() }.getOrNull().orEmpty()
            }
            if (pending.isNotEmpty() && observationCount < MAX_OBSERVATIONS) {
                pending.take(MAX_OBSERVATIONS - observationCount).forEach { text ->
                    val clipped = AiChat.clip(text, 1500)
                    if (clipped.isBlank() || !sentObservations.add(clipped)) return@forEach
                    observationCount++
                    messages += message("user", clipped)
                    onChat(
                        AiChatMessage(
                            id = AiChat.nextId(),
                            kind = AiChatKind.ACTION,
                            title = "画面 / 操作更新（已发给 AI）",
                            body = clipped,
                            at = System.currentTimeMillis(),
                        ),
                    )
                }
                onStatus("已把当前画面变化同步给 AI…")
            }
            val call = try {
                request(endpoint, config, messages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) {
                    return@withContext TaskPackAiOutcome(
                        attempts = rounds, screenshotsUsed = screenshots, clicksUsed = clicks,
                        templateHits = templateHits, interrupted = true,
                    )
                }
                // 上一条是图片消息：多半是端点/模型不支持多模态，撤回图片改纯文字继续。
                if (lastWasImage) {
                    runCatching { messages.removeAt(messages.lastIndex) }
                    screenAllowed = false
                    lastWasImage = false
                    messages += message(
                        "user",
                        "截图发送失败（当前模型或服务端可能不支持图片输入）。" +
                            "请仅依据已提供的 OCR 文字继续，直接输出最终完整 task.json，不要再次索取画面。",
                    )
                    onStatus("画面发送失败，已改为仅提供 OCR 文字")
                    continue
                }
                return@withContext TaskPackAiOutcome(
                    attempts = rounds,
                    screenshotsUsed = screenshots,
                    clicksUsed = clicks,
                    templateHits = templateHits,
                    error = e.message ?: "AI 请求失败",
                )
            }
            lastWasImage = false
            usageTotal = usageTotal.plus(call.usage)
            val raw = call.content
            // 思考过程（DeepSeek-R1 / Qwen 等会单独返回 reasoning_content）原样上屏：
            // 模型判断「该点哪里」的依据全在这里，看不到就只能猜它为什么乱点。
            if (call.reasoning.isNotBlank()) {
                onChat(
                    AiChatMessage(
                        id = AiChat.nextId(),
                        kind = AiChatKind.THINKING,
                        title = "思考过程",
                        body = call.reasoning,
                        at = System.currentTimeMillis(),
                        collapsible = true,
                    ),
                )
            }
            val fullPackReply = raw.contains("\"schemaVersion\"")
            when {
                fullPackReply -> onChat(
                    AiChatMessage(
                        id = AiChat.nextId(),
                        kind = AiChatKind.ASSISTANT,
                        title = "AI 输出的任务包",
                        body = AiChat.jsonSummary(raw),
                        at = System.currentTimeMillis(),
                        collapsible = true,
                    ),
                )

                !raw.contains("[[") && raw.length <= 4000 -> onChat(
                    AiChatMessage(
                        id = AiChat.nextId(),
                        kind = AiChatKind.ASSISTANT,
                        title = "AI",
                        body = raw,
                        at = System.currentTimeMillis(),
                    ),
                )
            }
            val specMatch = if (specProvider != null) NEED_SPEC_PATTERN.find(raw) else null
            if (specMatch != null && !raw.contains("\"schemaVersion\"") && raw.length <= 500) {
                val query = specMatch.groupValues[1].trim()
                val detail = runCatching { specProvider?.invoke(query) }.getOrNull().orEmpty()
                messages += message("assistant", raw)
                messages += message(
                    "user",
                    buildString {
                        appendLine("已按关键词「$query」从规范原文取回相关段落：")
                        appendLine(detail.ifBlank { "（没有匹配的段落，请换更短的关键词，或直接按已给的摘录写）" })
                        appendLine("请据此继续，只输出最终完整 task.json。")
                    },
                )
                onStatus("AI 索取了规范原文（$query），已回喂对应段落…")
                onChat(
                    AiChatMessage(
                        id = AiChat.nextId(),
                        kind = AiChatKind.ACTION,
                        title = "查阅规范原文：$query",
                        body = detail.ifBlank { "（没有匹配段落）" },
                        at = System.currentTimeMillis(),
                        collapsible = true,
                    ),
                )
                continue
            }

            // AI 边生成边自动点击：模型输出 [[TAP:文字]] 或 [[CLICK:x,y]]，
            // App 负责定位坐标（TAP 走 OCR）、真实点击、并把这次点击录成步骤（开启时）。
            val tapMatch = TAP_PATTERN.find(raw)
            val clickMatch = if (tapMatch == null) CLICK_PATTERN.find(raw) else null
            // 不再要求整段不含 `{`：模型常在标记前加一句说明。只要没同时给出完整
            // task.json（含 schemaVersion / steps）就按点击请求处理。
            val looksLikePack = raw.contains("\"schemaVersion\"") || raw.contains("\"steps\"")
            if ((tapMatch != null || clickMatch != null) && !looksLikePack && raw.length <= 1500) {
                if (!clickAllowed || clicks >= MAX_CLICKS) {
                    messages += message("assistant", raw)
                    messages += message(
                        "user",
                        "当前不能再自动点击游戏画面（未开启或已达 $MAX_CLICKS 次上限）。" +
                            "请直接输出最终完整 task.json，不要再次请求点击。",
                    )
                    continue
                }
                clicks++
                val result: TaskPackAiClickResult? = runCatching<TaskPackAiClickResult?> {
                    if (tapMatch != null) {
                        textClickHandler?.invoke(tapMatch.groupValues[1].trim())
                    } else {
                        val nx = clickMatch?.groupValues?.get(1)?.toDoubleOrNull()
                        val ny = clickMatch?.groupValues?.get(2)?.toDoubleOrNull()
                        if (nx == null || ny == null) null else clickHandler?.invoke(nx, ny)
                    }
                }.getOrNull()
                // 边点边录：只有真正点中了才写步骤，避免把失败的探索也灌进包里。
                if (result?.accepted == true && onActionRecorded != null) {
                    runCatching {
                        onActionRecorded(
                            TaskPackAiAction(
                                op = "tap",
                                x = result.x,
                                y = result.y,
                                text = result.text,
                                note = result.text?.let { "点击文字「$it」" } ?: "点击画面位置",
                            ),
                        )
                    }
                }
                messages += message("assistant", raw)
                messages += message("user", buildString {
                    appendLine(if (result?.accepted == true) "已按你的请求执行点击。" else "这次点击请求未被执行。")
                    appendLine(result?.observation ?: "坐标无效或点击执行失败。")
                    if (result?.accepted == true && onActionRecorded != null) {
                        appendLine("这次点击已由系统录成任务包的第 $clicks 条动作（坐标由系统定位并校正），不需要你再写一遍。")
                    }
                    appendLine(
                        "请据此继续：可以继续点击推进界面，也可以直接输出最终完整 task.json。" +
                            "坐标由系统负责（TAP 用 OCR 定位、数值坐标会归一化并裁剪），" +
                            "所以最终 task.json 里直接写 tap 的 x/y 或用 tapText 都可以，不必等用户给点。",
                    )
                })
                onStatus(
                    if (result?.accepted == true) {
                        "AI 自动点击第 $clicks 次（坐标由系统定位），已回传点击后画面文字…"
                    } else {
                        "AI 的自动点击请求未执行（服务未连接、文字未识别或坐标无效）"
                    }
                )
                onChat(
                    AiChatMessage(
                        id = AiChat.nextId(),
                        kind = if (result?.accepted == true) AiChatKind.ACTION else AiChatKind.ERROR,
                        title = if (tapMatch != null) {
                            "自动点击「${tapMatch.groupValues[1].trim()}」"
                        } else {
                            "自动点击坐标"
                        },
                        body = result?.observation ?: "点击未执行（坐标无效或服务未连接）",
                        at = System.currentTimeMillis(),
                    ),
                )
                continue
            }

            // 模型向用户提问：写包时经常缺关键信息（哪个角色、跑几次、异常怎么办），
            // 与其瞎猜，不如停下来问一句。App 把问题挂在面板顶部等用户输入。
            val askMatch = ASK_PATTERN.find(raw)
            if (askMatch != null && !looksLikePack && raw.length <= 1200) {
                val question = askMatch.groupValues[1].trim()
                messages += message("assistant", raw)
                onChat(
                    AiChatMessage(
                        id = AiChat.nextId(),
                        kind = AiChatKind.ASK,
                        title = "AI 想确认一件事",
                        body = question,
                        at = System.currentTimeMillis(),
                    ),
                )
                onStatus("AI 在向你提问，回答后继续生成…")
                val answer = if (askHandler == null || asks >= MAX_ASKS) {
                    null
                } else {
                    asks++
                    runCatching { askHandler.invoke(question) }.getOrNull()
                }
                if (answer.isNullOrBlank()) {
                    messages += message(
                        "user",
                        "用户没有回答这个问题。请按最稳妥的默认处理继续，" +
                            "把不确定的地方设成 optional=true 或写成配置项 params，直接输出最终 task.json。",
                    )
                    onChat(
                        AiChatMessage(
                            id = AiChat.nextId(),
                            kind = AiChatKind.SYSTEM,
                            title = "问题未回答",
                            body = "已让 AI 按最稳妥的默认继续（不确定的步骤会标 optional 或做成配置项）。",
                            at = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    messages += message("user", "用户回答：" + answer.trim())
                    onChat(
                        AiChatMessage(
                            id = AiChat.nextId(),
                            kind = AiChatKind.USER,
                            title = "你的回答",
                            body = answer.trim(),
                            at = System.currentTimeMillis(),
                        ),
                    )
                }
                continue
            }

            // AI 从模板库按需索取：先检索（并注册所需图标素材），再把详情回喂。
            val templateQuery = TEMPLATE_PATTERN.find(raw)?.groupValues?.get(1)
            if (templateQuery != null && '{' !in raw && raw.length <= 300) {
                if (!templateAllowed || templateHits >= MAX_TEMPLATE_HITS) {
                    messages += message("assistant", raw)
                    messages += message(
                        "user",
                        "模板库索取次数已用尽或未开启。请依据已提供的模板信息与 OCR 文字，" +
                            "直接输出最终完整 task.json，不要再次索取模板。",
                    )
                    continue
                }
                templateHits++
                // templateAllowed 已保证此处非空（Kotlin 也会做智能推断）。
                val detail = runCatching { templateProvider(templateQuery) }.getOrNull()
                messages += message("assistant", raw)
                messages += message(
                    "user",
                    buildString {
                        appendLine("已按关键词「$templateQuery」检索模板库，结果如下：")
                        appendLine(detail.orEmpty().ifBlank { "（没有匹配的模板，请换个关键词或不使用模板）" })
                        appendLine(
                            "可以使用上面列出的确切 assets/ 路径作为 image；" +
                                "也可以照抄整包/步骤模板里的字段写法。请据此继续，只输出最终完整 task.json。",
                        )
                    },
                )
                onStatus("AI 索取了第 $templateHits 次模板（$templateQuery），已回喂匹配结果…")
                onChat(
                    AiChatMessage(
                        id = AiChat.nextId(),
                        kind = AiChatKind.ACTION,
                        title = "检索模板库：$templateQuery",
                        body = detail.orEmpty().ifBlank { "（没有匹配的模板）" },
                        at = System.currentTimeMillis(),
                        collapsible = true,
                    ),
                )
                continue
            }

            if (isScreenshotRequest(raw)) {
                if (!screenAllowed || screenshots >= MAX_SCREENSHOTS) {
                    messages += message("assistant", raw)
                    messages += message(
                        "user",
                        "当前不能再提供更多画面截图。请仅依据已提供的 OCR 文字与已有截图，" +
                            "输出最终完整 task.json，不要解释，不要再次索取画面。",
                    )
                    continue
                }
                val base64 = runCatching { screenshotProvider?.invoke() }.getOrNull()
                screenshots++
                messages += message("assistant", raw)
                if (base64.isNullOrBlank()) {
                    messages += message(
                        "user",
                        "当前无法截取游戏画面（截图失败）。请仅依据已提供的 OCR 文字继续，" +
                            "输出最终完整 task.json，不要再次索取画面。",
                    )
                    screenAllowed = false
                    onStatus("截图失败，已改为仅提供 OCR 文字")
                } else {
                    messages += imageMessage(
                        "这是当前游戏画面的实时截图（第 $screenshots 次，共最多 $MAX_SCREENSHOTS 次）。" +
                            "请据此理解界面布局，决定下一步点哪里（[[TAP:文字]] 或估算坐标，坐标由系统校正），" +
                            "或输出最终完整 task.json。模板只能用已注册的 assets 路径，不要臆造图片。",
                        base64,
                    )
                    lastWasImage = true
                    onStatus("AI 索取了第 $screenshots 张画面，已发送截图，等待继续生成…")
                    onChat(
                        AiChatMessage(
                            id = AiChat.nextId(),
                            kind = AiChatKind.ACTION,
                            title = "发送第 $screenshots 张游戏画面",
                            body = "AI 主动索取截图，已发送当前画面（多模态）。",
                            at = System.currentTimeMillis(),
                        ),
                    )
                }
                continue
            }

            var candidateText = runCatching { extractJsonObject(raw) }.getOrNull()
            var candidate = candidateText?.let {
                runCatching {
                    parser.decodeFromString(CustomTaskPack.serializer(), it)
                }.getOrNull()
            }
            // 坐标由软件提供：模型给像素、给越界值都行，这里统一换算成 0~1 并裁剪，
            // 不因为它「坐标写歪了」把一整轮生成判失败再重试。
            candidate = candidate?.let { TaskPackCoordinateFix.normalize(it, canvasWidth, canvasHeight) }
            // 配置项：AI 可以自己加 params（跑几次、选哪个角色），但已有配置项不能因为
            // 模型这次没返回就丢掉 —— 只按 key 覆盖，其余原样保留。
            candidate = candidate?.let { mergeParams(basePack.params, it) }
            // 解析失败先试一次语法修复（多逗号 / 注释 / 单引号 / 尾括号缺失），
            // 模型偶尔会输出这种「差一点就对」的 JSON，直接重试一轮很浪费。
            if (candidate == null && candidateText != null) {
                val repaired = runCatching { TaskPackJsonRepair.repair(candidateText!!) }.getOrNull()
                if (repaired != null && repaired.parsed != null) {
                    candidate = runCatching {
                        parser.decodeFromString(CustomTaskPack.serializer(), repaired.text)
                    }.getOrNull()
                    if (candidate != null) {
                        candidateText = repaired.text
                        onStatus("AI 返回的 JSON 有语法问题，已自动修复后继续校验…")
                    }
                }
            }

            val errors = mutableListOf<String>()
            if (candidate == null) {
                errors += "返回内容不是可解析的 task.json"
            } else {
                errors += CustomTaskPackValidator.validate(candidate, appVersionCode, availableAssets())
                if (candidate.schemaVersion != 1) errors += "schemaVersion 必须是 1"
                if (candidate.minAppVersionCode != 11) {
                    errors += "minAppVersionCode 必须是 11（第一个支持任务包的原版 App）"
                }
                if (!TaskPackNaming.isValidVersion(candidate.version)) {
                    errors += "version 必须是 x.x-y.y（任务包版本 x.x + 游戏版本 y.y，用短横线连接）；" +
                        "确实没有对应游戏版本时可只写任务包版本，如 1.0.0"
                }
            }
            if (candidate != null && errors.isEmpty()) {
                // 首轮就交卷且一次都没点过：提醒一次「先探索再定稿」。
                // 模型若确认已看得足够，下一轮原样再输出即可被接受，不会死循环。
                if (clickAllowed && clicks == 0 && !clickNudged) {
                    clickNudged = true
                    messages += message("assistant", raw)
                    messages += message(
                        "user",
                        buildString {
                            appendLine("你还没有实际推进过界面，而这些点击本来会由系统自动录成步骤。")
                            appendLine("如果后面的步骤涉及当前 OCR / 截图里还没出现的界面（弹窗、二级菜单、翻页、确认键），")
                            appendLine("请先输出一行 [[TAP:按钮文字]]（或 [[CLICK:x,y]]）让系统真实点击，")
                            appendLine("再根据回传的新画面文字继续；这一步会自动录进任务包。")
                            appendLine("如果确实已经掌握全部界面，请直接重新输出同一份完整 task.json（内容不必改动）。")
                        },
                    )
                    onStatus("提醒 AI 先探索点击再定稿…")
                    continue
                }
                return@withContext TaskPackAiOutcome(
                    pack = candidate,
                    attempts = rounds,
                    screenshotsUsed = screenshots,
                    clicksUsed = clicks,
                    templateHits = templateHits,
                    usage = usageTotal,
                    asksUsed = asks,
                    observationsUsed = observationCount,
                )
            }

            val feedback = errors.joinToString("\n")
            repairLog += feedback
            repairs++
            onChat(
                AiChatMessage(
                    id = AiChat.nextId(),
                    kind = AiChatKind.SYSTEM,
                    title = "第 $repairs 次未通过校验，已回喂修正",
                    body = feedback,
                    at = System.currentTimeMillis(),
                    collapsible = true,
                ),
            )
            if (repairs >= MAX_ATTEMPTS) break
            messages += message("assistant", raw)
            messages += message(
                "user",
                "上次输出未通过原版校验：\n$feedback\n" +
                    "请修正后只返回一份完整、可导入的 task.json，不要解释，不要增删动作白名单。",
            )
            onStatus("第 $repairs 次生成未通过校验，正在回喂修正…")
        }

        TaskPackAiOutcome(
            attempts = rounds,
            screenshotsUsed = screenshots,
            clicksUsed = clicks,
            templateHits = templateHits,
            usage = usageTotal,
            asksUsed = asks,
            observationsUsed = observationCount,
            error = "多轮后仍未通过校验：\n${repairLog.lastOrNull().orEmpty()}",
        )
    }

    /** 按 key 合并配置项：模型新给的同名项覆盖默认值，模型没提的旧项保留。 */
    private fun mergeParams(
        base: List<com.march7th.hkrpg.data.model.PackParam>,
        pack: CustomTaskPack,
    ): CustomTaskPack {
        if (base.isEmpty()) return pack
        val merged = LinkedHashMap<String, com.march7th.hkrpg.data.model.PackParam>()
        base.forEach { if (it.key.isNotBlank()) merged[it.key] = it }
        pack.params.forEach { if (it.key.isNotBlank()) merged[it.key] = it }
        val list = merged.values.toList()
        return if (list == pack.params) pack else pack.copy(params = list)
    }

    /** 一次模型调用的结果：正文 + 思考过程 + 服务端上报的 token 用量（含缓存命中）。 */
    private data class AiCall(
        val content: String,
        val usage: TaskPackAiUsage,
        val reasoning: String = "",
    )

    private fun request(
        endpoint: String,
        config: TaskPackAiConfig,
        messages: List<JsonObject>,
    ): AiCall {
        val body = buildJsonObject {
            put("model", config.model.trim())
            put("temperature", 0.2)
            put("stream", false)
            put("messages", JsonArray(messages))
        }.toString()
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey.trim()}")
                }
            }
            .build()
        // 长流程生成可能超过普通站点请求的 60 秒读超时，单独给 AI 调用放宽。
        val client = httpClient.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(request)
        activeCall.set(call)
        try {
            val text = call.execute().use { response ->
                val raw = response.body.string()
                if (raw.length > MAX_RESPONSE_CHARS) error("AI 响应过大，已停止解析")
                if (!response.isSuccessful) {
                    error("AI 服务返回 HTTP ${response.code}：${raw.take(500)}")
                }
                raw
            }
            val root = parser.parseToJsonElement(text) as? JsonObject
                ?: error("AI 服务响应不是 JSON 对象")
            val choices = root["choices"] as? JsonArray
                ?: error("AI 响应缺少 choices")
            val first = choices.firstOrNull() as? JsonObject
                ?: error("AI 响应没有候选结果")
            val messageObject = first["message"] as? JsonObject
                ?: error("AI 响应缺少 message")
            val content = extractContent(messageObject["content"])
                .ifBlank { error("AI 返回了空内容") }
            // 思考过程：DeepSeek / Qwen / 部分网关用 reasoning_content 或 reasoning 单独返回。
            val reasoning = listOf("reasoning_content", "reasoning", "thinking")
                .firstNotNullOfOrNull { key ->
                    messageObject[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                }
                .orEmpty()
            return AiCall(content, TaskPackAiUsageParser.parse(root), reasoning)
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun extractContent(content: kotlinx.serialization.json.JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { item ->
            (item as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")
        else -> ""
    }

    private fun extractJsonObject(raw: String): String {
        val withoutFence = raw.trim()
            .removePrefix("```json").removePrefix("```JSON")
            .removePrefix("```").removeSuffix("```").trim()
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        require(start >= 0 && end > start) { "AI 没有返回完整 JSON 对象" }
        return withoutFence.substring(start, end + 1)
    }

    /** 模型主动索取画面的标记：必须独占一条回复，不能混在 JSON 里。 */
    private fun isScreenshotRequest(raw: String): Boolean =
        SCREENSHOT_MARKER in raw && '{' !in raw && raw.length <= 400

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** OpenAI 兼容的多模态 user 消息：文字说明 + base64 JPEG。 */
    private fun imageMessage(caption: String, base64: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", caption)
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", "data:image/jpeg;base64,$base64")
                })
            })
        })
    }

    /** 把规范正文包装成一条强约束 system 消息，明确「以全文为准」。 */
    /**
     * 稳定 system 前缀：角色与规则 → 规范 → 模板索引，顺序与分隔符都固定。
     *
     * 这条消息里**不允许**出现时间、项目名、当前步骤数、OCR 文字等任何会变化的内容：
     * 服务端前缀缓存按从头开始的连续 token 匹配，前缀里改一个字符，后面全部作废。
     * 动态信息一律放到后面的 user 消息，这样同一份规范下每次生成都能命中缓存。
     */
    private fun buildStableSystem(
        specText: String,
        templateIndex: String,
        specDigest: Boolean,
        specLookup: Boolean,
    ): String = buildString {
        append(SYSTEM_PROMPT.trimEnd())
        if (specText.isNotBlank()) {
            appendLine()
            appendLine()
            if (specDigest) {
                appendLine("## 任务文件规范 · 精要摘录")
                appendLine("以下是《任务文件开发规范》的摘录，op、字段、取值区间与约束均以此为准；")
                appendLine("摘录没覆盖到的细节，可输出一行 [[NEED_SPEC:关键词]] 让系统回喂原文段落。")
            } else {
                appendLine("## 任务文件开发规范（全文，强制依据）")
                appendLine("以下全文优先于上面的概要：字段、动作白名单、取值区间、约束、嵌套与表达式规则一律以它为准。")
            }
            appendLine("===== 规范开始 =====")
            appendLine(specText.trim())
            appendLine("===== 规范结束 =====")
            if (specLookup) {
                appendLine()
                appendLine("需要规范原文的某个细节时，只输出一行 [[NEED_SPEC:关键词]]（如 [[NEED_SPEC:swipeUntil]]），系统会回喂对应段落。")
            }
        }
        if (templateIndex.isNotBlank()) {
            appendLine()
            appendLine("## 任务包模板库索引")
            appendLine("需要图标、整包写法或步骤骨架时，只输出一行 [[NEED_TEMPLATE:关键词]]，系统会回喂确切 assets 路径与字段写法。")
            appendLine("不要臆造 assets/ 路径；索取后再引用。")
            appendLine()
            appendLine(templateIndex.trim())
        }
    }

    private fun buildUserPrompt(
        instruction: String,
        basePack: CustomTaskPack,
        assets: Set<String>,
        screenText: String,
        allowAutoClick: Boolean,
        allowTextClick: Boolean,
        autoRecord: Boolean,
        knownCoordinates: String,
        allowScreenshot: Boolean,
        allowTemplateLookup: Boolean,
    ): String = buildString {
        // 稳定部分在前（写作要求 / 当前包 / 素材），会变的部分（OCR 文字）放最后：
        // 同一会话多轮回喂时，前面的 token 序列不容易被打断，缓存更容易命中。
        appendLine("写作要求：")
        appendLine(instruction.trim())
        appendLine()
        appendLine("当前任务包（保留其中有效的元数据、录制步骤和已有素材；没有步骤时可全新生成）：")
        appendLine(compactManifest(basePack))
        appendLine()
        appendLine("当前包内可用模板（除此之外不得引用或臆造模板）：")
        appendLine(if (assets.isEmpty()) "（无）" else assets.sorted().joinToString("\n"))

        appendLine()
        appendLine("## 配置项：你可以自己产生 params")
        appendLine("流程里有「跑几次」「选哪个角色」「是否领取额外奖励」这类每次都可能不同的值时，在 params 里声明，")
        appendLine("步骤里用 {key} 引用；setVar 的 expr 里直接写变量名（不要花括号）。key 用英文小写+下划线，label 写中文，")
        appendLine("default 必填，类型按 int / bool / string 写。已有配置项会被保留，同名覆盖即可，不要重复声明。")

        appendLine()
        appendLine("## 不确定就问用户")
        appendLine("缺少关键信息（用哪个角色、跑几次、异常时重试还是跳过）时，只输出一行 [[ASK:要问的问题]]，")
        appendLine("系统会把问题显示给用户并等回答，答完你再继续。能自己判断就不要问，问也不要超过 6 次。")

        appendLine()
        appendLine("## 中途可能出现的新信息")
        appendLine("生成过程中如果游戏画面自己变了，或用户手动点了游戏，系统会把这些作为 user 消息补给你（含点到了什么文字）。")
        appendLine("收到后请以新画面为准：已发生过的操作不用重复写，条件判断要按新界面调整。")

        if (allowAutoClick) {
            appendLine()
            appendLine("## 本次已开启「自动点击」：你负责推进，系统负责坐标")
            if (allowTextClick) {
                appendLine("推荐按文字点：只输出一行 [[TAP:按钮文字]]，系统用当前画面的 OCR 定位该文字并真实点击。")
                appendLine("文字必须与画面显示一致（例如 [[TAP:开始挑战]]）；找不到就退回 [[CLICK:x,y]]。")
            }
            appendLine("也可以给坐标：只输出一行 [[CLICK:x,y]]。x、y 可以写 0~1 归一化值，也可以写 1920×1080 像素值，")
            appendLine("系统会自动归一化、裁剪到合法区间，并在附近有文字时吸附到文字中心——所以不需要精确，也不需要用户先给点。")
            appendLine("系统会真实点击游戏，并把点击后的画面 OCR 文字回给你，你据此继续判断。")
            if (autoRecord) {
                appendLine("**每次点击成功后会自动录成任务包的一条 tap 步骤（含 note）**：写流程时不必再把这一步重复写进 task.json，")
                appendLine("否则会重复执行。你只需在最终 task.json 里安排等待、判断、循环等点击之外的部分。")
            } else {
                appendLine("这些点击只用于推进界面，不会写进任务包；最终 task.json 要自己把步骤写全。")
            }
            appendLine("「坐标必须来自用户」这条限制已经取消：坐标由系统提供与校正，你按画面估算即可。最多 $MAX_CLICKS 次点击。")
            if (knownCoordinates.isNotBlank()) {
                appendLine()
                appendLine("参考坐标（可选，系统已录制或用户手动提供，可优先复用）：")
                appendLine(knownCoordinates)
            }
        } else {
            appendLine()
            appendLine("本次未开启自动点击，请不要输出 [[CLICK:x,y]] 或 [[TAP:文字]]。")
            appendLine("坐标由系统在导出时归一化与裁剪，你按画面估算 0~1 即可，不必等用户提供点。")
        }

        if (allowScreenshot) {
            appendLine()
            appendLine(
                "如果上面的文字不足以判断界面，且你需要看真实画面，" +
                    "请只输出一行 $SCREENSHOT_MARKER（可附一句原因），系统会立即附上当前游戏画面截图；" +
                    "否则直接输出最终 task.json，不要索取画面。",
            )
        }
        if (allowTemplateLookup) {
            appendLine()
            appendLine(
                "如果你需要模板图、某个 op 的标准写法或现成整包流程，" +
                    "请只输出一行 $TEMPLATE_MARKER（可附一句原因），系统会回喂匹配模板的确切 assets 路径与字段；" +
                    "否则直接输出最终 task.json。",
            )
        }

        appendLine()
        appendLine("当前游戏画面 OCR 文字（仅供理解界面；坐标由系统负责，不需要从这里量像素）：")
        appendLine(screenText.ifBlank { "（未识别到画面文字）" })
    }

    /**
     * 当前包发给模型时的瘦身版：大包只给前若干步 + 结构摘要。
     *
     * 已有 800 步的包整份发出去就是几万 token，而模型真正需要的是「已有的开头写法」
     * 和「后面的结构」，不需要逐步读完。截断能显著降低每轮输入，命中率也更容易稳住。
     */
    private fun compactManifest(pack: CustomTaskPack): String {
        val steps = pack.steps
        if (steps.size <= COMPACT_STEPS) return TaskPackProject.manifestJson(pack)
        val head = steps.take(COMPACT_STEPS)
        val rest = steps.drop(COMPACT_STEPS)
        val summary = buildString {
            appendLine("…（为控制输入长度，已省略后续 ${rest.size} 步，仅给出结构摘要）")
            val counts = rest.groupingBy { it.op }.eachCount().entries
                .sortedByDescending { it.value }.take(12)
            appendLine("后续步骤 op 分布：" + counts.joinToString("、") { "${it.key}×${it.value}" })
            val texts = rest.mapNotNull { it.text }.distinct().take(20)
            if (texts.isNotEmpty()) appendLine("后续涉及的界面文字（节选）：" + texts.joinToString("、"))
        }
        // 只在 JSON 后面追加纯文本摘要，不塞伪字段：模型很容易把 _omitted_* 照抄进结果。
        val headJson = TaskPackProject.manifestJson(pack.copy(steps = head))
        return headJson.trimEnd() + "\n\n" + summary.trimEnd()
    }

    private fun endpoint(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.isNotBlank()) { "请填写 API 地址" }
        val url = if (value.endsWith("/chat/completions")) value else "$value/chat/completions"
        val uri = URI(url)
        require(uri.scheme == "https" || uri.scheme == "http") { "API 地址只支持 http/https" }
        if (uri.scheme == "http") {
            require(uri.host in setOf("127.0.0.1", "localhost", "::1")) {
                "明文 HTTP 只允许本机模型服务；其他服务请使用 HTTPS"
            }
        }
        return url
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val MAX_SCREENSHOTS = 3
        private const val MAX_CLICKS = 24
        private const val MAX_TEMPLATE_HITS = 4
        private const val MAX_ROUNDS = 26
        /** 一次生成最多向用户提几个问题：问太多说明提示词不清楚，收益也在下降。 */
        private const val MAX_ASKS = 6
        /** 中途最多补几次画面/操作观察：再多输入会膨胀，反而拖慢生成。 */
        private const val MAX_OBSERVATIONS = 16

        /** 发给模型的包最多保留多少步，超出部分只给摘要（控制输入体积、稳住缓存前缀）。 */
        private const val COMPACT_STEPS = 60
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
        const val SCREENSHOT_MARKER = "[[NEED_SCREENSHOT]]"
        const val CLICK_MARKER = "[[CLICK:x,y]]"
        const val ASK_MARKER = "[[ASK:问题]]"
        const val TEMPLATE_MARKER = "[[NEED_TEMPLATE:关键词]]"
        private val CLICK_PATTERN = Regex(
            "\\[\\[CLICK:\\s*([0-9]*\\.?[0-9]+)\\s*,\\s*([0-9]*\\.?[0-9]+)\\]\\]"
        )
        private val TEMPLATE_PATTERN = Regex("\\[\\[NEED_TEMPLATE:\\s*([^]\\r\\n]+?)\\s*\\]\\]")
        private val TAP_PATTERN = Regex("\\[\\[TAP:\\s*([^]\\r\\n]+?)\\s*\\]\\]")
        private val NEED_SPEC_PATTERN = Regex("\\[\\[NEED_SPEC:\\s*([^]\\r\\n]+?)\\s*\\]\\]")
        private val ASK_PATTERN = Regex("\\[\\[ASK:\\s*([^\\]]+?)\\s*\\]\\]")

        private val SYSTEM_PROMPT = """
你是 March7thAssistant Android Local 的任务包作者。你的唯一输出是一份完整的 task.json JSON 对象，不要使用 Markdown 代码围栏，不要解释。
格式必须保持 schemaVersion=1，供原版 App 直接导入执行。禁止发明字段、动作或接口。
若对话中另附《任务文件开发规范》全文，以该全文为准（本段只是概要）；全文与本段冲突时一律服从全文。
顶层字段：schemaVersion=1、id、name、author、version、description、minAppVersionCode=11、params、steps。
version 必须为 x.x-y.y：x.x 是任务包版本，y.y 是崩铁游戏版本，中间用短横线连接（例如 1.2-4.7）；确实没有对应游戏版本时可以只写任务包版本（如 1.0.0 或 1.2），不要再补短横线。id 只能含字母、数字、点、横线、下划线，长度 3~64；author 不能为空。
所有坐标和 region 都必须是 0~1 的数，region=[x,y,w,h]。坐标由 App 负责：你可以按画面估算 0~1（也可写像素值），App 会统一归一化、裁剪到合法区间并在附近有文字时吸附到文字中心，不需要等用户提供点位。更稳的写法是 tapText / waitText，把定位交给运行时 OCR。模板只能引用已注册的 assets 文件（可先用 [[NEED_TEMPLATE:关键词]] 索取确切路径），不得臆造图片。
每个步骤可用公共字段 optional、note。点击带文字的按钮时，note 必须写“点击文字「原文」”；无文本图标可写“点击图标”并说明用途。

## 画面上下文（按需索取）
你会先收到当前游戏画面的 OCR 文字，这是默认提供的上下文。多数情况据此即可写出流程。
仅当 OCR 文字确实不足以理解界面（例如需要确认无文字图标、整体布局、按钮相对位置）时，你才可以索取截图：
在回复中只输出一行 $SCREENSHOT_MARKER（可附一句简短原因，不要输出 JSON、不要输出其它内容）。
系统会把当前游戏画面截图作为图片发给你，你再据此输出最终 task.json。最多索取 3 次。
模型未索取截图时系统不会发任何图片，因此不要等待图片；也不要在不需要看画面时索取。
截图用于理解界面与决定点哪里；坐标会由 App 归一化与吸附校正，不会因此产生越界值；但模板只能引用已注册的 assets 路径。
如果系统告知截图不可用，请立即仅依据 OCR 文字输出最终结果，不要重复索取。

## 生成期推进界面（自动点击 · 必须执行）
若本次对话允许自动点击，这是**强制协议**，不是可选项：
1. 先判断你需要的界面是否已经出现在当前 OCR / 截图中。**没有出现，就必须先点击**推进。
2. 首选按文字点：只输出一行 [[TAP:按钮文字]]（文字要与画面显示一致，如 [[TAP:开始挑战]]），
   App 用当前画面的 OCR 定位该文字并真实点击，**坐标完全由 App 提供，你不接触任何坐标**。
3. 画面没有文字可点时用 [[CLICK:x,y]]：x、y 可写 0~1 或像素值，App 会归一化与裁剪，不必精确。
4. 这一行可以带一句很短的原因，但**不要同时输出 task.json**。
5. 系统会真实点击并把新画面的 OCR 文字回给你，你据此继续判断，必要时继续点击。
6. 界面到位后再输出最终 task.json。
每次点击成功后，App 会自动把它录成任务包的一条 tap 步骤，因此不用在最终 JSON 里重复写这一步。
不要因为怕「猜坐标」而跳过点击：坐标不是你的责任，判断「该点哪里」才是。最多 24 次点击。

## 模板库（按需索取）
需要图标模板、整包写法或某个 op 的标准字段时，输出一行 [[NEED_TEMPLATE:关键词]]，
系统会回喂匹配模板的确切 assets/ 路径与字段骨架，你再据此引用。不要臆造 assets 文件名。

## 配置项（可以自己产生）
流程中「跑几次」「选哪个角色」「是否领取额外奖励」这类会变的值，写进顶层 params：
{"key":"times","label":"挑战次数","type":"int","default":3,"min":1,"max":99}。
步骤里用 {times} 引用（只有 text / times 字段这样写）；setVar 的 expr 里直接写变量名 times，不要加花括号。
key 用英文小写+下划线，label 用中文，default 必填；不要重复声明已有配置项。

## 不确定就问用户
缺少关键信息（角色、次数、异常处理方式）时，只输出一行 [[ASK:你的问题]]，不要输出 JSON。
系统会把问题显示给用户，拿到回答后再继续。能自己合理默认的就别问，最多 6 次。

只能使用以下原语：
log(text)；wait(ms，1~60000，尽量少用)；tap(x,y,durationMs?)；longPress(x,y,durationMs?)；swipe(fromX,fromY,toX,toY,durationMs?)；
waitTemplate(image,timeoutMs?,threshold?,region?,stable?)；tapTemplate(image,timeoutMs?,threshold?,region?,offsetX?,offsetY?)；assertTemplate(image,threshold?,region?)；
waitText(text,timeoutMs?,region?)；tapText(text,timeoutMs?,region?)；assertText(text,region?)；readNumber(region,to,timeoutMs?)；readText(region,to)；
ifCount(region,color,value,compare?,tolerance?,minArea?,maxArea?,then,else?)；assertCount(同条件字段)；waitColor(color,region?,ratio?,tolerance?,timeoutMs?)；
swipeUntil(image 或 text,fromX,fromY,toX,toY,maxTries?,intervalMs?,region?)；
repeat(times,steps)；ifTemplate(image,then,else?)；ifText(text,then,else?)；setVar(to,expr)；ifExpr(expr,then,else?)；whileExpr(expr,steps)；stop(ok?)。
timeoutMs 不得超过 120000；嵌套不超过 4 层；repeat 子步骤不能为空。表达式仅支持整数、比较、布尔和 min/max/abs/clamp/len。
**表达式里引用变量直接写名字**：`{"op":"setVar","to":"left","expr":"count - 1"}`，不要写成 `{count} - 1`；
`{key}` 这种花括号只用于 text / times 字段引用 params，写进 expr 会被校验器拒绝（未认识的字符「{」）。
规范里没有写清某个 op 的字段时，用 [[NEED_SPEC:op 名]] 索取原文，不要凭印象编字段。
优先“先 waitText/waitTemplate 等界面出现，再点击”，转场不要只靠固定 wait。流程开始用 assertText/assertTemplate 检查前置界面；不确定是否出现的步骤设 optional=true。
不要输出任何可执行脚本、网络访问、shell、文件读写或包外资源引用。
""".trimIndent()
    }
}
