package com.march7th.hkrpg.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静默录制的手势判定：摇杆、滑动、长按、点击，以及连续滑动合并。
 *
 * 这些用例对应真机上的四类抱怨：摇杆被录成零长度滑动、快速滑动太快游戏不认、
 * 抬起前回拉导致方向偏、一次滑动被拆成两条。
 */
class SilentGestureAnalyzerTest {

    private fun line(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        points: Int = 12,
    ): List<Pair<Double, Double>> = (0..points).map { i ->
        val t = i.toDouble() / points
        (from.first + (to.first - from.first) * t) to (from.second + (to.second - from.second) * t)
    }

    @Test
    fun joystickPushAndReleaseBecomesDirectionalSwipe() {
        // 按住 1.2s，先向右推到 5% 处，再收回按下点：抬起点 == 起点。
        val center = 0.2 to 0.8
        val tip = 0.25 to 0.8
        val track = line(center, tip, 8) + line(tip, center, 8)
        val g = SilentGestureAnalyzer.analyze(track, durationMs = 1200)

        assertEquals("swipe", g.op)
        assertEquals(SilentGestureAnalyzer.KIND_JOYSTICK, g.kind)
        assertEquals(center.first, g.x, 1e-6)
        // 方向必须指向推杆最远处，而不是回到原点（老版本就是这里录歪）。
        assertTrue(g.toX > g.x + 0.04)
        assertEquals(center.second, g.toY, 1e-6)
        // 摇杆保留真实按住时长，让角色能持续走。
        assertEquals(1200L, g.durationMs)
    }

    @Test
    fun holdingStillBecomesLongPress() {
        val p = 0.5 to 0.5
        val track = listOf(p, p, p, p)
        val g = SilentGestureAnalyzer.analyze(track, durationMs = 1500)
        assertEquals("longPress", g.op)
        assertEquals(1500L, g.durationMs)
        assertEquals("", g.kind)
    }

    @Test
    fun quickSmallTapStaysTap() {
        val track = listOf(0.5 to 0.5, 0.504 to 0.503, 0.505 to 0.505)
        val g = SilentGestureAnalyzer.analyze(track, durationMs = 90)
        assertEquals("tap", g.op)
    }

    @Test
    fun fastSwipeGetsMinimumDuration() {
        val track = line(0.5 to 0.8, 0.5 to 0.2)
        val g = SilentGestureAnalyzer.analyze(track, durationMs = 110)
        assertEquals("swipe", g.op)
        // 110ms 的一闪而过在游戏里常被当成点击，至少给到 240ms。
        assertEquals(SilentGestureAnalyzer.SWIPE_MIN_MS, g.durationMs)
        assertTrue(g.toY < 0.3)
    }

    @Test
    fun swipeEndUsesFarthestPointNotReleasePoint() {
        // 滑到 0.2 后回拉一点到 0.3 再抬手：终点应停在 0.2。
        val track = line(0.5 to 0.8, 0.5 to 0.2, 10) + line(0.5 to 0.2, 0.5 to 0.3, 4)
        val g = SilentGestureAnalyzer.analyze(track, durationMs = 400)
        assertEquals("swipe", g.op)
        assertEquals(0.2, g.toY, 0.02)
    }

    @Test
    fun slowLongDragIsStillSwipe() {
        // 慢速拖动列表（1.4s、位移 35%）：不是摇杆，是一条正常滑动。
        val track = line(0.5 to 0.85, 0.5 to 0.45)
        val g = SilentGestureAnalyzer.analyze(track, durationMs = 1400)
        assertEquals("swipe", g.op)
        assertEquals("", g.kind)
        assertEquals(1400L, g.durationMs)
    }

    @Test
    fun consecutiveSameDirectionSwipesMerge() {
        val first = SilentGestureAnalyzer.analyze(line(0.5 to 0.85, 0.5 to 0.45), 260)
        val second = SilentGestureAnalyzer.analyze(line(0.5 to 0.45, 0.5 to 0.1), 260)
        val merged = SilentGestureAnalyzer.mergeSwipes(first, second, gapMs = 120)
        assertTrue(merged != null)
        assertEquals(0.85, merged!!.y, 1e-6)
        assertEquals(0.1, merged.toY, 1e-6)
        assertEquals(520L, merged.durationMs)
    }

    @Test
    fun differentDirectionOrTooLateSwipesAreNotMerged() {
        val up = SilentGestureAnalyzer.analyze(line(0.5 to 0.85, 0.5 to 0.45), 260)
        val down = SilentGestureAnalyzer.analyze(line(0.5 to 0.45, 0.5 to 0.85), 260)
        assertNull(SilentGestureAnalyzer.mergeSwipes(up, down, gapMs = 100))

        val later = SilentGestureAnalyzer.analyze(line(0.5 to 0.45, 0.5 to 0.1), 260)
        assertNull(SilentGestureAnalyzer.mergeSwipes(up, later, gapMs = 900))
    }

    @Test
    fun joystickNeverMerges() {
        val center = 0.2 to 0.8
        val tip = 0.25 to 0.8
        val joystick = SilentGestureAnalyzer.analyze(
            line(center, tip, 6) + line(tip, center, 6),
            1200,
        )
        val another = SilentGestureAnalyzer.analyze(line(center, tip, 8), 800)
        assertNull(SilentGestureAnalyzer.mergeSwipes(joystick, another, gapMs = 100))
    }
}
