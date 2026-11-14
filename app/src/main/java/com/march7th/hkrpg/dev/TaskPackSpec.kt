package com.march7th.hkrpg.dev

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 生成时实际注入给模型的规范正文，以及给界面看的加载说明。 */
data class TaskPackSpecResult(
    val text: String = "",
    val status: String = "",
    val fromBuiltIn: Boolean = false,
)

/**
 * 「让 AI 读规范」的正文提供器。
 *
 * OpenAI 兼容模型无法自己联网打开 URL，所以只把链接写进 prompt 没有意义：
 * 规范必须由 App 读取成**正文**，随对话一起发给模型。
 *
 * - 内置 [ASSET_PATH]（项目 docs/task-file.md 全文），离线可靠，作为默认与失败回退；
 * - 用户可添加多个规范链接，App 生成前拉取正文（GitHub blob 页面自动转 raw 纯文本）；
 * - 线上正文可用时以线上为准；全部失败或链接被清空时自动回退内置全文，保证规范始终存在。
 */
object TaskPackSpec {
    const val DEFAULT_URL =
        "https://github.com/biirekha857-star/March7thAssistantAndroidlocal/blob/main/docs/task-file.md"

    private const val ASSET_PATH = "dev/task-file-spec.md"
    private const val MAX_DOC_CHARS = 100_000
    private const val MAX_TOTAL_CHARS = 160_000
    private const val MAX_BODY_CHARS = 2_000_000

    fun defaultUrls(): List<String> = listOf(DEFAULT_URL)

    fun builtIn(context: Context): String = runCatching {
        context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("")

    /** GitHub 的 blob 页面返回 HTML，转成 raw 才能拿到 Markdown 正文。其它链接原样使用。 */
    fun toRawUrl(url: String): String {
        val value = url.trim()
        if (value.startsWith("https://github.com/") && "/blob/" in value) {
            return "https://raw.githubusercontent.com/" +
                value.removePrefix("https://github.com/").replaceFirst("/blob/", "/")
        }
        return value
    }

    suspend fun load(
        context: Context,
        client: OkHttpClient,
        urls: List<String>,
    ): TaskPackSpecResult = withContext(Dispatchers.IO) {
        val notes = mutableListOf<String>()
        val docs = mutableListOf<String>()
        for (raw in urls) {
            val url = raw.trim()
            if (url.isEmpty()) continue
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                notes += "跳过：$url（只支持 http/https）"
                continue
            }
            fetch(client, toRawUrl(url)).fold(
                onSuccess = { body ->
                    val clipped = body.take(MAX_DOC_CHARS)
                    docs += "===== 规范文档：$url =====\n$clipped"
                    notes += "已读取（${clipped.length} 字）：$url"
                },
                onFailure = { notes += "读取失败：${it.message} → $url" },
            )
        }
        val builtIn = builtIn(context)
        val usedBuiltIn = docs.isEmpty()
        if (usedBuiltIn && builtIn.isNotBlank()) {
            docs += "===== 规范文档：App 内置 docs/task-file.md =====\n" + builtIn.take(MAX_DOC_CHARS)
            notes += if (urls.any { it.isNotBlank() }) {
                "线上规范不可用，已自动回退到 App 内置规范全文"
            } else {
                "使用 App 内置规范全文"
            }
        }
        TaskPackSpecResult(
            text = docs.joinToString("\n\n").take(MAX_TOTAL_CHARS),
            status = notes.joinToString("\n").ifBlank { "未加载规范文档" },
            fromBuiltIn = usedBuiltIn,
        )
    }

    private fun fetch(client: OkHttpClient, url: String): Result<String> = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain, text/markdown, */*")
            .header("User-Agent", "March7thTaskPackDev/1.0")
            .get()
            .build()
        val caller = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        caller.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val text = response.body.string()
            if (text.isBlank()) error("内容为空")
            if (text.length > MAX_BODY_CHARS) error("内容过大")
            val head = text.trimStart()
            if (head.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                head.startsWith("<html", ignoreCase = true)
            ) {
                error("返回网页而不是文档正文（请改用 raw 链接）")
            }
            text
        }
    }
}
