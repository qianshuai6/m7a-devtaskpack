package com.march7th.hkrpg.dev

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 图标模板库：把**仓库自带的全部图标**与**整包模板内的模板图**统一索引，供开发者
 * 搜索、预览、插入到任务包，也供 AI 按需索取。
 *
 * 图标全部来自仓库现有资源（或官方任务包里的真实素材），工具**不生成、不臆造**任何图标：
 * - `repo`：assets 下 starrail 的 images 目录共 410 个 PNG，本来就在 APK 里，零新增体积；
 * - `pack`：7 个内置 `.m7atask` 的 assets 目录里 26 张 PNG，构建时解压到
 *   dev 的 templates 的 icons 目录，同样原样保留。
 *
 * 插入到任务包时路径统一为 `assets/icon_<稳定 id>.png`，字节原样复制，不重新编码，
 * 因此与原版执行器的模板匹配行为一致。
 */
data class IconTemplateEntry(
    val id: String,
    val name: String,
    /** APK assets 内的源路径。 */
    val asset: String,
    /** 分类（目录名或整包短名），用于分组与搜索。 */
    val category: String,
    /** "仓库图标" / "整包模板图"。 */
    val group: String,
    /** "repo" / "pack"。 */
    val source: String,
    val keywords: String,
    val width: Int,
    val height: Int,
) {
    /** 写入任务包时的固定路径，AI 与 UI 都引用它，保证校验器能识别。 */
    val packPath: String
        get() = "assets/icon_$id.png"

    val searchText: String
        get() = "$id $name $category $group $keywords $asset"

    val subtitle: String
        get() = "$group · $category · ${width}×${height}"
}

object TaskPackIconTemplates {
    private const val INDEX = "dev/templates/icons.json"
    private val parser = Json { ignoreUnknownKeys = true }

    /** 读取内置索引；解析失败返回空列表，不影响其它模板。 */
    fun load(context: Context): List<IconTemplateEntry> = runCatching {
        val text = context.assets.open(INDEX).bufferedReader().use { it.readText() }
        val root = parser.parseToJsonElement(text).jsonObject
        val arr = root["icons"] as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val str = { key: String -> obj[key]?.jsonPrimitive?.contentOrNull.orEmpty() }
            val asset = str("asset")
            if (asset.isBlank()) return@mapNotNull null
            val name = str("name")
            val baseKeywords = str("keywords")
            // 角色图标追加官方中文译名（黄泉/砂金/流萤…），中文也能搜到。
            val cn = TaskPackCharacterNames.chineseKeywords(asset, name)
            val keywords = if (cn.isBlank()) baseKeywords else "$baseKeywords $cn"
            IconTemplateEntry(
                id = str("id"),
                name = name,
                asset = asset,
                category = str("category"),
                group = str("group"),
                source = str("source"),
                keywords = keywords,
                width = obj["w"]?.jsonPrimitive?.intOrNull ?: 0,
                height = obj["h"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.filter { context.assets.hasAsset(it.asset) }
    }.getOrDefault(emptyList())

    /** 原样读取图标字节，用于插入工程或发给 AI；不做缩放、不重新编码。 */
    fun bytes(context: Context, entry: IconTemplateEntry): ByteArray? = runCatching {
        context.assets.open(entry.asset).use { it.readBytes() }
    }.getOrNull()

    /** 缩略图（最长边压到 96px），仅用于模板库列表展示，不影响写入包的原图。 */
    fun thumbnail(context: Context, entry: IconTemplateEntry): ImageBitmap? = runCatching {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(entry.asset).use { BitmapFactory.decodeStream(it, null, probe) }
        val longest = maxOf(probe.outWidth, probe.outHeight).coerceAtLeast(1)
        val sample = (longest / 96).coerceAtLeast(1).let { n ->
            generateSequence(1) { it * 2 }.takeWhile { it < n }.lastOrNull() ?: 1
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.assets.open(entry.asset).use { BitmapFactory.decodeStream(it, null, opts) }
        bitmap?.asImageBitmap()
    }.getOrNull()

    /** 复用模板库的分词匹配规则。 */
    fun matches(query: String, entry: IconTemplateEntry): Boolean =
        TaskPackTemplateLibrary.matches(query, entry.searchText)

    /**
     * 注入 AI 对话的**索引摘要**：按分组/分类列出数量与代表图名，控制长度，
     * 让模型知道有哪些图标可用、按什么关键词索取，但不把 436 个全路径一次性塞进 prompt。
     */
    fun aiIndex(entries: List<IconTemplateEntry>): String = buildString {
        appendLine("图标模板库共 ${entries.size} 个，来自仓库现有图标与内置整包模板图，均为真实素材。")
        val byGroup = entries.groupBy { it.group }
        byGroup.forEach { (group, list) ->
            appendLine("【$group】${list.size} 个")
            list.groupBy { it.category }.toSortedMap().forEach { (cat, items) ->
                val names = items.take(6).joinToString("、") { it.name }
                appendLine("  $cat（${items.size}）：$names${if (items.size > 6) " …" else ""}")
            }
        }
        appendLine("可用 packPath 形如 assets/icon_<id>.png；不要臆造文件名，必须先索取拿到确切路径。")
    }

    /**
     * AI 按需索取：按关键词检索，返回若干条目的**确切 packPath + 中文说明**，
     * 并给出可直接照抄的 tapTemplate 字段骨架。命中的图标字节由调用方注册进工程。
     */
    fun lookup(entries: List<IconTemplateEntry>, query: String, limit: Int = 8): List<IconTemplateEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val exact = entries.filter { matches(q, it) }
        val source = if (exact.isNotEmpty()) exact else {
            // 退化为「任一 token 命中」，避免模型用了稍宽的关键词就一无所获。
            val tokens = q.split(Regex("[\\s,，、]+")).filter { it.isNotBlank() }
            entries.filter { e -> tokens.any { matches(it, e) } }
        }
        return source.take(limit)
    }

    private fun android.content.res.AssetManager.hasAsset(path: String): Boolean = runCatching {
        open(path).close(); true
    }.getOrDefault(false)
}
