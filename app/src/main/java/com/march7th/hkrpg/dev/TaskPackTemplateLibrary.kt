package com.march7th.hkrpg.dev

import android.content.Context
import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.data.model.PackStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.ZipInputStream

/**
 * 任务包模板库。
 *
 * 「整包模板」是把上游仓库 `site/taskpacks/` 与 `examples/` 里的**全部** `.m7atask`
 * 原样内置进 APK，不做任何改写：包含真实坐标、真实模板图，可直接载入继续编辑或导出。
 * 「步骤模板」是 24 个原语 op 的字段骨架，供拼流程时一键插入。
 *
 * 两类模板都不新增 op、不新增字段，也不自带任何由工具臆造的坐标或图标 —— 步骤模板
 * 的坐标/模板/文字字段一律留空，必须由开发者在画面上取点或框选后填写。
 */
data class PackTemplateEntry(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val description: String,
    val stepCount: Int,
    /** assets/dev/templates/packs/ 下的文件名 */
    val fileName: String,
    val source: String,
    /** 额外索引词，便于搜索栏命中场景/功能 */
    val keywords: String = "",
) {
    val searchText: String
        get() = "$id $name $author $version $description $source $keywords $fileName"
}

data class StepTemplate(
    val op: String,
    val title: String,
    val description: String,
    val fields: List<String>,
    val keywords: String,
    /** 骨架步骤：不含任何坐标、模板图或臆造的业务文字 */
    val step: PackStep,
) {
    val searchText: String
        get() = "$op $title $description $keywords ${fields.joinToString(" ")}"
}

object TaskPackTemplateLibrary {
    private const val ASSET_INDEX = "dev/templates/index.json"
    private const val ASSET_PACK_DIR = "dev/templates/packs"
    private const val DEMO_FILE = "demo-screen-scan-1.0.0.m7atask"
    private val parser = Json { ignoreUnknownKeys = true }

    /** 读取内置索引；解析失败时退回硬编码的文件清单，保证模板库永远可用。 */
    fun loadPackEntries(context: Context): List<PackTemplateEntry> = runCatching {
        val text = context.assets.open(ASSET_INDEX).bufferedReader().use { it.readText() }
        val root = parser.parseToJsonElement(text).jsonObject
        val packs = root["packs"] as? JsonArray ?: JsonArray(emptyList())
        val entries = packs.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.str("urlFallback").ifBlank { obj.str("url") }
            val file = url.substringAfterLast('/').takeIf { it.endsWith(".m7atask") }
                ?: return@mapNotNull null
            PackTemplateEntry(
                id = obj.str("id"),
                name = obj.str("name"),
                author = obj.str("author"),
                version = obj.str("version"),
                description = obj.str("description"),
                stepCount = obj["stepCount"]?.jsonPrimitive?.intOrNull ?: 0,
                fileName = file,
                source = "site/taskpacks",
                // 把包里实际用到的 op 也收进索引：搜索 "ifCount" 能直接找到用过它的模板包。
                keywords = obj.str("id").replace('.', ' ') + " " + collectOps(context, file),
            )
        }.toMutableList()
        // 仓库 examples/ 的开箱自测包不在站点索引里，单独补上。
        entries += PackTemplateEntry(
            id = "demo-screen-scan",
            name = "示例·画面体检",
            author = "March7thAssistant",
            version = "1.0.0",
            description = "把 24 个原语挨个用一遍：模板、颜色计数、变量表达式、repeat、whileExpr、OCR 读文字、optional 跳过。只有一次点击会动到游戏。",
            stepCount = 24,
            fileName = DEMO_FILE,
            source = "examples",
            keywords = "示例 demo 自检 全流程 原语 表达式 模板 计数 " +
                collectOps(context, DEMO_FILE),
        )
        entries.filter { context.assets.hasPack(it.fileName) }
    }.getOrElse {
        // 极端情况下索引损坏：仍列出所有内置包名，避免整个模板库空白。
        context.assets.list(ASSET_PACK_DIR).orEmpty()
            .filter { it.endsWith(".m7atask") }
            .map { file ->
                PackTemplateEntry(
                    id = file.removeSuffix(".m7atask"),
                    name = file.removeSuffix(".m7atask"),
                    author = "", version = "", description = "内置任务包模板",
                    stepCount = 0, fileName = file, source = ASSET_PACK_DIR,
                )
            }
    }

    /** 从 assets 原样解出整包（含模板图），不改写坐标与图片。 */
    fun loadPack(context: Context, entry: PackTemplateEntry): TaskPackProject =
        context.assets.open("$ASSET_PACK_DIR/${entry.fileName}").buffered().use { input ->
            TaskPackArchive.read(input, BuildConfig.VERSION_CODE, validate = false)
        }

    private fun android.content.res.AssetManager.hasPack(name: String): Boolean = runCatching {
        open("$ASSET_PACK_DIR/$name").close(); true
    }.getOrDefault(false)

    /** 只读包内 task.json 收集 op 名，用做搜索索引；不解码任何模板图。 */
    private fun collectOps(context: Context, fileName: String): String = runCatching {
        val ops = LinkedHashSet<String>()
        context.assets.open("$ASSET_PACK_DIR/$fileName").buffered().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "task.json") {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        Regex("\"op\"\\s*:\\s*\"([^\"]+)\"").findAll(text)
                            .forEach { ops += it.groupValues[1] }
                    }
                }
            }
        }
        ops.joinToString(" ")
    }.getOrDefault("")

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** 24 个 op 的完整字段骨架。坐标、模板图、业务文字一律留空，由开发者填写。 */
    val STEP_TEMPLATES: List<StepTemplate> = listOf(
        StepTemplate("log", "写一行日志", "调试期进度标记，只在日志里输出 text。",
            listOf("text"), "日志 调试 log 打印", PackStep(op = "log", note = "模板：填写日志文字")),
        StepTemplate("wait", "固定等待", "死等 ms 毫秒（1~60000）。能用 waitText/waitTemplate 就别用。",
            listOf("ms"), "等待 延时 sleep 死等", PackStep(op = "wait", ms = 1000, note = "模板：固定等待，建议改用等待文字")),
        StepTemplate("tap", "点击坐标", "按 0~1 归一化坐标点一下，durationMs 默认 60。",
            listOf("x", "y", "durationMs"), "点击 坐标 tap 触摸", PackStep(op = "tap", note = "模板：请从实时画面取 x / y")),
        StepTemplate("longPress", "长按坐标", "长按，durationMs 默认 800。",
            listOf("x", "y", "durationMs"), "长按 按住 longPress", PackStep(op = "longPress", note = "模板：请从实时画面取 x / y")),
        StepTemplate("swipe", "滑动", "从 fromX/fromY 滑到 toX/toY，durationMs 默认 320。",
            listOf("fromX", "fromY", "toX", "toY", "durationMs"), "滑动 拖拽 swipe 滚动",
            PackStep(op = "swipe", note = "模板：请从实时画面取起点与终点")),
        StepTemplate("waitTemplate", "等待模板出现", "等到 image 模板出现；建议填 region 缩小搜索面积。",
            listOf("image", "timeoutMs", "threshold", "region", "stable"), "模板 等待 图片 waitTemplate 识别",
            PackStep(op = "waitTemplate", note = "模板：请先框选模板图并填 image")),
        StepTemplate("tapTemplate", "点击模板", "等到模板出现并点它中心，可用 offsetX/offsetY 偏移。",
            listOf("image", "timeoutMs", "threshold", "region", "offsetX", "offsetY"), "模板 点击 图片 tapTemplate 偏移",
            PackStep(op = "tapTemplate", note = "模板：请先框选模板图并填 image")),
        StepTemplate("assertTemplate", "断言模板在场", "起点自检：模板不在就直接失败，避免乱点。",
            listOf("image", "threshold", "region"), "模板 断言 自检 assertTemplate 前置",
            PackStep(op = "assertTemplate", note = "模板：请先框选模板图并填 image")),
        StepTemplate("waitText", "等待文字出现", "OCR 包含匹配等待，建议填 region。",
            listOf("text", "timeoutMs", "region"), "文字 等待 OCR waitText",
            PackStep(op = "waitText", note = "模板：填写要等待的文字")),
        StepTemplate("tapText", "点击文字", "OCR 找到文字并点它，无需坐标；录制默认生成这个 op。",
            listOf("text", "timeoutMs", "region"), "文字 点击 OCR tapText 文字点击",
            PackStep(op = "tapText", note = "模板：填写要点击的文字")),
        StepTemplate("assertText", "断言文字在场", "起点自检：文字不在就失败。",
            listOf("text", "region"), "文字 断言 OCR assertText 自检",
            PackStep(op = "assertText", note = "模板：填写必须出现的文字")),
        StepTemplate("readNumber", "OCR 读整数", "读 region 里第一个整数，存进 to 变量。",
            listOf("region", "to", "timeoutMs"), "OCR 数字 读变量 readNumber 层数",
            PackStep(op = "readNumber", note = "模板：填写 region 与变量名 to")),
        StepTemplate("readText", "OCR 读整段文字", "读 region 全部文字拼成一行，存进 to 变量。",
            listOf("region", "to"), "OCR 文字 读变量 readText",
            PackStep(op = "readText", note = "模板：填写 region 与变量名 to")),
        StepTemplate("ifCount", "数颜色块走分支", "数 region 内指定颜色连通块，按 compare/value 判断走 then 或 else。",
            listOf("region", "color", "value", "compare", "tolerance", "minArea", "maxArea", "then", "else"),
            "颜色 计数 星 红点 分支 ifCount 连通块",
            PackStep(op = "ifCount", note = "模板：填写 region/color/value，再编辑分支",
                then = listOf(PackStep(op = "log", text = "分支占位：请替换")))),
        StepTemplate("assertCount", "断言颜色块数量", "数量不符合预期就判失败（无分支）。",
            listOf("region", "color", "value", "compare", "tolerance", "minArea", "maxArea"),
            "颜色 计数 断言 assertCount 连通块",
            PackStep(op = "assertCount", note = "模板：填写 region/color/value")),
        StepTemplate("waitColor", "等待颜色占比", "等区域里目标色面积占比达到 ratio（默认 0.02）。",
            listOf("color", "region", "ratio", "tolerance", "timeoutMs"),
            "颜色 占比 等待 waitColor 高亮",
            PackStep(op = "waitColor", note = "模板：填写 color 与 ratio")),
        StepTemplate("swipeUntil", "滑到目标出现", "反复滑动直到 image 或 text 出现，最多 maxTries 次（≤30）。",
            listOf("image", "text", "fromX", "fromY", "toX", "toY", "maxTries", "intervalMs", "region"),
            "滑动 直到 找列表 swipeUntil 翻页",
            PackStep(op = "swipeUntil", note = "模板：填写起点终点与目标 image/text")),
        StepTemplate("repeat", "重复子步骤", "子步骤重复 times 次（≤500，可引用 {param}），可嵌套。",
            listOf("times", "steps"), "循环 重复 repeat 次数",
            PackStep(op = "repeat", times = JsonPrimitive(1),
                steps = listOf(PackStep(op = "log", text = "子步骤占位：请替换")),
                note = "模板：填写 times 并编辑子步骤")),
        StepTemplate("ifTemplate", "模板条件分支", "模板在 → then，否则 → else。",
            listOf("image", "threshold", "region", "then", "else"), "模板 条件 分支 ifTemplate",
            PackStep(op = "ifTemplate", note = "模板：填写 image 并编辑分支",
                then = listOf(PackStep(op = "log", text = "分支占位：请替换")))),
        StepTemplate("ifText", "文字条件分支", "OCR 文字在 → then，否则 → else。",
            listOf("text", "region", "then", "else"), "文字 条件 分支 ifText OCR",
            PackStep(op = "ifText", note = "模板：填写 text 并编辑分支",
                then = listOf(PackStep(op = "log", text = "分支占位：请替换")))),
        StepTemplate("setVar", "设置变量", "把 expr 表达式结果存进 to 变量。",
            listOf("to", "expr"), "变量 表达式 setVar 赋值",
            PackStep(op = "setVar", note = "模板：填写 to 与 expr")),
        StepTemplate("ifExpr", "表达式条件分支", "expr 为真走 then，否则走 else。",
            listOf("expr", "then", "else"), "表达式 条件 分支 ifExpr",
            PackStep(op = "ifExpr", note = "模板：填写 expr 并编辑分支",
                then = listOf(PackStep(op = "log", text = "分支占位：请替换")))),
        StepTemplate("whileExpr", "表达式循环", "expr 为真就循环 steps，单次最多 200 轮。",
            listOf("expr", "steps"), "表达式 循环 whileExpr",
            PackStep(op = "whileExpr", note = "模板：填写 expr 并编辑子步骤",
                steps = listOf(PackStep(op = "log", text = "子步骤占位：请替换")))),
        StepTemplate("stop", "提前结束", "结束任务，ok 默认 true；用于「本层已满星，跳过」。",
            listOf("ok"), "结束 停止 stop 跳过",
            PackStep(op = "stop", note = "模板：提前结束任务")),
    )

    /** 搜索：空格/逗号分词，所有词都要命中（大小写不敏感），空串返回全部。 */
    fun matches(query: String, text: String): Boolean {
        val tokens = query.trim().split(Regex("[\\s,，、]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val haystack = text.lowercase()
        return tokens.all { haystack.contains(it.lowercase()) }
    }
}
