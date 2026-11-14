package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.PackParam
import com.march7th.hkrpg.data.model.PackStep
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开发者工具可以增强编辑体验，但产物必须仍是原版执行器认识的任务包。 */
class TaskPackDeveloperCompatibilityTest {

    @Test
    fun versionJoinsPackAndGameVersionWithDash() {
        // v1.0.15 起版本号改成「任务包版本-游戏版本」，例如 1.2 + 4.7 → 1.2-4.7。
        assertEquals("1.2-4.6", TaskPackNaming.version("1.2", "4.6"))
        assertTrue(TaskPackNaming.isValidVersion("1.2-4.6"))
        // 旧包的四点写法仍要能解析，否则历史任务包一导入就报格式错。
        assertTrue(TaskPackNaming.isValidVersion("1.2.4.6"))
        assertEquals("1.0" to "4.6", TaskPackNaming.splitVersion("1.0-4.6"))
        assertEquals("1.0" to "4.6", TaskPackNaming.splitVersion("1.0.4.6"))
        assertEquals("demo-1.2-4.6.m7atask", TaskPackNaming.archiveName("demo", "1.2-4.6"))
    }

    @Test
    fun versionNeverAppendsExtraDashOrRequiresGameVersion() {
        // 第一栏已经是完整版本，不能再拼一次短横线。
        assertEquals("1.2-4.7", TaskPackNaming.version("1.2-4.7", "4.7"))
        assertEquals("1.2-4.7", TaskPackNaming.version("1.2-4.7", ""))
        // 游戏版本留空时只写任务包版本，不出现尾部短横线。
        assertEquals("1.0.0", TaskPackNaming.version("1.0.0", ""))
        assertEquals("1.0.0", TaskPackNaming.version("1.0.0", " "))
        assertTrue(TaskPackNaming.isValidPackVersion("1.0.0"))
        assertTrue(TaskPackNaming.isValidPackVersion("1.2"))
        assertTrue(TaskPackNaming.isValidPackVersion("1.2-4.7"))
        assertFalse(TaskPackNaming.isValidPackVersion(""))
        assertFalse(TaskPackNaming.isValidPackVersion("v1.2"))
    }

    @Test
    fun formFillPutsNonDashedVersionIntoPackFieldOnly() {
        // 不是 x.x-y.y 的版本（如 1.0.0）只填任务包版本栏，游戏版本留空。
        assertEquals("1.0.0" to "", TaskPackNaming.fillForm("1.0.0"))
        assertEquals("1.2" to "", TaskPackNaming.fillForm("1.2"))
        assertEquals("1.2" to "4.7", TaskPackNaming.fillForm("1.2-4.7"))
        assertEquals("1.2" to "4.7", TaskPackNaming.fillForm("1.2.4.7"))
        // 第一栏里识别到完整版本号才回填两栏，中间态保持原样。
        assertEquals("1.2" to "4.7", TaskPackNaming.interpretWholeVersion("1.2-4.7"))
        assertEquals("1.0.0" to "", TaskPackNaming.interpretWholeVersion("1.0.0"))
        assertEquals(null, TaskPackNaming.interpretWholeVersion("1.2"))
        assertEquals(null, TaskPackNaming.interpretWholeVersion("1."))
    }

    @Test
    fun archiveRoundTripKeepsStandardManifestAndTemplates() {
        val project = sampleProject()
        val zip = project.toZipBytes()

        val restored = TaskPackArchive.read(ByteArrayInputStream(zip), APP_VERSION_CODE)

        assertEquals(1, restored.pack.schemaVersion)
        assertEquals("1.2.4.6", restored.pack.version)
        assertEquals(11, restored.pack.minAppVersionCode)
        assertEquals(1, restored.pack.params.size)
        assertEquals("count", restored.pack.params.first().key)
        assertEquals("tap", restored.pack.steps.first().op)
        assertEquals("点击文字「领取」", restored.pack.steps.first().note)
        assertArrayEquals(TEMPLATE_BYTES, restored.assets["assets/button.png"])
    }

    @Test
    fun generatedManifestHasNoDeveloperOnlyFieldsOrOps() {
        val manifest = TaskPackProject.manifestJson(sampleProject().pack)
        assertTrue(manifest.contains("\"schemaVersion\": 1"))
        assertTrue(manifest.contains("\"version\": \"1.2.4.6\""))
        assertFalse(manifest.contains("aiPrompt"))
        assertFalse(manifest.contains("draftSavedAt"))
        assertFalse(manifest.contains("selectedStepIndex"))
        assertFalse(manifest.contains("recordGesture"))
        assertFalse(manifest.contains("generateWithAi"))
    }

    @Test
    fun importRejectsUnknownOpsAndNonStandardSchema() {
        val bad = sampleProject().pack.copy(
            schemaVersion = 2,
            steps = listOf(PackStep(op = "aiWritePack", text = "not supported")),
        )
        val zip = TaskPackProject(bad).toZipBytes()
        val failure = runCatching { TaskPackArchive.read(ByteArrayInputStream(zip), APP_VERSION_CODE) }
            .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("schemaVersion") == true)
        assertTrue(failure?.message?.contains("未知动作") == true)
    }

    @Test
    fun importRejectsMissingTemplateAndUnsafeZipPath() {
        val missing = sampleProject().pack.copy(
            steps = listOf(PackStep(op = "tapTemplate", image = "assets/missing.png")),
        )
        val missingFailure = runCatching {
            TaskPackArchive.read(ByteArrayInputStream(TaskPackProject(missing).toZipBytes()), APP_VERSION_CODE)
        }.exceptionOrNull()
        assertTrue(missingFailure?.message?.contains("assets/missing.png") == true)

        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("task.json"))
            zip.write(TaskPackProject.manifestJson(sampleProject().pack).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../evil.png"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        val pathFailure = runCatching {
            TaskPackArchive.read(ByteArrayInputStream(bytes.toByteArray()), APP_VERSION_CODE)
        }.exceptionOrNull()
        assertTrue(pathFailure?.message?.contains("非法路径") == true)
    }

    private fun sampleProject(): TaskPackProject {
        val pack = CustomTaskPack(
            schemaVersion = 1,
            id = "author.demo",
            name = "演示任务",
            author = "author",
            version = "1.2.4.6",
            description = "从邮件页领取全部奖励",
            minAppVersionCode = 11,
            params = listOf(
                PackParam(
                    key = "count",
                    label = "次数",
                    type = "int",
                    default = JsonPrimitive(1),
                    min = 1,
                    max = 5,
                ),
            ),
            steps = listOf(
                PackStep(
                    op = "tap",
                    x = 0.5,
                    y = 0.6,
                    durationMs = 90,
                    note = "点击文字「领取」",
                ),
                PackStep(op = "wait", ms = 500, note = "等待结果"),
            ),
        )
        return TaskPackProject(pack, mapOf("assets/button.png" to TEMPLATE_BYTES))
    }

    private companion object {
        const val APP_VERSION_CODE = 16
        val TEMPLATE_BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
