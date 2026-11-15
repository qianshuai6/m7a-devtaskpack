package com.march7th.hkrpg.dev

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 静默录制的手势判定：把一条触摸轨迹变成原版任务包里的动作。
 *
 * 为什么不能只看「起点 + 抬起点」：
 *
 * - **摇杆**：按住推方向时，手指会推出去再回来（或原地画圈），抬起点几乎回到按下点。
 *   只取终点会得到一条零长度的 `swipe`，跑任务时角色原地不动 —— 这是老版本最明显的问题。
 *   所以终点改用**轨迹里离起点最远的点**（推杆方向），并用「按住时长够长 + 推杆幅度有限 +
 *   有明显回程」把它识别成摇杆。
 * - **滑动**：快速滑动时抬起前常有一点回拉或抖动，终点跟着抬起点走就会偏方向、偏距离；
 *   取最远点更接近用户想滑到的地方。同时给一个时长下限，避免 80ms 的「一闪而过」被游戏忽略。
 * - **连续滑动**：手滑一次列表、手指微抬再补一下，会被记成两条同向 swipe。相邻且同向、
 *   首尾相接的两条合并成一条，距离与时长相加，效果与用户意图一致。
 *
 * 这些都是纯几何/时序判断，没有 Android 依赖，单测直接覆盖。
 */
object SilentGestureAnalyzer {

    /** 按住超过这个时长就当作长按（与原版录制一致）。 */
    const val LONG_PRESS_MS = 550L

    /** 滑动的最短注入时长：低于这个值游戏经常当成点击，看不到滚动。 */
    const val SWIPE_MIN_MS = 240L

    /** 与包内校验上限一致，避免录出 12 秒的手势。 */
    const val MAX_DURATION_MS = 10_000L

    /** 净位移小于画面 1.2% 认为「没动」（1920 宽下约 23px）。 */
    const val TAP_SPOT = 0.012

    /** 摇杆至少按住这么久才可能是「推着走」，点一下不是摇杆。 */
    const val JOYSTICK_MIN_MS = 650L

    /** 摇杆推杆的位移上限：超过这个幅度基本是滑屏，不是摇杆。 */
    const val JOYSTICK_MAX_SPOT = 0.09

    /** 两条滑动间隔不超过这个时间才考虑合并。 */
    const val MERGE_GAP_MS = 360L

    /** 合并时要求后一条的起点离前一条终点这么近（归一化）。 */
    const val MERGE_MAX_START_GAP = 0.08

    /** 合并时允许的最大方向夹角。 */
    const val MERGE_MAX_ANGLE_DEG = 32.0

    /** 摇杆标记：op 仍是 `swipe`，只用于面板展示与 note 文案。 */
    const val KIND_JOYSTICK = "joystick"

    data class Gesture(
        /** 24 个原 op 之一：tap / longPress / swipe。 */
        val op: String,
        /** 空表示普通动作，[KIND_JOYSTICK] 表示摇杆推杆。 */
        val kind: String = "",
        val x: Double,
        val y: Double,
        val toX: Double = x,
        val toY: Double = y,
        val durationMs: Long = 0L,
        /** 轨迹净位移（归一化），用于面板显示「推杆 6%」。 */
        val travel: Double = 0.0,
    )

    /**
     * @param track 归一化触摸轨迹（按下 → 抬起，按顺序），至少一个点
     * @param durationMs 从按下到抬起的真实时长
     */
    fun analyze(track: List<Pair<Double, Double>>, durationMs: Long): Gesture {
        val pts = track.filter { it.first.isFinite() && it.second.isFinite() }
        val d = durationMs.coerceIn(0L, MAX_DURATION_MS)
        if (pts.isEmpty()) return Gesture(op = "tap", x = 0.0, y = 0.0, durationMs = d)

        val first = pts.first()
        val far = pts.maxByOrNull { dist(first, it) } ?: first
        val maxD = dist(first, far)
        val net = dist(first, pts.last())

        // 几乎没动：长按还是点击，只取决于按住多久。
        if (maxD < TAP_SPOT) {
            return Gesture(
                op = if (d >= LONG_PRESS_MS) "longPress" else "tap",
                x = first.first,
                y = first.second,
                durationMs = d,
            )
        }

        // 摇杆：按住够久 + 推杆幅度有限 + 有回程（推出去又收回）或原地画圈。
        val path = pathLength(pts)
        val returnBack = net < maxD * 0.4
        val wiggle = path > maxD * 2.0
        if (d >= JOYSTICK_MIN_MS && maxD <= JOYSTICK_MAX_SPOT && (returnBack || wiggle)) {
            return Gesture(
                op = "swipe",
                kind = KIND_JOYSTICK,
                x = first.first,
                y = first.second,
                toX = far.first,
                toY = far.second,
                durationMs = d,
                travel = maxD,
            )
        }

        // 普通滑动：终点取轨迹里的最远点，时长给个下限。
        return Gesture(
            op = "swipe",
            x = first.first,
            y = first.second,
            toX = far.first,
            toY = far.second,
            durationMs = maxOf(d, SWIPE_MIN_MS),
            travel = maxD,
        )
    }

    /**
     * 相邻两条同向滑动合并成一条：起点用前一条、终点用后一条，时长相加。
     * 摇杆永不合并（它是「一次按住」）。
     *
     * @param gapMs 前一条结束到后一条按下的间隔，负数按 0 处理
     */
    fun mergeSwipes(prev: Gesture, next: Gesture, gapMs: Long): Gesture? {
        if (prev.op != "swipe" || next.op != "swipe") return null
        if (prev.kind == KIND_JOYSTICK || next.kind == KIND_JOYSTICK) return null
        if (gapMs > MERGE_GAP_MS) return null
        if (dist(prev.toX to prev.toY, next.x to next.y) > MERGE_MAX_START_GAP) return null
        val angle = angleBetween(
            prev.toX - prev.x to prev.toY - prev.y,
            next.toX - next.x to next.toY - next.y,
        )
        if (angle > MERGE_MAX_ANGLE_DEG) return null
        return prev.copy(
            toX = next.toX,
            toY = next.toY,
            durationMs = (prev.durationMs + next.durationMs).coerceAtMost(MAX_DURATION_MS),
            travel = prev.travel + next.travel,
        )
    }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        hypot(a.first - b.first, a.second - b.second)

    private fun pathLength(pts: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 1 until pts.size) total += dist(pts[i - 1], pts[i])
        return total
    }

    /** 两个向量的夹角（度，0~180）。零向量按「同向」处理，交给其它条件判断。 */
    private fun angleBetween(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        if ((a.first == 0.0 && a.second == 0.0) || (b.first == 0.0 && b.second == 0.0)) return 0.0
        val deg = abs(atan2(a.second, a.first) - atan2(b.second, b.first)) * 180.0 / PI
        return if (deg > 180.0) 360.0 - deg else deg
    }
}
