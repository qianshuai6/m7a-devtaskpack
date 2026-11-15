package com.march7th.hkrpg.dev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存策略的回归测试。
 *
 * 前缀缓存要生效，有两个硬条件：同一份规范必须得到**逐字节相同**的摘录，
 * 且摘录里不能出现时间、项目名之类会变的内容。这里把这两点固定住。
 */
class TaskPackAiCacheTest {

    private val spec = """
        # 任务文件开发规范

        这里是无关的散文段落，讲一些背景，不该被摘录留下。

        ## 顶层字段
        | 字段 | 必填 | 说明 |
        | --- | --- | --- |
        | schemaVersion | 是 | 固定为 1 |
        | steps | 是 | 步骤数组 |

        ## 动作
        ```json
        {"op":"tapText","text":"开始挑战","timeoutMs":10000}
        ```
        tapText 的 text 必填，timeoutMs 取值 1000~120000。

        ## 约束
        嵌套不超过 4 层。表达式里引用变量直接写名字。
    """.trimIndent()

    @Test
    fun digestIsStableForSameInput() {
        val a = TaskPackSpecDigest.digest(spec)
        val b = TaskPackSpecDigest.digest(spec)
        // 缓存按前缀逐 token 匹配，任何一次抖动都会让整段前缀作废。
        assertEquals(a, b)
        assertTrue(a.contains("tapText"))
        assertTrue(a.contains("嵌套不超过"))
    }

    @Test
    fun digestKeepsFieldTableAndDropsProse() {
        // 传一个小于全文长度的上限，才会真的走摘录分支（超过上限时原文本身就是最完整的）。
        val digest = TaskPackSpecDigest.digest(spec, limit = 200)
        assertTrue(digest.contains("schemaVersion"))
        assertTrue(digest.contains("| steps |"))
        // 纯背景叙述没有字段价值，摘录里应当被去掉，给真正有用的规则留空间。
        assertTrue(!digest.contains("讲一些背景"))
    }

    @Test
    fun digestRespectsLimitWhenSpecIsHuge() {
        val huge = (1..2_000).joinToString("\n") { "## 小节 $it\nwaitText 的 text 必填，timeoutMs 不超过 120000。" }
        val digest = TaskPackSpecDigest.digest(huge, limit = 2_000)
        assertTrue(digest.length <= 2_400)
        assertTrue(digest.contains("[[NEED_SPEC:关键词]]"))
    }

    @Test
    fun searchReturnsContextAroundKeyword() {
        val block = TaskPackSpecDigest.search(spec, "tapText")
        assertTrue(block.contains("tapText"))
        assertTrue(block.contains("\"op\":\"tapText\""))
        assertEquals("", TaskPackSpecDigest.search(spec, "完全不存在的关键词"))
    }

    @Test
    fun parsesDeepSeekCacheFields() {
        val raw = """
            {"choices":[{"message":{"content":"{}"}}],
             "usage":{"prompt_tokens":1000,"completion_tokens":20,
                      "prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200}}
        """.trimIndent()
        val root = Json.parseToJsonElement(raw) as JsonObject
        val usage = TaskPackAiUsageParser.parse(root)
        assertEquals(1000, usage.promptTokens)
        assertEquals(800, usage.cacheHitTokens)
        assertEquals(200, usage.cacheMissTokens)
        assertEquals(0.8, usage.hitRate ?: 0.0, 0.0001)
    }

    @Test
    fun parsesOpenAiCachedTokensFallback() {
        val raw = """
            {"usage":{"prompt_tokens":500,"completion_tokens":10,
                     "prompt_tokens_details":{"cached_tokens":400}}}
        """.trimIndent()
        val root = Json.parseToJsonElement(raw) as JsonObject
        val usage = TaskPackAiUsageParser.parse(root)
        assertEquals(400, usage.cacheHitTokens)
        assertEquals(100, usage.cacheMissTokens)
    }

    @Test
    fun missingUsageReportsUnknownInsteadOfZeroPercent() {
        val usage = TaskPackAiUsageParser.parse(JsonObject(emptyMap()))
        assertNull(usage.hitRate)
        assertTrue(usage.summary().contains("未上报"))
        assertNotNull(usage.plus(TaskPackAiUsage(cacheHitTokens = 10)))
    }
}
