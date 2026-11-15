package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.PackStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「坐标由软件提供」的回归测试。
 *
 * 模型给像素、给越界值都算正常输入：软件必须自己换算成 0~1 并裁剪，
 * 而不是把整轮生成判失败让用户重试。
 */
class TaskPackCoordinateFixTest {

    private fun pack(vararg steps: PackStep) = CustomTaskPack(
        id = "demo.pack", name = "demo", author = "tester", version = "1.0-4.7",
        description = "", steps = steps.toList(),
    )

    @Test
    fun convertsPixelCoordinatesToNormalized() {
        val fixed = TaskPackCoordinateFix.normalize(
            pack(PackStep(op = "tap", x = 960.0, y = 540.0)),
            1920, 1080,
        )
        assertEquals(0.5, fixed.steps[0].x ?: -1.0, 0.0001)
        assertEquals(0.5, fixed.steps[0].y ?: -1.0, 0.0001)
    }

    @Test
    fun clampsOutOfRangeNormalizedCoordinates() {
        val fixed = TaskPackCoordinateFix.normalize(
            pack(PackStep(op = "tap", x = 1.4, y = -0.2)),
        )
        assertEquals(1.0, fixed.steps[0].x ?: -1.0, 0.0001)
        assertEquals(0.0, fixed.steps[0].y ?: -1.0, 0.0001)
    }

    @Test
    fun fixesRegionAndSwipeEndpoints() {
        val fixed = TaskPackCoordinateFix.normalize(
            pack(
                PackStep(op = "waitTemplate", image = "assets/a.png", region = listOf(1800.0, 900.0, 400.0, 400.0)),
                PackStep(op = "swipe", fromX = 960.0, fromY = 800.0, toX = 960.0, toY = 200.0),
            ),
            1920, 1080,
        )
        val region = fixed.steps[0].region!!
        assertEquals(0.9375, region[0], 0.0001)
        // x + w 不能超过 1，否则校验器会判越界。
        assertTrue(region[0] + region[2] <= 1.0001)
        assertTrue(region[1] + region[3] <= 1.0001)
        assertEquals(0.5, fixed.steps[1].fromX ?: -1.0, 0.0001)
        assertEquals(0.1852, fixed.steps[1].toY ?: -1.0, 0.001)
    }

    @Test
    fun keepsNestedBranchesAndValidStepsUntouched() {
        val valid = PackStep(op = "tap", x = 0.5, y = 0.5)
        val original = pack(valid)
        // 全部合法时必须返回同一个对象，避免无意义的重建与历史记录污染。
        assertSame(original, TaskPackCoordinateFix.normalize(original))

        val nested = pack(
            PackStep(
                op = "ifText", text = "确定",
                then = listOf(PackStep(op = "tap", x = 960.0, y = 540.0)),
            ),
        )
        val fixed = TaskPackCoordinateFix.normalize(nested)
        assertEquals(0.5, fixed.steps[0].then!![0].x ?: -1.0, 0.0001)
    }
}
