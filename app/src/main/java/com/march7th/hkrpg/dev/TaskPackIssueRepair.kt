package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.PackExpr
import com.march7th.hkrpg.data.model.PackStep

/**
 * 校验导出页的「一键修复」。
 *
 * 只修**意图明确、改写无损**的问题，修不了的继续按原文报错，绝不猜内容：
 *
 * - 表达式（`setVar` / `ifExpr` / `whileExpr` 的 `expr`）里把变量写成 `{count}`：
 *   表达式语法只认裸标识符，`{}` 是文本字段引用参数的写法，去掉即可
 * - 表达式里的全角括号 / 引号 / 运算符、单独一个 `=`（应为 `==`）
 * - 越界数值：归一化坐标 clamp 到 0~1、`threshold` 0.3~1、`tolerance` 0~128、
 *   `maxTries` 1~30、`intervalMs` 50~5000 等，都是校验器写死的区间
 * - `region` 越界：宽高兜底为正、`x+w` / `y+h` 超出画面时收宽高
 * - `compare` 写成 `=` / `≥` / `≤` 这类同义符号
 * - `color` 少了 `#` 或小写
 */
object TaskPackIssueRepair {

    data class Result(val pack: CustomTaskPack, val fixes: List<String>)

    fun repair(pack: CustomTaskPack): Result {
        val counter = Counter()
        val steps = pack.steps.map { fixStep(it, counter, 1) }
        return Result(pack.copy(steps = steps), counter.lines())
    }

    /**
     * 递归上限。校验器的嵌套上限是 4 层，这里放宽到 16 层 ——
     * 手写 JSON 改坏时可能出现很深的重复嵌套，递归没有上限会 `StackOverflowError`，
     * 那是 `Error` 而不是 `Exception`，`runCatching` 也兜不住，直接闪退。
     */
    private const val MAX_FIX_DEPTH = 16

    private class Counter {
        private val map = LinkedHashMap<String, Int>()
        fun add(what: String, n: Int = 1) {
            map[what] = (map[what] ?: 0) + n
        }

        fun lines(): List<String> = map.map { (k, v) -> if (v > 1) "$k（$v 处）" else k }
    }

    private fun fixStep(step: PackStep, c: Counter, depth: Int): PackStep {
        if (depth > MAX_FIX_DEPTH) return step
        var s = step

        // ---- 表达式 ----
        s.expr?.let { raw ->
            val fixed = fixExpr(raw)
            if (fixed != raw) {
                s = s.copy(expr = fixed)
                c.add("去掉表达式里多余的 {}、全角符号或单等号")
            }
        }

        // ---- 归一化坐标 ----
        val nx = norm(s.x)
        val ny = norm(s.y)
        if (nx != s.x) c.add("把越界的 x 收进 0~1")
        if (ny != s.y) c.add("把越界的 y 收进 0~1")
        s = s.copy(
            x = nx,
            y = ny,
            fromX = norm(s.fromX),
            fromY = norm(s.fromY),
            toX = norm(s.toX),
            toY = norm(s.toY),
        )

        // ---- region ----
        s.region?.let { region ->
            val r = fixRegion(region, c)
            if (r != region) s = s.copy(region = r)
        }

        // ---- 区间字段 ----
        s = s.copy(
            threshold = inRange(s.threshold, 0.3, 1.0, c, "threshold"),
            tolerance = inRangeInt(s.tolerance, 0, 128, c, "tolerance"),
            ratio = inRange(s.ratio, 0.0, 1.0, c, "ratio"),
            minArea = inRangeInt(s.minArea, 1, 200_000, c, "minArea"),
            maxArea = inRangeInt(s.maxArea, 1, 200_000, c, "maxArea"),
            maxTries = inRangeInt(s.maxTries, 1, 30, c, "maxTries"),
            intervalMs = inRangeLong(s.intervalMs, 50L, 5_000L, c, "intervalMs"),
            compare = fixCompare(s.compare, c),
            color = fixColor(s.color, c),
        )

        // ---- 子步骤 ----
        s.steps?.let { sub ->
            val fixed = sub.map { fixStep(it, c, depth + 1) }
            if (fixed != sub) s = s.copy(steps = fixed)
        }
        s.then?.let { sub ->
            val fixed = sub.map { fixStep(it, c, depth + 1) }
            if (fixed != sub) s = s.copy(then = fixed)
        }
        s.elseSteps?.let { sub ->
            val fixed = sub.map { fixStep(it, c, depth + 1) }
            if (fixed != sub) s = s.copy(elseSteps = fixed)
        }
        return s
    }

    // ==================== 表达式 ====================

    /**
     * `{count}` → `count`：表达式里变量是裸标识符，花括号属于文本字段。
     *
     * ⚠️ 结尾的 `}` 必须写成 `\}`：Android 的 ICU 正则把裸 `}` 当量词结束符，
     * 直接抛 `PatternSyntaxException`。这里是 `object` 的字段，异常发生在 `<clinit>`，
     * 表现为**一点「一键修复」就整进程闪退**（`ExceptionInInitializerError`），
     * 而 JVM 的 `java.util.regex` 对裸 `}` 宽容，所以单测和编译都拦不住。
     */
    private val BRACED = Regex("\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}")
    private val SINGLE_EQ = Regex("(?<![<>=!])=(?!=)")

    private fun fixExpr(raw: String): String {
        var s = raw.trim()
        s = BRACED.replace(s) { it.groupValues[1] }
        s = s.replace('（', '(').replace('）', ')')
            .replace('，', ',')
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
            .replace('＝', '=').replace('＞', '>').replace('＜', '<')
            .replace('！', '!').replace('＆', '&').replace('｜', '|')
            .replace('＋', '+').replace('－', '-').replace('＊', '*')
            .replace('／', '/').replace('％', '%')
            .replace('\u3000', ' ')
        // 单等号是赋值写法，表达式里只有 `==`
        s = SINGLE_EQ.replace(s, "==")
        s = s.replace(Regex("\\s+"), " ").trim()
        // 改完反而解析不过、原文却能过，说明改坏了，保留原文
        if (PackExpr.checkSyntax(s) != null && PackExpr.checkSyntax(raw) == null) return raw
        return s
    }

    // ==================== 数值 ====================

    private fun norm(v: Double?): Double? {
        if (v == null || v.isNaN() || v.isInfinite()) return v
        return v.coerceIn(0.0, 1.0)
    }

    private fun inRange(v: Double?, lo: Double, hi: Double, c: Counter, name: String): Double? {
        if (v == null || v.isNaN() || v.isInfinite()) return v
        val f = v.coerceIn(lo, hi)
        if (f != v) c.add("把越界的 $name 收进 $lo~$hi")
        return f
    }

    private fun inRangeInt(v: Int?, lo: Int, hi: Int, c: Counter, name: String): Int? {
        if (v == null) return null
        val f = v.coerceIn(lo, hi)
        if (f != v) c.add("把越界的 $name 收进 $lo~$hi")
        return f
    }

    private fun inRangeLong(v: Long?, lo: Long, hi: Long, c: Counter, name: String): Long? {
        if (v == null) return null
        val f = v.coerceIn(lo, hi)
        if (f != v) c.add("把越界的 $name 收进 $lo~$hi")
        return f
    }

    private fun fixRegion(region: List<Double>, c: Counter): List<Double> {
        if (region.size != 4) return region
        var (x, y, w, h) = region
        if (x.isNaN() || y.isNaN() || w.isNaN() || h.isNaN()) return region
        x = x.coerceIn(0.0, 1.0)
        y = y.coerceIn(0.0, 1.0)
        w = w.coerceIn(0.001, 1.0)
        h = h.coerceIn(0.001, 1.0)
        if (x + w > 1.0) w = (1.0 - x).coerceAtLeast(0.001)
        if (y + h > 1.0) h = (1.0 - y).coerceAtLeast(0.001)
        val fixed = listOf(x, y, w, h)
        if (fixed != region) c.add("把越界的 region 收回画面内")
        return fixed
    }

    // ==================== 枚举 ====================

    private val COMPARE_ALIAS = mapOf(
        "=" to "==", "==" to "==", "===" to "==",
        "≥" to ">=", ">" to ">", "=>" to ">=",
        "≤" to "<=", "<" to "<", "=<" to "<=",
        "≠" to "!=", "!=" to "!=",
        "＞" to ">", "＜" to "<", "＞=" to ">=", "＜=" to "<=",
    )

    private fun fixCompare(v: String?, c: Counter): String? {
        if (v == null) return null
        val t = v.trim()
        val fixed = COMPARE_ALIAS[t] ?: return v
        if (fixed != v) c.add("把 compare 的同义写法统一")
        return fixed
    }

    private fun fixColor(v: String?, c: Counter): String? {
        if (v == null) return null
        val t = v.trim()
        if (t.contains(',')) return t
        val hex = t.removePrefix("#")
        if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return v
        val fixed = "#" + hex.uppercase()
        if (fixed != v) c.add("把 color 统一写成 #RRGGBB")
        return fixed
    }
}
