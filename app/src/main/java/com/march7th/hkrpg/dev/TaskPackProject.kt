package com.march7th.hkrpg.dev

import com.march7th.hkrpg.data.model.CustomTaskPack
import com.march7th.hkrpg.data.model.PackStep
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * 任务包命名约定。
 *
 * `x.x-y.y`：前段是任务包版本，后段是崩铁游戏版本（旧包 `x.x.y.y` 仍可解析）。
 * 例如 `1.2.4.6` 表示任务包 1.2，面向游戏 4.6。
 * 最终写进原格式已有的 `version` 字段，不增加任何清单字段。
 */
object TaskPackNaming {
    private val TWO_PART = Regex("^\\d+\\.\\d+$")
    /** 现行格式：任务包版本-游戏版本，例如 `1.2-4.7`。 */
    private val PACK_GAME = Regex("^\\d+\\.\\d+-\\d+\\.\\d+$")
    /** 旧格式 `1.2.4.7`，导入历史包时仍要能解析。 */
    private val LEGACY_FOUR = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
    /** 三段式（如 `1.0.0`）：不是 `x.x-y.y`，只当任务包版本。 */
    private val THREE_PART = Regex("^\\d+\\.\\d+\\.\\d+$")
    /** 任务包版本栏的宽松格式：点分段，允许后面再跟一段 `-y.y`。 */
    private val PACK_ONLY = Regex("^\\d+(\\.\\d+)*(-\\d+(\\.\\d+)*)?$")

    /**
     * 组合成最终写入 `version` 的字符串。
     *
     * - 游戏版本为空 → 只写任务包版本，**不补短横线**（`1.0.0` 就是 `1.0.0`）；
     * - 任务包版本栏里已经是 `1.2-4.7` 这种完整版本 → 原样使用，不会再拼成 `1.2-4.7-4.7`。
     */
    fun version(packVersion: String, gameVersion: String): String {
        val pack = packVersion.trim().trim('-').trim()
        val game = gameVersion.trim().trim('-').trim()
        if (pack.isEmpty()) return game
        if (game.isEmpty() || pack.contains('-')) return pack
        return "$pack-$game"
    }

    fun isValidTwoPart(value: String): Boolean = TWO_PART.matches(value.trim())

    /** 任务包版本栏可接受的写法：`1.2`、`1.0.0`、`1.2-4.7` 都行，只要不是空。 */
    fun isValidPackVersion(value: String): Boolean {
        val v = value.trim()
        return v.isNotEmpty() && PACK_ONLY.matches(v)
    }

    /**
     * AI 回填用的宽松校验：`1.2-4.7`、旧 `1.2.4.7`、只写任务包版本的 `1.0.0` / `1.2` 都算合法。
     */
    fun isValidVersion(value: String): Boolean {
        val v = value.trim()
        return v.isNotEmpty() && PACK_ONLY.matches(v)
    }

    /** 解析出（任务包版本, 游戏版本）。优先 `1.2-4.7`，兼容旧的 `1.2.4.7`。 */
    fun splitVersion(version: String): Pair<String, String>? {
        val v = version.trim()
        val dashed = v.split('-')
        if (dashed.size == 2) {
            val pack = dashed[0].trim()
            val game = dashed[1].trim()
            if (TWO_PART.matches(pack) && TWO_PART.matches(game)) return pack to game
        }
        val parts = v.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return null
        return parts.take(2).joinToString(".") to parts.drop(2).joinToString(".")
    }

    /**
     * 表单回填：把任意一个 `version` 拆进「任务包版本 / 游戏版本」两栏。
     *
     * 不是 `x.x-y.y`（也不是旧的四段式）时，只填任务包版本那一栏，游戏版本留空，
     * 例如 `1.0.0` → (`1.0.0`, "")。
     */
    fun fillForm(version: String): Pair<String, String> {
        val v = version.trim()
        splitVersion(v)?.let { return it }
        return v.trim('-').trim() to ""
    }

    /**
     * 用户在第一栏直接输入/粘贴了一整个版本号时，返回应写入两栏的结果。
     *
     * 返回 `null` 表示这只是任务包版本的中间态（如 `1.`、`1.2`），保持原样即可。
     */
    fun interpretWholeVersion(raw: String): Pair<String, String>? {
        val v = raw.trim()
        if (PACK_GAME.matches(v) || LEGACY_FOUR.matches(v)) return splitVersion(v)
        // 三段式不是「任务包-游戏」格式，按需求只认任务包版本，游戏版本清空。
        if (THREE_PART.matches(v)) return v to ""
        return null
    }

    fun archiveName(id: String, version: String): String =
        "${id.trim()}-${version.trim()}.m7atask"
}

/**
 * 待导出的任务包工程。`assets` 的键始终是从包根开始的路径，例如 `assets/entry.png`。
 *
 * 这里只做原项目已经支持的 `.m7atask`：ZIP 根目录一个 `task.json`，模板图在
 * `assets/` 下。没有新 op、新字段或新的执行接口。
 */
data class TaskPackProject(
    val pack: CustomTaskPack,
    val assets: Map<String, ByteArray> = emptyMap(),
) {
    companion object {
        private val OUTPUT_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        fun manifestJson(pack: CustomTaskPack): String =
            OUTPUT_JSON.encodeToString(CustomTaskPack.serializer(), pack)

        /** 收集整棵步骤树实际引用的素材，AI 改写后可据此去掉不再使用的图。 */
        fun referencedAssets(steps: List<PackStep>): Set<String> {
            val out = LinkedHashSet<String>()
            fun visit(items: List<PackStep>) {
                items.forEach { step ->
                    step.image?.takeIf { it.startsWith("assets/") }?.let(out::add)
                    step.steps?.let(::visit)
                    step.then?.let(::visit)
                    step.elseSteps?.let(::visit)
                }
            }
            visit(steps)
            return out
        }
    }

    fun manifestBytes(): ByteArray = manifestJson(pack).toByteArray(Charsets.UTF_8)

    fun toZipBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes, Charsets.UTF_8).use { zip ->
            zip.putNextEntry(ZipEntry("task.json"))
            zip.write(manifestBytes())
            zip.closeEntry()

            assets.toSortedMap().forEach { (path, content) ->
                require(isSafeAssetPath(path)) { "非法模板路径：$path" }
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun isSafeAssetPath(path: String): Boolean =
        path.startsWith("assets/") && !path.startsWith("/") &&
            path.split('/').none { it.isBlank() || it == ".." }
}

/** 任务包坐标统一保留四位小数，避免把浮点误差写进清单。 */
fun normCoordinate(value: Double): Double =
    String.format(Locale.US, "%.4f", value.coerceIn(0.0, 1.0)).toDouble()
