package com.march7th.hkrpg.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标模板候选筛选。
 *
 * 410 个图标每个都做一次模板匹配要好几秒，所以先用点位上 OCR 到的文字把候选缩到十几个。
 * 这里验证的是「筛选」这一步：名字/关键词命中优先、尺寸不合理的直接排除、顺序稳定。
 */
class TaskPackIconCandidatesTest {

    private fun icon(
        id: String,
        name: String,
        keywords: String = "",
        w: Int = 64,
        h: Int = 64,
    ) = IconTemplateEntry(
        id = id,
        name = name,
        asset = "starrail/images/$id.png",
        category = "ui",
        group = "仓库图标",
        source = "repo",
        keywords = keywords,
        width = w,
        height = h,
    )

    @Test
    fun nameHitRanksFirst() {
        val entries = listOf(
            icon("a", "关闭"),
            icon("b", "开始挑战"),
            icon("c", "设置"),
        )
        val ranked = TaskPackIconCandidates.rank(entries, listOf("开始挑战"))
        assertEquals("b", ranked.first().id)
        assertTrue("没有文字线索的图标不该混进来", ranked.none { it.id == "c" })
    }

    @Test
    fun keywordAndSubstringBothMatch() {
        val entries = listOf(
            icon("a", "confirm", keywords = "确定 确认 ok"),
            icon("b", "挑战图标", keywords = "模拟宇宙 start"),
        )
        assertEquals("a", TaskPackIconCandidates.rank(entries, listOf("确定")).first().id)
        assertEquals("b", TaskPackIconCandidates.rank(entries, listOf("模拟宇宙")).first().id)
    }

    @Test
    fun absurdSizesAreExcluded() {
        val entries = listOf(
            icon("huge", "关闭", w = 800, h = 800),
            icon("tiny", "关闭", w = 6, h = 6),
            icon("ok", "关闭", w = 64, h = 64),
        )
        val ranked = TaskPackIconCandidates.rank(entries, listOf("关闭"))
        assertEquals(listOf("ok"), ranked.map { it.id })
    }

    @Test
    fun noHintsFallsBackToButtonSizedIcons() {
        val entries = listOf(
            icon("big", "banner", w = 200, h = 200),
            icon("btn", "button", w = 56, h = 56),
        )
        val ranked = TaskPackIconCandidates.rank(entries, emptyList())
        assertEquals("btn", ranked.first().id)
    }

    @Test
    fun limitCapsCandidates() {
        val entries = (1..40).map { icon("i$it", "关闭$it") }
        assertEquals(12, TaskPackIconCandidates.rank(entries, listOf("关闭"), limit = 12).size)
    }

    @Test
    fun orderIsStableForSameInput() {
        val entries = (1..10).map { icon("i$it", "按钮", w = 60 + it, h = 60 + it) }
        val first = TaskPackIconCandidates.rank(entries, listOf("按钮")).map { it.id }
        val second = TaskPackIconCandidates.rank(entries.reversed(), listOf("按钮")).map { it.id }
        assertEquals(first, second)
    }

    @Test
    fun normalizeStripsSpacesAndPunctuation() {
        assertEquals("开始挑战", TaskPackIconCandidates.normalize("开始 挑战！"))
        assertEquals("close", TaskPackIconCandidates.normalize("Close."))
    }
}
