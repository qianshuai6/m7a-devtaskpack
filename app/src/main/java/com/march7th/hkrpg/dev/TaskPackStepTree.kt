package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.PackStep

/**
 * 步骤树工具：把 `PackStep` 的 `steps` / `then` / `else` 三层嵌套当作一棵树来编辑。
 *
 * 原版执行器只认这四类容器，这里**不新增任何字段或 op**，只提供按路径定位与
 * 递归改写的能力，让开发者页能可视化编辑多层 IF / while / repeat 嵌套。
 *
 * 路径 [StepPath] 用「每一级在其父节点哪个子列表里的下标」表示，例如
 * `[(3, ROOT), (1, THEN), (0, STEPS)]`：顶层第 4 步 → 它的 then 第 2 步 → 再往下
 * 的 steps 第 1 步。这样任何深度都能唯一定位，且不需要给 PackStep 加 id。
 */
enum class StepBranch { ROOT, STEPS, THEN, ELSE }

data class StepSegment(val index: Int, val branch: StepBranch)

data class StepPath(val parts: List<StepSegment>) {
    val key: String get() = parts.joinToString("/") { "${it.index}:${it.branch.name}" }
    val depth: Int get() = parts.size - 1
    fun child(index: Int, branch: StepBranch): StepPath = StepPath(parts + StepSegment(index, branch))
    val parent: StepPath? get() = if (parts.size <= 1) null else StepPath(parts.dropLast(1))

    companion object {
        fun root(index: Int): StepPath = StepPath(listOf(StepSegment(index, StepBranch.ROOT)))
        fun parse(key: String): StepPath = runCatching {
            StepPath(key.split("/").map { part ->
                val (i, b) = part.split(":")
                StepSegment(i.toInt(), StepBranch.valueOf(b))
            })
        }.getOrDefault(StepPath(emptyList()))
    }
}

/**
 * 人读路径，如 `3>1>2`：每一段是该级在父列表里的**从 1 开始**的序号。
 * 编辑界面用它和 task.json 的嵌套位置对照，找代码时不必一层层展开数。
 */
fun StepPath.label(): String = parts.joinToString(">") { (it.index + 1).toString() }

/** 扁平化后给 LazyColumn 直接渲染的一行；避免递归 composable 在万级节点上爆栈。 */
data class FlatStepNode(
    val path: StepPath,
    val step: PackStep,
    val depth: Int,
    /** 所在子列表的中文标签，如「then 分支」。 */
    val branchLabel: String,
    val childCount: Int,
    val expanded: Boolean,
    val hasChildren: Boolean,
    /** 在同级列表里的下标，用于上移/下移。 */
    val indexInList: Int,
) {
    val pathKey: String get() = path.key
}

/**
 * 步骤的可搜索文本：op、note、文本/表达式/变量名/图片路径，**再加字段名与字段值**。
 *
 * 这样搜 `note` 里的「点击文字」能定位录制步骤，搜 `durationMs` 能筛出所有带时长
 * 的动作，搜 `9000` 能找到 timeoutMs=9000 的那一步；字段名只在真正填了值时才出现，
 * 避免搜 `stable` 时把上万个没这个字段的步骤全命中。
 */
private fun PackStep.searchHaystack(): String = buildString {
    fun add(value: Any?) {
        val text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (isNotEmpty()) append(' ')
        append(text.lowercase())
    }
    add(op)
    add(note)
    add(text)
    add(expr)
    add(to)
    add(image)
    add(region)
    if (x != null || y != null) {
        add("x y 坐标")
        add(x)
        add(y)
    }
    if (fromX != null || fromY != null || toX != null || toY != null) {
        add("fromX fromY toX toY 滑动")
        add(fromX); add(fromY); add(toX); add(toY)
    }
    if (durationMs != null) { add("durationMs"); add(durationMs) }
    if (ms != null) { add("ms"); add(ms) }
    if (timeoutMs != null) { add("timeoutMs"); add(timeoutMs) }
    if (intervalMs != null) { add("intervalMs"); add(intervalMs) }
    if (maxTries != null) { add("maxTries"); add(maxTries) }
    if (stable != null) { add("stable"); add(stable) }
    if (offsetX != null || offsetY != null) { add("offsetX offsetY"); add(offsetX); add(offsetY) }
    if (threshold != null) { add("threshold"); add(threshold) }
    if (color != null) { add("color 颜色"); add(color) }
    if (tolerance != null) { add("tolerance"); add(tolerance) }
    if (minArea != null || maxArea != null) { add("minArea maxArea"); add(minArea); add(maxArea) }
    if (compare != null) { add("compare"); add(compare) }
    if (value != null) { add("value"); add(value) }
    if (ratio != null) { add("ratio"); add(ratio) }
    if (times != null) { add("times"); add(times) }
    if (ok != null) { add("ok"); add(ok) }
    if (optional == true) add("optional 可选")
}

/** 一个步骤的全部子列表，按 then / else / steps 的固定顺序展开。 */
private data class ChildList(val branch: StepBranch, val label: String, val items: List<PackStep>)

private fun childrenOf(step: PackStep): List<ChildList> = buildList {
    step.then?.let { add(ChildList(StepBranch.THEN, "then 分支", it)) }
    step.elseSteps?.let { add(ChildList(StepBranch.ELSE, "else 分支", it)) }
    step.steps?.let { add(ChildList(StepBranch.STEPS, "steps 子步骤", it)) }
}

private fun childCountOf(step: PackStep): Int = childrenOf(step).sumOf { it.items.size }

object TaskPackStepTree {

    fun branchLabel(branch: StepBranch): String = when (branch) {
        StepBranch.ROOT -> "顶层"
        StepBranch.THEN -> "then 分支"
        StepBranch.ELSE -> "else 分支"
        StepBranch.STEPS -> "steps 子步骤"
    }

    /** 在指定路径取到步骤；路径失效返回 null。 */
    fun stepAt(steps: List<PackStep>, path: StepPath): PackStep? {
        var list = steps
        var result: PackStep? = null
        path.parts.forEachIndexed { level, seg ->
            if (level == 0 && seg.branch != StepBranch.ROOT) return null
            val node = list.getOrNull(seg.index) ?: return null
            result = node
            val next = when (path.parts.getOrNull(level + 1)?.branch) {
                StepBranch.THEN -> node.then
                StepBranch.ELSE -> node.elseSteps
                StepBranch.STEPS -> node.steps
                else -> null
            }
            if (level < path.parts.lastIndex) list = next ?: return null
        }
        return result
    }

    /** 递归替换某一路径上的步骤；找不到则原样返回。 */
    fun replaceAt(steps: List<PackStep>, path: StepPath, newStep: PackStep): List<PackStep> =
        rewriteAt(steps, path) { _, _ -> RewriteResult.REPLACE to newStep }

    /** 递归删除某一路径上的步骤；找不到则原样返回。 */
    fun removeAt(steps: List<PackStep>, path: StepPath): List<PackStep> =
        rewriteAt(steps, path) { _, _ -> RewriteResult.REMOVE to null }

    /** 在某一路径的**同级之后**插入一步；顶层与任意子列表都适用。 */
    fun insertAfter(steps: List<PackStep>, path: StepPath, newStep: PackStep): List<PackStep> =
        rewriteAt(steps, path) { _, _ -> RewriteResult.INSERT_AFTER to newStep }

    /** 在同级内上移/下移；越界返回原列表。 */
    fun moveAt(steps: List<PackStep>, path: StepPath, delta: Int): List<PackStep> {
        val seg = path.parts.lastOrNull() ?: return steps
        val parent = path.parent
        val list = if (parent == null) steps else {
            val parentStep = stepAt(steps, parent) ?: return steps
            when (seg.branch) {
                StepBranch.THEN -> parentStep.then
                StepBranch.ELSE -> parentStep.elseSteps
                StepBranch.STEPS -> parentStep.steps
                StepBranch.ROOT -> null
            } ?: return steps
        }
        val target = seg.index + delta
        if (seg.index !in list.indices || target !in list.indices) return steps
        val reordered = list.toMutableList().apply {
            val item = removeAt(seg.index); add(target, item)
        }
        return if (parent == null) reordered else {
            replaceAt(steps, parent, setChildList(stepAt(steps, parent)!!, seg.branch, reordered))
        }
    }

    /** 在某个分支列表末尾追加一步（用于「在分支里加一步」）。 */
    fun appendChild(steps: List<PackStep>, parentPath: StepPath, branch: StepBranch,
                    newStep: PackStep): List<PackStep> {
        val parent = stepAt(steps, parentPath) ?: return steps
        val current = when (branch) {
            StepBranch.THEN -> parent.then
            StepBranch.ELSE -> parent.elseSteps
            StepBranch.STEPS -> parent.steps
            StepBranch.ROOT -> return steps
        }.orEmpty()
        return replaceAt(steps, parentPath, setChildList(parent, branch, current + newStep))
    }

    private fun setChildList(step: PackStep, branch: StepBranch, items: List<PackStep>): PackStep =
        when (branch) {
            StepBranch.THEN -> step.copy(then = items)
            StepBranch.ELSE -> step.copy(elseSteps = items)
            StepBranch.STEPS -> step.copy(steps = items)
            StepBranch.ROOT -> step
        }

    private enum class RewriteResult { REPLACE, REMOVE, INSERT_AFTER }

    /** 通用递归改写：只重写目标所在的那一层列表，其余分支原样复用，避免深拷贝整棵树。 */
    private fun rewriteAt(
        steps: List<PackStep>,
        path: StepPath,
        action: (PackStep, Int) -> Pair<RewriteResult, PackStep?>,
    ): List<PackStep> {
        val parts = path.parts
        if (parts.isEmpty()) return steps

        fun recurse(list: List<PackStep>, level: Int, branch: StepBranch): List<PackStep> {
            val seg = parts[level]
            if (seg.index !in list.indices) return list
            val node = list[seg.index]
            val isLast = level == parts.lastIndex
            if (isLast) {
                val (op, value) = action(node, seg.index)
                return when (op) {
                    RewriteResult.REPLACE -> list.toMutableList().apply { set(seg.index, value!!) }
                    RewriteResult.REMOVE -> list.toMutableList().apply { removeAt(seg.index) }
                    RewriteResult.INSERT_AFTER -> list.toMutableList().apply {
                        add(seg.index + 1, value!!)
                    }
                }
            }
            val nextBranch = parts[level + 1].branch
            val children = when (nextBranch) {
                StepBranch.THEN -> node.then
                StepBranch.ELSE -> node.elseSteps
                StepBranch.STEPS -> node.steps
                StepBranch.ROOT -> null
            } ?: return list
            val rewritten = recurse(children, level + 1, nextBranch)
            val updated = setChildList(node, nextBranch, rewritten)
            return list.toMutableList().apply { set(seg.index, updated) }
        }
        return recurse(steps, 0, StepBranch.ROOT)
    }

    /**
     * 扁平化成可渲染的行。
     * - 只展开 [expanded] 里出现的父节点，默认全部折叠，万级节点也只渲染顶层分页；
     * - [query] 非空时按 op / note / text / expr / to 过滤，并自动展开命中项的所有祖先；
     * - [opFilter] 非空时只保留该 op；
     * - [topRange] 控制顶层分页，避免一次构造 10548 行。
     */
    fun flatten(
        steps: List<PackStep>,
        expanded: Set<String>,
        query: String = "",
        opFilter: String = "",
        topRange: IntRange = 0 until Int.MAX_VALUE,
        /** 单次最多构造多少行：搜索命中 7000+ setVar 时也只渲染前若干行，避免卡死。 */
        maxNodes: Int = 400,
    ): List<FlatStepNode> {
        val q = query.trim().lowercase()
        val out = ArrayList<FlatStepNode>()
        val matchAny = q.isNotEmpty() || opFilter.isNotEmpty()
        fun matches(s: PackStep): Boolean {
            if (opFilter.isNotEmpty() && s.op != opFilter) return false
            if (q.isEmpty()) return true
            // 先比 op，命中就不用拼整串（万步包里这个短路省掉大量字符串操作）。
            if (s.op.lowercase().contains(q)) return true
            return s.searchHaystack().contains(q)
        }
        /** 判断子树里是否有命中项，用于搜索时自动展开祖先。 */
        fun subtreeMatches(s: PackStep): Boolean {
            if (matches(s)) return true
            return childrenOf(s).any { c -> c.items.any(::subtreeMatches) }
        }
        fun walk(list: List<PackStep>, parent: StepPath?, branch: StepBranch, depth: Int) {
            if (out.size >= maxNodes) return
            list.forEachIndexed { index, step ->
                if (out.size >= maxNodes) return@forEachIndexed
                if (parent == null && index !in topRange) return@forEachIndexed
                val path = if (parent == null) StepPath.root(index)
                else parent.child(index, branch)
                if (matchAny && !subtreeMatches(step)) return@forEachIndexed
                val children = childrenOf(step)
                val count = children.sumOf { it.items.size }
                val open = matchAny || path.key in expanded
                out += FlatStepNode(
                    path = path, step = step, depth = depth,
                    branchLabel = branchLabel(branch),
                    childCount = count, expanded = open, hasChildren = count > 0,
                    indexInList = index,
                )
                if (open) {
                    children.forEach { c -> walk(c.items, path, c.branch, depth + 1) }
                }
            }
        }
        walk(steps, null, StepBranch.ROOT, 0)
        return out
    }

    /** 收集整棵树里出现过的变量名（setVar.to / readX.to / expr 里的标识符 / {params}）。 */
    fun collectVariables(steps: List<PackStep>): List<String> {
        val out = LinkedHashSet<String>()
        val exprToken = Regex("[A-Za-z_][A-Za-z0-9_]*")
        // 结尾的 `}` 必须转义：Android 用 ICU 正则实现，未转义的 `}` 会直接抛
        // PatternSyntaxException（桌面 JVM 容忍，真机必崩），导致一进步骤编辑页就崩溃。
        val paramToken = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")
        fun visit(list: List<PackStep>) {
            list.forEach { s ->
                s.to?.takeIf { it.isNotBlank() }?.let(out::add)
                s.expr?.let { exprToken.findAll(it).forEach { m -> out.add(m.value) } }
                s.text?.let { paramToken.findAll(it).forEach { m -> out.add(m.groupValues[1]) } }
                s.then?.let(::visit); s.elseSteps?.let(::visit); s.steps?.let(::visit)
            }
        }
        visit(steps)
        // 表达式里的关键字不是变量
        out.removeAll(setOf("true", "false", "and", "or", "not", "abs", "min", "max"))
        return out.sorted()
    }

    /** 变量名全局重命名：to / expr（词边界）/ text 里的 {变量} 一起改。 */
    fun renameVariable(steps: List<PackStep>, old: String, new: String): List<PackStep> {
        if (old.isBlank() || new.isBlank() || old == new) return steps
        return mapList(steps, old, new)
    }

    private fun mapList(list: List<PackStep>, old: String, new: String): List<PackStep> {
        val word = Regex("\\b${Regex.escape(old)}\\b")
        fun one(s: PackStep): PackStep = s.copy(
            to = if (s.to == old) new else s.to,
            expr = s.expr?.let { word.replace(it, new) },
            text = s.text?.replace("{$old}", "{$new}"),
            then = s.then?.let { mapList(it, old, new) },
            elseSteps = s.elseSteps?.let { mapList(it, old, new) },
            steps = s.steps?.let { mapList(it, old, new) },
        )
        return list.map(::one)
    }
}
