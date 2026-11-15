package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.PackExpr
import com.march7th.hkrpg.data.model.PackStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPackIssueRepairTest {

    private fun pack(vararg steps: PackStep) = CustomTaskPack(id = "dev.test", steps = steps.toList())

    @Test
    fun bracesInExprAreRemoved() {
        // 校验器报的就是这个：setVar.expr 写了 {count}，表达式只认裸标识符。
        val step = PackStep(op = "setVar", to = "left", expr = "{count} - 1")
        assertNotNull(PackExpr.checkSyntax(step.expr!!))

        val result = TaskPackIssueRepair.repair(pack(step))
        val fixed = result.pack.steps.single().expr
        assertEquals("count - 1", fixed)
        assertNull(PackExpr.checkSyntax(fixed!!))
        assertTrue(result.fixes.isNotEmpty())
    }

    @Test
    fun fullWidthAndSingleEqualsAreFixed() {
        val step = PackStep(op = "ifExpr", expr = "（{count}＝3）")
        val fixed = TaskPackIssueRepair.repair(pack(step)).pack.steps.single().expr
        assertEquals("(count==3)", fixed)
        assertNull(PackExpr.checkSyntax(fixed!!))
    }

    @Test
    fun nestedBranchesAreRepaired() {
        val inner = PackStep(op = "setVar", to = "x", expr = "{x}+1")
        val mid = PackStep(op = "ifExpr", expr = "{x}>0", then = listOf(inner))
        val root = PackStep(op = "repeat", times = kotlinx.serialization.json.JsonPrimitive(2), steps = listOf(mid))

        // 层级：repeat → ifExpr → then → setVar，断言要按这层往下走。
        val rootFixed = TaskPackIssueRepair.repair(pack(root)).pack.steps.single()
        val midFixed = rootFixed.steps!!.single()
        assertEquals("x>0", midFixed.expr)
        assertEquals("x+1", midFixed.then!!.single().expr)
    }

    @Test
    fun outOfRangeNumbersAreClamped() {
        val step = PackStep(
            op = "tap",
            x = 1.4, y = -0.2,
            threshold = 0.1, tolerance = 300, maxTries = 99, intervalMs = 10_000,
        )
        val fixed = TaskPackIssueRepair.repair(pack(step)).pack.steps.single()
        assertEquals(1.0, fixed.x!!, 1e-9)
        assertEquals(0.0, fixed.y!!, 1e-9)
        assertEquals(0.3, fixed.threshold!!, 1e-9)
        assertEquals(128, fixed.tolerance)
        assertEquals(30, fixed.maxTries)
        assertEquals(5_000L, fixed.intervalMs)
    }

    @Test
    fun regionIsPulledInsideFrame() {
        val step = PackStep(op = "ifCount", region = listOf(0.8, 0.9, 0.5, 0.5))
        val region = TaskPackIssueRepair.repair(pack(step)).pack.steps.single().region!!
        assertTrue(region[0] + region[2] <= 1.0 + 1e-9)
        assertTrue(region[1] + region[3] <= 1.0 + 1e-9)
        assertTrue(region[2] > 0.0 && region[3] > 0.0)
    }

    @Test
    fun compareAndColorAreNormalised() {
        val a = PackStep(op = "ifCount", compare = "≥")
        assertEquals(">=", TaskPackIssueRepair.repair(pack(a)).pack.steps.single().compare)

        val b = PackStep(op = "waitColor", color = "ff8800")
        assertEquals("#FF8800", TaskPackIssueRepair.repair(pack(b)).pack.steps.single().color)
    }

    @Test
    fun validPackIsLeftUntouched() {
        val step = PackStep(op = "setVar", to = "left", expr = "count - 1", x = 0.5, y = 0.5)
        val result = TaskPackIssueRepair.repair(pack(step))
        assertEquals(step, result.pack.steps.single())
        assertTrue(result.fixes.isEmpty())
    }

    @Test
    fun deepNestingDoesNotBlowTheStack() {
        // 手写 JSON 改坏时可能嵌套几十层；递归没有上限会 StackOverflowError，
        // 那是 Error 不是 Exception，兜不住就整进程闪退。
        var step = PackStep(op = "setVar", to = "x", expr = "{x}+1")
        repeat(200) { step = PackStep(op = "repeat", steps = listOf(step)) }
        val fixed = TaskPackIssueRepair.repair(pack(step))
        // 超过递归上限的层级原样返回，不修改也不抛异常。
        assertTrue(fixed.fixes.isEmpty())
    }
}
