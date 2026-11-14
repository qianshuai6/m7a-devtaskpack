package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.PackStep
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多工程存档：同时写多个任务包时，每个工程独立落盘、可切换、可改名与删除。 */
class TaskPackProjectStoreTest {

    private val appVersionCode = 26

    private fun project(name: String, version: String): TaskPackProject = TaskPackProject(
        pack = CustomTaskPack(
            schemaVersion = 1,
            id = "author.$name",
            name = name,
            author = "author",
            version = version,
            description = "测试工程",
            minAppVersionCode = 11,
            steps = listOf(
                PackStep(op = "tap", x = 0.5, y = 0.5, note = "点击文字「领取」"),
                PackStep(
                    op = "ifText",
                    text = "确认",
                    then = listOf(PackStep(op = "tapText", text = "确认")),
                ),
            ),
        ),
    )

    @Test
    fun saveListLoadRenameDeleteRoundTrip() {
        val root = Files.createTempDirectory("taskpack-store").toFile()
        val store = TaskPackProjectStore(root, appVersionCode)

        val first = store.save("", project("每日实训", "1.2-4.7"))
        val second = store.save("", project("货币战争", "1.3-4.7"))
        // 嵌套步骤（then 里的 tapText）也要计入步数，列表显示的才是真实规模。
        assertEquals(3, first.stepCount)
        assertEquals(2, store.list().size)
        // 后保存的排在前面，最近编辑的工程一眼可见。
        assertEquals(second.fileId, store.list().first().fileId)

        val loaded = store.load(first.fileId)
        assertNotNull(loaded)
        assertEquals("1.2-4.7", loaded!!.pack.version)
        assertEquals(2, loaded.pack.steps.size)

        // 覆盖保存同一个 fileId 不新增条目。
        store.save(first.fileId, project("每日实训改", "1.2-4.7"))
        assertEquals(2, store.list().size)
        assertEquals("每日实训改", store.list().first { it.fileId == first.fileId }.name)

        store.rename(first.fileId, "每日实训 v2")
        assertEquals("每日实训 v2", store.list().first { it.fileId == first.fileId }.displayName)

        store.delete(first.fileId)
        assertEquals(1, store.list().size)
        assertNull(store.load(first.fileId))
    }

    @Test
    fun missingFileIsDroppedFromList() {
        val root = Files.createTempDirectory("taskpack-store-missing").toFile()
        val store = TaskPackProjectStore(root, appVersionCode)
        val saved = store.save("", project("临时", "1.0-4.7"))
        // 模拟用户清理了工程文件：索引里残留的条目要自动过滤，不能让列表点进去报错。
        java.io.File(root, "taskpack-projects/${saved.fileId}.m7atask").delete()
        assertTrue(store.list().isEmpty())
    }
}
