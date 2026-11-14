package com.march7th.hkrpg.dev

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图标模板库索引、搜索与 AI 按需索取的契约测试。 */
class TaskPackIconTemplatesTest {

    private val parser = Json { ignoreUnknownKeys = true }

    private fun iconEntries(): List<IconTemplateEntry> {
        val root = requireNotNull(findRepoRoot()) { "应能定位仓库根目录以校验内置图标索引" }
        val index = File(root, "app/src/main/assets/dev/templates/icons.json")
        assertTrue("图标索引必须存在", index.isFile)
        val obj = parser.parseToJsonElement(index.readText()).jsonObject
        val arr = obj["icons"] as? JsonArray ?: error("icons 数组缺失")
        fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        return arr.map { element ->
            val e = element as JsonObject
            IconTemplateEntry(
                id = e.str("id"),
                name = e.str("name"),
                asset = e.str("asset"),
                category = e.str("category"),
                group = e.str("group"),
                source = e.str("source"),
                keywords = e.str("keywords"),
                width = e["w"]?.jsonPrimitive?.intOrNull ?: 0,
                height = e["h"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    @Test
    fun indexCoversAllRepositoryIconsAndPackTemplateImages() {
        val entries = iconEntries()
        val repo = entries.count { it.source == "repo" }
        val pack = entries.count { it.source == "pack" }
        assertEquals("仓库 starrail/images 下全部 410 个 PNG 都应入索引", 410, repo)
        assertEquals("7 个整包模板里的 26 张模板图都应入索引", 26, pack)
        assertEquals(436, entries.size)
        assertEquals("id 必须唯一，否则 packPath 会冲突", entries.size, entries.map { it.id }.toSet().size)
    }

    @Test
    fun everyIndexedAssetExistsAndHasLegalPackPath() {
        val root = requireNotNull(findRepoRoot())
        iconEntries().forEach { entry ->
            val file = File(root, "app/src/main/assets/${entry.asset}")
            assertTrue("索引指向的图标文件不存在：${entry.asset}", file.isFile)
            assertTrue("图标必须是 PNG：${entry.asset}", entry.asset.endsWith(".png", ignoreCase = true))
            val path = entry.packPath
            assertTrue("写入包内的路径必须以 assets/ 开头：$path", path.startsWith("assets/"))
            assertFalse("路径不能含 ..：$path", path.contains(".."))
            assertTrue("路径必须是 .png：$path", path.endsWith(".png"))
            assertTrue("尺寸必须有效：${entry.asset}", entry.width > 0 && entry.height > 0)
        }
        assertEquals(
            "packPath 也必须唯一",
            iconEntries().size,
            iconEntries().map { it.packPath }.toSet().size,
        )
    }

    @Test
    fun chineseAndEnglishKeywordsBothHitSearch() {
        val entries = iconEntries()
        assertTrue("中文场景词应能命中", entries.any { TaskPackIconTemplates.matches("关闭", it) })
        assertTrue("英文文件名应能命中", entries.any { TaskPackIconTemplates.matches("close_x", it) })
        assertTrue("领取类图标应能命中", entries.any { TaskPackIconTemplates.matches("领取", it) })
        assertFalse("不存在的关键词不应命中", entries.any {
            TaskPackIconTemplates.matches("绝不可能存在的zzz关键词", it)
        })
    }

    @Test
    fun lookupRespectsLimitAndFallsBackToAnyToken() {
        val entries = iconEntries()
        val hits = TaskPackIconTemplates.lookup(entries, "领取", limit = 3)
        assertTrue("常见关键词应有命中", hits.isNotEmpty())
        assertTrue("必须遵守 limit", hits.size <= 3)
        assertTrue("空关键词不应返回任何结果", TaskPackIconTemplates.lookup(entries, "").isEmpty())
        val wide = TaskPackIconTemplates.lookup(entries, "不存在的词 close", limit = 8)
        assertTrue("任一 token 命中即可回退", wide.isNotEmpty())
    }

    @Test
    fun aiIndexIsCompactButMentionsBothGroups() {
        val index = TaskPackIconTemplates.aiIndex(iconEntries())
        assertTrue(index.contains("仓库图标"))
        assertTrue(index.contains("整包模板图"))
        assertTrue(index.contains("436"))
        assertTrue("索引摘要不能把全部路径塞进 prompt", index.length < 12_000)
    }

    @Test
    fun extractedPackIconsAreValidPngBytes() {
        val root = requireNotNull(findRepoRoot())
        val dir = File(root, "app/src/main/assets/dev/templates/icons")
        val files = dir.listFiles { f -> f.extension == "png" } ?: emptyArray()
        assertEquals("解压出的整包模板图应为 26 张", 26, files.size)
        files.forEach { file ->
            val head = file.readBytes().take(8).toByteArray()
            assertEquals(
                "${file.name} 必须是合法 PNG",
                listOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10),
                head.toList(),
            )
        }
    }

    private fun findRepoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            if (dir != null &&
                File(dir, "app/src/main/assets/dev/templates/icons.json").isFile
            ) return dir
            dir = dir?.parentFile
        }
        return null
    }
}
