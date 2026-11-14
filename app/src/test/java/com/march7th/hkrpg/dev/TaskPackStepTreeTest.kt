package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.PackStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 步骤树（多层 IF 编辑）、大包扁平化、变量索引与 region 格式化的契约测试。 */
class TaskPackStepTreeTest {

    /** 构造一棵三层嵌套：ifExpr → then(ifText → then(setVar))。 */
    private fun nested(): List<PackStep> {
        val leaf = PackStep(op = "setVar", to = "clean", expr = "0")
        val inner = PackStep(op = "ifText", text = "出战", then = listOf(leaf))
        val outer = PackStep(
            op = "ifExpr", expr = "resume == 0",
            then = listOf(inner),
            elseSteps = listOf(PackStep(op = "log", text = "跳过")),
        )
        return listOf(PackStep(op = "log", text = "开始"), outer, PackStep(op = "wait", ms = 500))
    }

    @Test
    fun stepAtLocatesArbitraryDepth() {
        val steps = nested()
        val p = StepPath.root(1)
            .child(0, StepBranch.THEN)          // inner ifText
            .child(0, StepBranch.THEN)          // leaf setVar
        assertEquals("setVar", TaskPackStepTree.stepAt(steps, p)?.op)
        assertEquals("clean", TaskPackStepTree.stepAt(steps, p)?.to)
        assertEquals(
            "跳过",
            TaskPackStepTree.stepAt(steps, StepPath.root(1).child(0, StepBranch.ELSE))?.text,
        )
        assertNull(TaskPackStepTree.stepAt(steps, StepPath.root(99)))
    }

    @Test
    fun replaceRemoveInsertAndMoveAcrossNestedPaths() {
        val steps = nested()
        val leaf = StepPath.root(1).child(0, StepBranch.THEN).child(0, StepBranch.THEN)

        val replaced = TaskPackStepTree.replaceAt(
            steps, leaf, PackStep(op = "setVar", to = "done", expr = "1"),
        )
        assertEquals("done", TaskPackStepTree.stepAt(replaced, leaf)?.to)

        val removed = TaskPackStepTree.removeAt(steps, leaf)
        assertNull(TaskPackStepTree.stepAt(removed, leaf))

        val inserted = TaskPackStepTree.insertAfter(
            steps, leaf, PackStep(op = "wait", ms = 100),
        )
        // 插入发生在最深层 then 里，外层 ifExpr 的 then 仍只有 1 个子节点。
        assertEquals(1, TaskPackStepTree.stepAt(inserted, StepPath.root(1))?.then?.size)
        val deeper = TaskPackStepTree.stepAt(
            inserted, StepPath.root(1).child(0, StepBranch.THEN),
        )
        assertEquals(2, deeper?.then?.size)
        assertEquals("wait", deeper?.then?.get(1)?.op)

        // 顶层上移/下移
        val moved = TaskPackStepTree.moveAt(steps, StepPath.root(0), 1)
        assertEquals("ifExpr", moved[0].op)
        assertEquals("log", moved[1].op)
    }

    @Test
    fun appendChildBuildsMultiLevelNesting() {
        val steps = listOf(PackStep(op = "ifExpr", expr = "a == 0"))
        val root = StepPath.root(0)
        val withThen = TaskPackStepTree.appendChild(
            steps, root, StepBranch.THEN, PackStep(op = "ifText", text = "出战"),
        )
        val inner = root.child(0, StepBranch.THEN)
        val withNested = TaskPackStepTree.appendChild(
            withThen, inner, StepBranch.THEN, PackStep(op = "tapText", text = "确认"),
        )
        assertEquals(
            "tapText",
            TaskPackStepTree.stepAt(withNested, inner.child(0, StepBranch.THEN))?.op,
        )
    }

    @Test
    fun flattenHonorsExpandCollapseAndPaging() {
        val steps = nested()
        val collapsed = TaskPackStepTree.flatten(steps, emptySet())
        assertEquals("只有 3 个顶层节点", 3, collapsed.size)
        assertEquals(2, collapsed[1].childCount) // then(1) + else(1)
        assertFalse(collapsed[1].expanded)

        val expanded = TaskPackStepTree.flatten(steps, setOf(StepPath.root(1).key))
        assertEquals("展开后应出现 then/else 子节点", 5, expanded.size)
        assertTrue(expanded[1].expanded)

        val paged = TaskPackStepTree.flatten(steps, emptySet(), topRange = 0 until 1)
        assertEquals("顶层分页只渲染第 1 个", 1, paged.size)
    }

    @Test
    fun flattenFiltersByOpAndQueryAndAutoExpandsAncestors() {
        val steps = nested()
        val byOp = TaskPackStepTree.flatten(steps, emptySet(), opFilter = "setVar")
        assertTrue("筛选 setVar 应命中嵌套里的那条", byOp.isNotEmpty())
        assertTrue(byOp.all { it.step.op == "setVar" || it.hasChildren })

        val byText = TaskPackStepTree.flatten(steps, emptySet(), query = "出战")
        assertTrue(byText.any { it.step.op == "ifText" })
    }

    @Test
    fun flattenCapsNodeCountForHugePacks() {
        // 模拟真实包：7000 个 setVar，避免一次性构造上万行。
        val steps = (1..7000).map { PackStep(op = "setVar", to = "v$it", expr = "0") }
        val out = TaskPackStepTree.flatten(
            steps, emptySet(), topRange = 0 until Int.MAX_VALUE, maxNodes = 400,
        )
        assertEquals(400, out.size)
    }

    @Test
    fun collectVariablesReadsToExprAndParams() {
        val steps = listOf(
            PackStep(op = "setVar", to = "resume", expr = "0"),
            PackStep(op = "ifExpr", expr = "resume == 0 && clean < 8"),
            PackStep(op = "log", text = "次数 {rounds}"),
            PackStep(op = "whileExpr", expr = "clean == 0", steps = listOf(
                PackStep(op = "readNumber", to = "hit"),
            )),
        )
        val vars = TaskPackStepTree.collectVariables(steps)
        assertTrue(vars.contains("resume"))
        assertTrue(vars.contains("clean"))
        assertTrue(vars.contains("rounds"))
        assertTrue(vars.contains("hit"))
        assertFalse("表达式里的数字不是变量", vars.contains("8"))
    }

    @Test
    fun renameVariableTouchesToExprTextAndNestedBranches() {
        val steps = listOf(
            PackStep(op = "setVar", to = "old", expr = "0"),
            PackStep(op = "ifExpr", expr = "old == 0", then = listOf(
                PackStep(op = "log", text = "值 {old}"),
            )),
        )
        val renamed = TaskPackStepTree.renameVariable(steps, "old", "fresh")
        assertEquals("fresh", renamed[0].to)
        assertEquals("fresh == 0", renamed[1].expr)
        assertEquals("值 {fresh}", renamed[1].then?.first()?.text)
        assertFalse("不应误伤包含关系", "old" in (renamed[1].expr ?: ""))
    }

    @Test
    fun regionTextIsPasteableJsonArray() {
        val text = TaskPackDisplay.regionText(listOf(0.123456, 0.5, 0.25, 0.125))
        assertEquals("[0.1235, 0.5000, 0.2500, 0.1250]", text)
        assertEquals("[]", TaskPackDisplay.regionText(emptyList()))
        assertEquals("[]", TaskPackDisplay.regionText(listOf(1.0)))
    }

    @Test
    fun collectVariablesExtractsParamsPlaceholders() {
        // 回归：正则结尾的 `}` 曾未转义，桌面 JVM 容忍但 Android ICU 会抛
        // PatternSyntaxException，导致一进步骤编辑页就崩溃。
        val steps = listOf(
            PackStep(op = "setVar", to = "loop", expr = "idx + 1"),
            PackStep(op = "log", text = "次数 {count} / 目标 {target}"),
            PackStep(
                op = "ifExpr", expr = "loop < {maxLoop}",
                then = listOf(PackStep(op = "tapText", text = "点击 {btn}")),
            ),
        )
        val names = TaskPackStepTree.collectVariables(steps)
        assertTrue(names.contains("loop"))
        assertTrue(names.contains("idx"))
        assertTrue(names.contains("count"))
        assertTrue(names.contains("target"))
        assertTrue(names.contains("maxLoop"))
        assertTrue(names.contains("btn"))
    }

    @Test
    fun characterChineseIndexCoversMainCastAndVariants() {
        assertEquals("黄泉", TaskPackCharacterNames.chineseKeywords(
            "starrail/images/share/character/Acheron.png", "Acheron"))
        assertEquals("砂金", TaskPackCharacterNames.chineseKeywords(
            "starrail/images/share/character/Aventurine.png", "Aventurine"))
        val variant = TaskPackCharacterNames.chineseKeywords(
            "starrail/images/share/character/FireflySpringMissive.png", "FireflySpringMissive")
        assertTrue("时装变体也应搜到本体中文名", variant.contains("流萤"))
        assertEquals("毁灭命途", TaskPackCharacterNames.chineseKeywords(
            "starrail/images/share/character/CaelumDestruction.png", "CaelumDestruction"))
        assertEquals("", TaskPackCharacterNames.chineseKeywords(
            "starrail/images/screen/back.png", "back"))
        assertTrue(TaskPackCharacterNames.isCharacter(
            "starrail/images/share/character/Blade.png"))
        assertFalse(TaskPackCharacterNames.isCharacter(
            "starrail/images/screen/back.png"))
    }

    @Test
    fun searchMatchesNoteContentAndFieldNames() {
        val steps = listOf(
            PackStep(op = "tap", x = 0.5, y = 0.5, durationMs = 90, note = "点击文字「领取」"),
            PackStep(op = "wait", ms = 1500, note = "等动画"),
            PackStep(op = "waitText", text = "确认", timeoutMs = 9000, note = "等确认按钮"),
        )

        // 搜 note 里的中文：只有那条点击命中。
        val byNote = TaskPackStepTree.flatten(steps, emptySet(), query = "领取")
        assertEquals(listOf("tap"), byNote.map { it.step.op })

        // 搜字段名：只有填了 timeoutMs 的那步命中，证明字段名也能作为索引。
        val byField = TaskPackStepTree.flatten(steps, emptySet(), query = "timeoutms")
        assertEquals(listOf("waitText"), byField.map { it.step.op })

        // 搜字段值：9000 命中 timeoutMs=9000 的步骤。
        val byValue = TaskPackStepTree.flatten(steps, emptySet(), query = "9000")
        assertEquals(listOf("waitText"), byValue.map { it.step.op })

        // 没填这个字段的步骤不应该被字段名带出来。
        assertTrue(TaskPackStepTree.flatten(steps, emptySet(), query = "durationms").size == 1)
    }

    @Test
    fun pathLabelUsesOneBasedIndexes() {
        val path = StepPath.root(2).child(0, StepBranch.THEN).child(1, StepBranch.STEPS)
        assertEquals("3>1>2", path.label())
    }
}
