package com.march7th.hkrpg.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「一键修复 JSON 语法」必须只修语法、不碰字段，而且修完真的能被解析。 */
class TaskPackJsonRepairTest {

    @Test
    fun repairsTrailingCommasAndKeepsValues() {
        val broken = """{"schemaVersion":1,"name":"测试","steps":[{"op":"wait","ms":1000,},],}"""
        val result = TaskPackJsonRepair.repair(broken)
        assertTrue(result.ok)
        assertTrue(result.fixes.any { it.contains("逗号") })
        val text = result.text
        assertFalse(text.contains(",}"))
        assertFalse(text.contains(",]"))
        assertTrue(text.contains("\"ms\":1000"))
    }

    @Test
    fun dropsCommentsAndSingleQuotes() {
        val broken = """
            {
              // 这是注释
              'schemaVersion': 1, /* 行内注释 */
              'name': '每日实训'
            }
        """.trimIndent()
        val result = TaskPackJsonRepair.repair(broken)
        assertTrue(result.ok)
        val compact = result.text.filterNot { it.isWhitespace() }
        assertTrue(compact.contains("\"name\":\"每日实训\""))
        assertTrue(result.fixes.any { it.contains("注释") })
        assertTrue(result.fixes.any { it.contains("单引号") })
    }

    @Test
    fun fixesFullWidthColonAndPythonLiterals() {
        val broken = """{"schemaVersion"：1，"optional"：True,"ok"：None}"""
        val result = TaskPackJsonRepair.repair(broken)
        assertTrue(result.ok)
        assertTrue(result.text.contains("\"optional\":true"))
        assertTrue(result.text.contains("\"ok\":null"))
    }

    @Test
    fun closesTruncatedTail() {
        val broken = """{"schemaVersion":1,"steps":[{"op":"log","text":"开始"}"""
        val result = TaskPackJsonRepair.repair(broken)
        assertTrue(result.ok)
        assertTrue(result.text.trimEnd().endsWith("}]}"))
    }

    @Test
    fun leavesValidJsonAlone() {
        val valid = """{"schemaVersion":1,"steps":[]}"""
        val result = TaskPackJsonRepair.repair(valid)
        assertTrue(result.ok)
        assertEquals(valid, result.text)
        assertTrue(result.fixes.isEmpty())
    }

    @Test
    fun reportsFailureWhenBeyondRepair() {
        // 缺少关键的大括号结构之外还多了非法 token：修不了就明确返回不可用，不能返回半成品。
        val broken = """{"schemaVersion":1,,"steps":[}"""
        val result = TaskPackJsonRepair.repair(broken)
        assertFalse(result.ok)
    }
}
