package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.PackStep
import java.util.Locale

/**
 * 坐标归一化：AI 给的坐标由软件负责，模型写歪了不让整轮生成失败。
 *
 * 模型常见的两种写法：
 * - 0~1 归一化（规范要求，但经常写成 0.52 / 0.73 之外的小数位或轻微越界）；
 * - 1920×1080 像素（它「看图」时更自然），例如 `960, 540`。
 *
 * 这里把大于 1.5 的数当作像素，按画布尺寸换算成 0~1，再统一裁剪到合法区间；
 * region 额外保证 `x + w <= 1`、`y + h <= 1`，避免校验器因为越界报错。
 * 全部字段都合法时原样返回同一个对象（引用相等），调用方可据此判断是否改动过。
 */
object TaskPackCoordinateFix {

    fun normalize(pack: CustomTaskPack, width: Int = 1920, height: Int = 1080): CustomTaskPack {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val fixed = pack.steps.map { normalizeStep(it, w, h) }
        return if (fixed == pack.steps) pack else pack.copy(steps = fixed)
    }

    private fun normalizeStep(step: PackStep, w: Int, h: Int): PackStep {
        val x = axis(step.x, w)
        val y = axis(step.y, h)
        val fromX = axis(step.fromX, w)
        val fromY = axis(step.fromY, h)
        val toX = axis(step.toX, w)
        val toY = axis(step.toY, h)
        val region = normalizeRegion(step.region, w, h)
        val steps = step.steps?.map { normalizeStep(it, w, h) }
        val then = step.then?.map { normalizeStep(it, w, h) }
        val elseSteps = step.elseSteps?.map { normalizeStep(it, w, h) }
        if (x == step.x && y == step.y && fromX == step.fromX && fromY == step.fromY &&
            toX == step.toX && toY == step.toY && region == step.region &&
            steps == step.steps && then == step.then && elseSteps == step.elseSteps
        ) {
            return step
        }
        return step.copy(
            x = x, y = y,
            fromX = fromX, fromY = fromY, toX = toX, toY = toY,
            region = region,
            steps = steps, then = then, elseSteps = elseSteps,
        )
    }

    /** 单轴：>1.5 视为像素值先除以画布边长，再裁剪到 0~1。 */
    private fun axis(value: Double?, side: Int): Double? {
        if (value == null) return null
        if (value.isNaN() || value.isInfinite()) return value
        val normalized = if (value > 1.5) value / side else value
        return round(normalized.coerceIn(0.0, 1.0))
    }

    /** `[x, y, w, h]`：前两项是左上角，后两项是宽高；保证不超出右/下边界。 */
    private fun normalizeRegion(region: List<Double>?, w: Int, h: Int): List<Double>? {
        if (region == null || region.size != 4) return region
        val x = axis(region[0], w) ?: 0.0
        val y = axis(region[1], h) ?: 0.0
        val rw = axis(region[2], w) ?: 1.0
        val rh = axis(region[3], h) ?: 1.0
        val safeW = rw.coerceIn(0.0, 1.0 - x).coerceAtLeast(0.001)
        val safeH = rh.coerceIn(0.0, 1.0 - y).coerceAtLeast(0.001)
        return listOf(x, y, round(safeW), round(safeH))
    }

    /** 保留 4 位，和原版写入格式一致，避免 0.5200000001 这种噪声。 */
    private fun round(value: Double): Double =
        String.format(Locale.US, "%.4f", value).toDouble()
}
