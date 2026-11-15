package com.march7th.hkrpg.dev

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一次生成请求的 token 用量，用于在界面上显示「缓存命中率」。
 *
 * OpenAI 兼容服务（DeepSeek、Moonshot 等）在前缀完全一致时会复用缓存，
 * 并在 `usage.prompt_cache_hit_tokens` 里报出命中的输入 token。命中率高说明
 * 每轮重发的规范/模板索引被服务端复用了，实际计费与延迟都会明显下降。
 */
data class TaskPackAiUsage(
    val promptTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val completionTokens: Int = 0,
) {
    fun plus(other: TaskPackAiUsage): TaskPackAiUsage = TaskPackAiUsage(
        promptTokens = promptTokens + other.promptTokens,
        cacheHitTokens = cacheHitTokens + other.cacheHitTokens,
        cacheMissTokens = cacheMissTokens + other.cacheMissTokens,
        completionTokens = completionTokens + other.completionTokens,
    )

    /** 命中率（0~1）；服务端没返回缓存字段时为 null，界面显示「服务端未上报」。 */
    val hitRate: Double?
        get() {
            val total = cacheHitTokens + cacheMissTokens
            if (total <= 0) return null
            return cacheHitTokens.toDouble() / total
        }

    fun summary(): String {
        val rate = hitRate ?: return "输入 $promptTokens tokens（服务端未上报缓存命中）"
        return "输入 $promptTokens tokens，缓存命中 ${(rate * 100).toInt()}%（" +
            "命中 $cacheHitTokens / 未命中 $cacheMissTokens），输出 $completionTokens"
    }
}

/**
 * 把 prompt 里可能变化的用量字段解析出来。
 *
 * 兼容三种写法：顶层 `usage`、`usage.prompt_tokens_details.cached_tokens`（OpenAI）、
 * `usage.prompt_cache_hit_tokens`（DeepSeek）。
 */
object TaskPackAiUsageParser {
    fun parse(root: JsonObject?): TaskPackAiUsage {
        val usage = root?.get("usage") as? JsonObject ?: return TaskPackAiUsage()
        fun int(vararg keys: String): Int {
            for (key in keys) {
                val v = (usage[key] as? JsonElement)?.jsonPrimitive?.contentOrNull ?: continue
                v.toIntOrNull()?.let { return it }
            }
            return 0
        }
        val prompt = int("prompt_tokens", "input_tokens")
        val completion = int("completion_tokens", "output_tokens")
        val hit = int("prompt_cache_hit_tokens", "cache_hit_tokens")
        val miss = int("prompt_cache_miss_tokens", "cache_miss_tokens")
        val cached = int("cached_tokens")
        val details = usage["prompt_tokens_details"] as? JsonObject
        val detailsCached = details?.get("cached_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val finalHit = listOf(hit, cached, detailsCached).maxOrNull() ?: 0
        val finalMiss = when {
            miss > 0 -> miss
            prompt > 0 && finalHit in 1..prompt -> prompt - finalHit
            else -> 0
        }
        return TaskPackAiUsage(
            promptTokens = prompt,
            cacheHitTokens = finalHit,
            cacheMissTokens = finalMiss,
            completionTokens = completion,
        )
    }

    /** 取 choices[0].message.content 之外，仍需要原始根对象来读 usage。 */
    fun parseRoot(raw: String, parser: kotlinx.serialization.json.Json): JsonObject? =
        runCatching { parser.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

/**
 * 规范全文的**稳定摘录**。
 *
 * 规范正文 33 KB（约 15K tokens），每轮都整份重发是缓存未命中的最大来源：
 * 一旦前缀里有一个字符不同，后面全部作废。这里在本地抽取出「标题 / 代码块 / 表格 /
 * 含 op 名与字段名的行」，输出只依赖输入文本本身，同一份规范永远得到同一份摘录，
 * 因此可以稳定命中服务端前缀缓存；缺失的细节让模型用 [[NEED_SPEC:关键词]] 现取。
 */
object TaskPackSpecDigest {
    /** 默认摘录上限：约 4K tokens，既覆盖 op 表与字段约束，又不会撑爆每轮输入。 */
    const val DEFAULT_LIMIT = 9_000

    /** 出现这些词的行基本就是「字段 / op / 约束」的定义，必须留下。 */
    private val KEY_HINTS = listOf(
        "schemaVersion", "steps", "params", "optional", "note",
        "waitTemplate", "tapTemplate", "assertTemplate", "waitText", "tapText", "assertText",
        "readNumber", "readText", "ifCount", "assertCount", "waitColor", "swipeUntil",
        "repeat", "ifTemplate", "ifText", "setVar", "ifExpr", "whileExpr", "stop",
        "region", "threshold", "stable", "timeoutMs", "tolerance", "minArea", "maxArea",
        "compare", "ratio", "durationMs", "maxTries", "intervalMs", "expr", "to",
        "必填", "可选", "取值", "范围", "约束", "禁止", "不得", "最多", "不超过",
    )

    fun digest(text: String, limit: Int = DEFAULT_LIMIT): String {
        if (text.length <= limit) return text.trim()
        val out = StringBuilder()
        val seen = HashSet<String>()
        var inFence = false
        var kept = 0
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val trimmed = line.trimStart()
            val keep = when {
                trimmed.startsWith("```") -> {
                    inFence = !inFence
                    true
                }
                inFence -> true
                trimmed.startsWith("#") -> true
                trimmed.startsWith("|") -> true
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> true
                KEY_HINTS.any { line.contains(it) } -> true
                else -> false
            }
            if (!keep) continue
            // 重复的表格分隔行 / 空标题没有信息量，去掉能让同样长度装更多内容。
            if (!seen.add(line)) continue
            if (out.length + line.length + 1 > limit) {
                out.appendLine("…（规范摘录已截断，需要细节请输出 [[NEED_SPEC:关键词]] 索取原文段落）")
                break
            }
            out.appendLine(line)
            kept++
        }
        // 极端情况下（例如全文都是没有标题的散文）兜底给前 limit 个字符，别返回空。
        if (kept == 0) return text.take(limit)
        return out.toString().trim()
    }

    /**
     * 按关键词从规范原文里取相关段落，回喂给模型。
     *
     * 命中位置取「上下文各 10 行」并合并相邻区间，保证不会只给半句话；
     * 多个关键词用空格 / 逗号分隔，任一命中即算。
     */
    fun search(text: String, query: String, limit: Int = 3_500): String {
        val terms = query.split(Regex("[\\s,，、/]+"))
            .map { it.trim() }
            .filter { it.length >= 1 }
            .ifEmpty { listOf(query.trim()) }
        if (terms.all { it.isBlank() }) return ""
        val lines = text.lines()
        val hits = lines.indices.filter { i ->
            val line = lines[i]
            terms.any { line.contains(it, ignoreCase = true) }
        }
        if (hits.isEmpty()) return ""
        val ranges = mutableListOf<IntRange>()
        for (i in hits) {
            val r = (i - 10).coerceAtLeast(0)..(i + 10).coerceAtMost(lines.lastIndex)
            val last = ranges.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                ranges[ranges.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                ranges += r
            }
        }
        val sb = StringBuilder()
        for (r in ranges) {
            val block = lines.subList(r.first, r.last + 1).joinToString("\n").trim()
            if (block.isBlank()) continue
            if (sb.length + block.length + 40 > limit) break
            sb.appendLine("----- 规范原文（第 ${r.first + 1}~${r.last + 1} 行）-----")
            sb.appendLine(block)
            sb.appendLine()
        }
        return sb.toString().trim()
    }
}
