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
)

/** 一次自动点击的结果；observation 会回喂给模型当作「点击后的画面观察」。 */
data class TaskPackAiClickResult(
    val accepted: Boolean,
    val observation: String,
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
        /** 用户提供的坐标清单文本：这些是**可写进任务包**的合法坐标，供模型参考。 */
        knownCoordinates: String = "",
        /** 执行一次真实点击并返回点击后观察；坐标由模型按画面自行决定，只用于推进界面。 */
        clickHandler: (suspend (Double, Double) -> TaskPackAiClickResult)? = null,
        /** 模板库索引摘要，注入 prompt 让模型知道有哪些模板可用。 */
        templateIndex: String = "",
        /** AI 按需索取模板：传入关键词，返回匹配模板详情（并已把图标素材注册进工程）。 */
        templateProvider: (suspend (String) -> String)? = null,
        /** 生成过程状态回调，用于界面展示“AI 索取了画面 / 自动点击 / 索取模板”。 */
        onStatus: (String) -> Unit = {},
    ): TaskPackAiOutcome = withContext(Dispatchers.IO) {
        val endpoint = runCatching { endpoint(config.baseUrl) }.getOrElse {
            return@withContext TaskPackAiOutcome(error = it.message)
        }
        if (config.model.isBlank()) {
            return@withContext TaskPackAiOutcome(error = "请填写模型名")
        }

        val messages = mutableListOf<JsonObject>()
        messages += message("system", SYSTEM_PROMPT)
        // 规范全文作为独立 system 消息注入：模型自己打不开 URL，必须由 App 把正文送进去。
        // 与 SYSTEM_PROMPT 概要冲突时以这份全文为准；生成与每轮回喂修正都会持續带着它。
        if (specText.isNotBlank()) {
            messages += message("system", buildSpecPrompt(specText, specSourceNote))
        }
        val templateAllowed = templateProvider != null && templateIndex.isNotBlank()
        if (templateAllowed) {
            messages += message("system", buildTemplateIndexPrompt(templateIndex))
        }
        messages += message(
            "user",
            buildUserPrompt(
                instruction, basePack, availableAssets(), screenText,
                allowAutoClick && clickHandler != null, knownCoordinates,
                screenshotProvider != null && allowScreenshot, templateAllowed,
            ),
        )

        var screenAllowed = allowScreenshot && screenshotProvider != null
        val clickAllowed = allowAutoClick && clickHandler != null
        var screenshots = 0
        var clicks = 0
        var templateHits = 0
        var repairs = 0
        var rounds = 0
        var lastWasImage = false
        var clickNudged = false
        val repairLog = mutableListOf<String>()

        while (rounds < MAX_ROUNDS) {
            // 打断后立刻收尾：activeCall 被 cancel 会抛 IOException，这里转成「已打断」而不是失败。
            if (!currentCoroutineContext().isActive) {
                return@withContext TaskPackAiOutcome(
                    attempts = rounds, screenshotsUsed = screenshots, clicksUsed = clicks,
                    templateHits = templateHits, interrupted = true,
                )
            }
            rounds++
            val raw = try {
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

            // AI 边生成边自动点击：模型输出 [[CLICK:x,y]]，App 真实点一下并回喂点击后画面文字。
            // 坐标可由模型依据 OCR/截图自行判断（仅用于推进界面），放宽长度限制避免解释文字导致漏判。
            val clickMatch = CLICK_PATTERN.find(raw)
            // 不再要求整段不含 `{`：模型常在标记前加一句说明。只要没同时给出完整
            // task.json（含 schemaVersion / steps）就按点击请求处理。
            val looksLikePack = raw.contains("\"schemaVersion\"") || raw.contains("\"steps\"")
            if (clickMatch != null && !looksLikePack && raw.length <= 1500) {
                if (!clickAllowed || clicks >= MAX_CLICKS) {
                    messages += message("assistant", raw)
                    messages += message(
                        "user",
                        "当前不能再自动点击游戏画面（未开启或已达上限）。" +
                            "请直接输出最终完整 task.json，不要再次请求点击。",
                    )
                    continue
                }
                clicks++
                val nx = clickMatch.groupValues[1].toDoubleOrNull()
                val ny = clickMatch.groupValues[2].toDoubleOrNull()
                val result = if (nx == null || ny == null) {
                    null
                } else {
                    try {
                        clickHandler.invoke(nx, ny)
                    } catch (e: Exception) {
                        null
                    }
                }
                messages += message("assistant", raw)
                messages += message("user", buildString {
                    appendLine(if (result?.accepted == true) "已按你的请求执行点击。" else "这次点击请求未被执行。")
                    appendLine(result?.observation ?: "坐标无效或点击执行失败。")
                    appendLine(
                        "请据此继续：可再次请求点击推进界面，或直接输出最终完整 task.json。" +
                            "注意生成期点击的坐标只是探索用，最终 task.json 里的 x/y/region 只能来自" +
                            "「用户提供的坐标」清单或用户录制，没把握时改用 tapText / waitText。",
                    )
                })
                onStatus(
                    if (result?.accepted == true) {
                        "AI 自动点击第 $clicks 次，已回传点击后画面文字…"
                    } else {
                        "AI 的自动点击请求未执行（服务未连接或坐标无效）"
                    }
                )
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
                            "请据此理解界面布局并输出最终完整 task.json。" +
                            "注意：坐标与模板仍必须来自用户录制或明确提供，不要凭截图臆造坐标，也不要臆造 assets 里的图片。",
                        base64,
                    )
                    lastWasImage = true
                    onStatus("AI 索取了第 $screenshots 张画面，已发送截图，等待继续生成…")
                }
                continue
            }

            var candidateText = runCatching { extractJsonObject(raw) }.getOrNull()
            var candidate = candidateText?.let {
                runCatching {
                    parser.decodeFromString(CustomTaskPack.serializer(), it)
                }.getOrNull()
            }
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
                            appendLine("你还没有实际推进过界面。")
                            appendLine("如果后面的步骤涉及当前 OCR / 截图里还没出现的界面（弹窗、二级菜单、翻页、确认键），")
                            appendLine("请先输出一行 [[CLICK:x,y]] 让系统真实点击，再根据回传的新画面文字继续写。")
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
                )
            }

            val feedback = errors.joinToString("\n")
            repairLog += feedback
            repairs++
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
            error = "多轮后仍未通过校验：\n${repairLog.lastOrNull().orEmpty()}",
        )
    }

    private fun request(
        endpoint: String,
        config: TaskPackAiConfig,
        messages: List<JsonObject>,
    ): String {
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
            return extractContent(messageObject["content"])
                .ifBlank { error("AI 返回了空内容") }
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
    private fun buildSpecPrompt(specText: String, sourceNote: String): String = buildString {
        appendLine("以下是《任务文件开发规范》全文，是本次写作的强制依据，必须逐条遵守。")
        appendLine("它优先于前面 system 里的概要：字段、动作白名单、取值区间、约束、嵌套与表达式规则，一律以这份全文为准。")
        appendLine("特别注意：只使用全文里真实存在的 op 与字段，不得发明；坐标/模板仍只能来自用户提供。")
        if (sourceNote.isNotBlank()) {
            appendLine("规范来源：${sourceNote.replace('\n', ' ').take(300)}")
        }
        appendLine()
        appendLine("===== 规范全文开始 =====")
        appendLine(specText)
        appendLine("===== 规范全文结束 =====")
        appendLine()
        appendLine("请据此直接输出最终完整 task.json，不要复述规范、不要解释。")
    }

    /** 模板库索引作为独立 system 消息注入，并说明按需索取方式。 */
    private fun buildTemplateIndexPrompt(index: String): String = buildString {
        appendLine("## 任务包模板库（可按需索取）")
        appendLine("下面是从仓库内置模板库生成的索引摘要。需要某个图标、整包写法或步骤骨架时，")
        appendLine("只输出一行 [[NEED_TEMPLATE:关键词]]，系统会检索并把确切路径与字段写法回给你；最多 $MAX_TEMPLATE_HITS 次。")
        appendLine("不要凭空编造 assets/ 路径，也不要臆造不存在的图标；索取后再引用。")
        appendLine()
        appendLine(index.take(12_000))
    }

    private fun buildUserPrompt(
        instruction: String,
        basePack: CustomTaskPack,
        assets: Set<String>,
        screenText: String,
        allowAutoClick: Boolean,
        knownCoordinates: String,
        allowScreenshot: Boolean,
        allowTemplateLookup: Boolean,
    ): String = buildString {
        appendLine("写作要求：")
        appendLine(instruction.trim())
        appendLine("\n当前任务包（保留其中有效的元数据、录制步骤和已有素材；没有步骤时可全新生成）：")
        appendLine(TaskPackProject.manifestJson(basePack))
        appendLine("\n当前包内可用模板（除此之外不得引用或臆造模板）：")
        appendLine(if (assets.isEmpty()) "（无）" else assets.sorted().joinToString("\n"))
        appendLine("\n当前游戏画面 OCR 文字（默认上下文，仅供理解界面，不得据此猜坐标或模板）：")
        appendLine(screenText.ifBlank { "（未识别到画面文字）" })
        if (allowScreenshot) {
            appendLine(
                "\n如果上面的文字不足以判断界面，且你需要看真实画面，" +
                    "请只输出一行 $SCREENSHOT_MARKER（可附一句原因），系统会立即附上当前游戏画面截图；" +
                    "否则直接输出最终 task.json，不要索取画面。",
            )
        }
        if (allowTemplateLookup) {
            appendLine(
                "\n如果你需要模板图、某个 op 的标准写法或现成整包流程，" +
                    "请只输出一行 $TEMPLATE_MARKER（可附一句原因），系统会回喂匹配模板的确切 assets 路径与字段；" +
                    "否则直接输出最终 task.json。",
            )
        }
        if (allowAutoClick) {
            appendLine("\n## 本次允许在生成过程中自动点击游戏画面（必须执行）")
            appendLine("你正在写一个需要推进多层界面的流程，只凭初始画面写后面的界面一定会写错。")
            appendLine("当流程需要进入下一层、打开菜单、翻页或确认弹窗，而该界面还没出现在已给你的 OCR / 截图里时，")
            appendLine("**必须先点击**：只输出一行 [[CLICK:0.52,0.73]]（x、y 为 0~1 归一化坐标，")
            appendLine("依据当前 OCR 文字或截图自行判断按钮位置），系统会真实点击并把新画面的 OCR 文字回给你。")
            appendLine("最多 $MAX_CLICKS 次；请求点击时不要同时输出 JSON。")
            appendLine("「包里坐标必须来自用户」只约束最终 task.json，探索点击按画面估算不受此限制。")
            appendLine("这些点击只用于探索推进，**不会自动写进任务包**。最终 task.json 里的 x/y/region")
            appendLine("只能来自下面「用户提供的坐标」清单或用户录制；清单里没有的坐标，请改用 tapText / waitText / tapTemplate。")
            appendLine("\n用户提供的坐标（可以写进任务包）：")
            appendLine(knownCoordinates)
        } else {
            appendLine(
                "\n本次未开启自动点击，请不要输出 [[CLICK:x,y]]；" +
                    "需要新坐标时改用 tapText/waitText，或在输出里说明需要用户取点。",
            )
        }
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
        private const val MAX_CLICKS = 8
        private const val MAX_TEMPLATE_HITS = 4
        private const val MAX_ROUNDS = 20
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
        const val SCREENSHOT_MARKER = "[[NEED_SCREENSHOT]]"
        const val CLICK_MARKER = "[[CLICK:x,y]]"
        const val TEMPLATE_MARKER = "[[NEED_TEMPLATE:关键词]]"
        private val CLICK_PATTERN = Regex(
            "\\[\\[CLICK:\\s*([0-9]*\\.?[0-9]+)\\s*,\\s*([0-9]*\\.?[0-9]+)\\]\\]"
        )
        private val TEMPLATE_PATTERN = Regex("\\[\\[NEED_TEMPLATE:\\s*([^]\\r\\n]+?)\\s*\\]\\]")

        private val SYSTEM_PROMPT = """
你是 March7thAssistant Android Local 的任务包作者。你的唯一输出是一份完整的 task.json JSON 对象，不要使用 Markdown 代码围栏，不要解释。
格式必须保持 schemaVersion=1，供原版 App 直接导入执行。禁止发明字段、动作或接口。
若对话中另附《任务文件开发规范》全文，以该全文为准（本段只是概要）；全文与本段冲突时一律服从全文。
顶层字段：schemaVersion=1、id、name、author、version、description、minAppVersionCode=11、params、steps。
version 必须为 x.x-y.y：x.x 是任务包版本，y.y 是崩铁游戏版本，中间用短横线连接（例如 1.2-4.7）；确实没有对应游戏版本时可以只写任务包版本（如 1.0.0 或 1.2），不要再补短横线。id 只能含字母、数字、点、横线、下划线，长度 3~64；author 不能为空。
所有坐标和 region 都必须是 0~1 的数，region=[x,y,w,h]。**写进最终 task.json 的** x/y/region 只能使用用户录制或明确提供的坐标；不知道坐标时优先用 tapText/waitText，不要猜坐标（注意：这条限制只针对最终 JSON，不针对下面的探索点击）。模板只能引用用户给出的 assets/ 文件，不得臆造图片。
每个步骤可用公共字段 optional、note。点击带文字的按钮时，note 必须写“点击文字「原文」”；无文本图标可写“点击图标”并说明用途。

## 画面上下文（按需索取）
你会先收到当前游戏画面的 OCR 文字，这是默认提供的上下文。多数情况据此即可写出流程。
仅当 OCR 文字确实不足以理解界面（例如需要确认无文字图标、整体布局、按钮相对位置）时，你才可以索取截图：
在回复中只输出一行 $SCREENSHOT_MARKER（可附一句简短原因，不要输出 JSON、不要输出其它内容）。
系统会把当前游戏画面截图作为图片发给你，你再据此输出最终 task.json。最多索取 3 次。
模型未索取截图时系统不会发任何图片，因此不要等待图片；也不要在不需要看画面时索取。
截图只帮助你理解界面，绝不能据此臆造写进 task.json 的坐标、region 或模板 —— 包内坐标与图标必须来自用户录制或明确提供。
如果系统告知截图不可用，请立即仅依据 OCR 文字输出最终结果，不要重复索取。

## 生成期推进界面（自动点击 · 必须执行）
若本次对话允许自动点击，这是**强制协议**，不是可选项：
1. 先判断你需要的界面是否已经出现在当前 OCR / 截图中。**没有出现，就必须先点击**推进。
2. 点击请求只输出一行 [[CLICK:x,y]]（x、y 为 0~1 归一化坐标，按 OCR 文字或截图估算按钮位置），
   这一行可以带一句很短的原因，但**不要同时输出 task.json**。
3. 系统会真实点击游戏并回传新画面的 OCR 文字，你据此继续判断，必要时继续点击。
4. 界面到位后再输出最终 task.json。
「坐标必须来自用户」这条规则**只约束最终 task.json 里的 x/y/region**；探索点击按画面估算完全允许，二者不冲突。
不要因为怕「猜坐标」而跳过点击直接写出没见过的界面。最多 8 次点击。

## 模板库（按需索取）
需要图标模板、整包写法或某个 op 的标准字段时，输出一行 [[NEED_TEMPLATE:关键词]]，
系统会回喂匹配模板的确切 assets/ 路径与字段骨架，你再据此引用。不要臆造 assets 文件名。

只能使用以下原语：
log(text)；wait(ms，1~60000，尽量少用)；tap(x,y,durationMs?)；longPress(x,y,durationMs?)；swipe(fromX,fromY,toX,toY,durationMs?)；
waitTemplate(image,timeoutMs?,threshold?,region?,stable?)；tapTemplate(image,timeoutMs?,threshold?,region?,offsetX?,offsetY?)；assertTemplate(image,threshold?,region?)；
waitText(text,timeoutMs?,region?)；tapText(text,timeoutMs?,region?)；assertText(text,region?)；readNumber(region,to,timeoutMs?)；readText(region,to)；
ifCount(region,color,value,compare?,tolerance?,minArea?,maxArea?,then,else?)；assertCount(同条件字段)；waitColor(color,region?,ratio?,tolerance?,timeoutMs?)；
swipeUntil(image 或 text,fromX,fromY,toX,toY,maxTries?,intervalMs?,region?)；
repeat(times,steps)；ifTemplate(image,then,else?)；ifText(text,then,else?)；setVar(to,expr)；ifExpr(expr,then,else?)；whileExpr(expr,steps)；stop(ok?)。
timeoutMs 不得超过 120000；嵌套不超过 4 层；repeat 子步骤不能为空。表达式仅支持整数、比较、布尔和 min/max/abs/clamp/len。
优先“先 waitText/waitTemplate 等界面出现，再点击”，转场不要只靠固定 wait。流程开始用 assertText/assertTemplate 检查前置界面；不确定是否出现的步骤设 optional=true。
不要输出任何可执行脚本、网络访问、shell、文件读写或包外资源引用。
""".trimIndent()
    }
}
