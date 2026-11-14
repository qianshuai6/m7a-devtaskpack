package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.PackStep
import com.march7th.hkrpg.data.resource.CustomTaskPackValidator
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模板库、24 op 全字段编辑与批量延迟的契约测试。 */
class TaskPackTemplateLibraryTest {

    @Test
    fun stepTemplatesCoverEveryKnownOpExactlyOnce() {
        val templates = TaskPackTemplateLibrary.STEP_TEMPLATES
        val ops = templates.map { it.op }.toSet()
        assertEquals(24, templates.size)
        assertEquals(24, ops.size)
        assertEquals(CustomTaskPackValidator.KNOWN_OPS, ops)
    }

    @Test
    fun stepTemplatesNeverInventCoordinatesOrTemplateImages() {
        TaskPackTemplateLibrary.STEP_TEMPLATES.forEach { template ->
            val step = template.step
            assertNull("${template.op} 不应自带 x", step.x)
            assertNull("${template.op} 不应自带 y", step.y)
            assertNull("${template.op} 不应自带 fromX", step.fromX)
            assertNull("${template.op} 不应自带 fromY", step.fromY)
            assertNull("${template.op} 不应自带 toX", step.toX)
            assertNull("${template.op} 不应自带 toY", step.toY)
            assertNull("${template.op} 不应自带 image", step.image)
            assertNull("${template.op} 不应自带 region", step.region)
        }
    }

    @Test
    fun searchMatchesMultipleKeywordsAndOpNames() {
        assertTrue(TaskPackTemplateLibrary.matches("tapText", "tapText 点击文字 OCR"))
        assertTrue(TaskPackTemplateLibrary.matches("货币战争 ifCount", "货币战争 全流程 ifCount 数星星"))
        assertTrue(TaskPackTemplateLibrary.matches("", "任何内容都应命中"))
        assertFalse(TaskPackTemplateLibrary.matches("完全不存在的关键词", "tapText 点击文字"))
    }

    @Test
    fun delayApplyRecursesIntoBranchesAndRespectsValidatorLimits() {
        val steps = listOf(
            PackStep(op = "wait", ms = 1_000),
            PackStep(op = "tapText", text = "进入", timeoutMs = 5_000),
            PackStep(
                op = "ifText",
                text = "返回",
                then = listOf(
                    PackStep(op = "wait", ms = 2_000),
                    PackStep(op = "swipeUntil", intervalMs = 300),
                ),
            ),
        )
        val (waitUpdated, waitCount) = TaskPackDelay.apply(steps, DelayField.WAIT, 500)
        assertEquals(2, waitCount)
        assertEquals(1_500L, waitUpdated[0].ms)
        assertEquals(2_500L, waitUpdated[2].then!![0].ms)

        val (timeoutUpdated, timeoutCount) = TaskPackDelay.apply(steps, DelayField.TIMEOUT, 1_000)
        assertEquals(1, timeoutCount)
        assertEquals(6_000L, timeoutUpdated[1].timeoutMs)

        val (intervalUpdated, intervalCount) = TaskPackDelay.apply(steps, DelayField.INTERVAL, 100)
        assertEquals(1, intervalCount)
        assertEquals(400L, intervalUpdated[2].then!![1].intervalMs)

        val clampedWait = TaskPackDelay.apply(
            listOf(PackStep(op = "wait", ms = 59_900)), DelayField.WAIT, 5_000,
        )
        assertEquals(60_000L, clampedWait.first[0].ms)
        val clampedTimeout = TaskPackDelay.apply(
            listOf(PackStep(op = "waitText", timeoutMs = 119_000)), DelayField.TIMEOUT, 5_000,
        )
        assertEquals(120_000L, clampedTimeout.first[0].timeoutMs)
    }

    @Test
    fun insertWaitForEveryStepOnlyAddsTopLevelWaits() {
        val steps = listOf(
            PackStep(op = "tap", x = 0.5, y = 0.5),
            PackStep(op = "log", text = "进度"),
        )
        val out = TaskPackDelay.insertWaitForEveryStep(steps, 250)
        assertEquals(4, out.size)
        assertEquals("tap", out[0].op)
        assertEquals("wait", out[1].op)
        assertEquals(250L, out[1].ms)
        assertEquals("log", out[2].op)
        assertEquals("wait", out[3].op)
        assertEquals(2, out.count { it.op == "wait" })
    }

    @Test
    fun allPackStepFieldsRoundTripThroughBranchJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val step = PackStep(
            op = "ifCount",
            optional = true,
            note = "数金色星星",
            region = listOf(0.05, 0.29, 0.13, 0.06),
            color = "#FFC870",
            tolerance = 32,
            minArea = 80,
            maxArea = 4_000,
            compare = ">=",
            value = 3,
            then = listOf(PackStep(op = "stop", ok = true)),
            elseSteps = listOf(PackStep(op = "log", text = "未满星")),
        )
        val text = json.encodeToString(ListSerializer(PackStep.serializer()), listOf(step))
        assertTrue("else 分支必须写成原版的 \"else\" 字段", text.contains("\"else\""))
        assertTrue(text.contains("maxArea"))
        val restored = json.decodeFromString(ListSerializer(PackStep.serializer()), text).single()
        assertEquals(step, restored)
    }

    @Test
    fun bundledRepositoryPacksAllPassOriginalValidator() {
        val root = requireNotNull(findRepoRoot()) { "应能定位仓库根目录以校验内置模板" }
        val dir = File(root, "app/src/main/assets/dev/templates/packs")
        val packs = (dir.listFiles { file -> file.extension == "m7atask" } ?: emptyArray())
            .sortedBy { it.name }
        assertEquals("仓库 site/taskpacks + examples 的全部模板都应内置", 7, packs.size)
        packs.forEach { file ->
            val project = TaskPackArchive.read(file, APP_VERSION_CODE)
            val errors = CustomTaskPackValidator.validate(
                project.pack, APP_VERSION_CODE, project.assets.keys,
            )
            assertTrue("${file.name} 未通过原版校验：$errors", errors.isEmpty())
        }
    }

    private fun findRepoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            if (dir != null &&
                File(dir, "app/src/main/assets/dev/templates/packs").isDirectory
            ) return dir
            dir = dir?.parentFile
        }
        return null
    }

    private companion object {
        const val APP_VERSION_CODE = 22
    }
}
