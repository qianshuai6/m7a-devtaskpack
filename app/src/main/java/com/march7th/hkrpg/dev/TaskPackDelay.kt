package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.PackStep

/** 批量增加延迟时可单独作用的 ms 类字段。 */
enum class DelayField(val label: String, val jsonKey: String) {
    WAIT("wait.ms（固定等待）", "ms"),
    DURATION("tap/swipe 的 durationMs", "durationMs"),
    TIMEOUT("waitText/waitTemplate 的 timeoutMs", "timeoutMs"),
    INTERVAL("swipeUntil 的 intervalMs", "intervalMs"),
}

/**
 * 全局 / 分字段 ms 延迟的纯逻辑，放在 dev 包以便单元测试直接覆盖。
 *
 * 只改原版已有的 ms 类字段，递归下钻 steps/then/else 嵌套分支；
 * 结果仍是一棵合法的 PackStep 树，不新增任何字段或动作。
 */
object TaskPackDelay {
    fun limitFor(field: DelayField): Long = when (field) {
        DelayField.TIMEOUT -> 120_000L
        else -> 60_000L
    }

    /** 给某一类 ms 字段的所有已有值统一 +deltaMs，返回新树与受影响步数。 */
    fun apply(
        steps: List<PackStep>,
        field: DelayField,
        deltaMs: Long,
    ): Pair<List<PackStep>, Int> {
        if (deltaMs == 0L) return steps to 0
        val limit = limitFor(field)
        var affected = 0
        val updated = mapRecursive(steps) { step ->
            fun bump(value: Long?): Long? = value?.let { (it + deltaMs).coerceIn(0L, limit) }
            val out = when (field) {
                DelayField.WAIT -> if (step.op == "wait" && step.ms != null) {
                    step.copy(ms = bump(step.ms))
                } else step
                DelayField.DURATION -> if (step.durationMs != null) {
                    step.copy(durationMs = bump(step.durationMs))
                } else step
                DelayField.TIMEOUT -> if (step.timeoutMs != null) {
                    step.copy(timeoutMs = bump(step.timeoutMs))
                } else step
                DelayField.INTERVAL -> if (step.intervalMs != null) {
                    step.copy(intervalMs = bump(step.intervalMs))
                } else step
            }
            if (out != step) affected++
            out
        }
        return updated to affected
    }

    /** 在每一个顶层步骤后插入一条 wait，实现「全局延迟」。 */
    fun insertWaitForEveryStep(steps: List<PackStep>, ms: Long): List<PackStep> {
        val waitMs = ms.coerceIn(1L, 60_000L)
        val out = ArrayList<PackStep>(steps.size * 2)
        steps.forEach { step ->
            out += step
            out += PackStep(op = "wait", ms = waitMs, note = "批量插入的全局延迟")
        }
        return out
    }

    private fun mapRecursive(
        steps: List<PackStep>,
        transform: (PackStep) -> PackStep,
    ): List<PackStep> = steps.map { source ->
        val mapped = transform(source)
        mapped.copy(
            steps = mapped.steps?.let { mapRecursive(it, transform) },
            then = mapped.then?.let { mapRecursive(it, transform) },
            elseSteps = mapped.elseSteps?.let { mapRecursive(it, transform) },
        )
    }
}
