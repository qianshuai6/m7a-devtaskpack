package com.march7th.hkrpg.dev

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.march7th.hkrpg.starrail.engine.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Rect
import timber.log.Timber

/**
 * 图标模板候选的**纯逻辑**筛选。
 *
 * 一次点击要在 410 个图标里做模板匹配是不现实的（每个 ROI 匹配一次就要几毫秒，
 * 加起来几秒），所以先用「点位上 OCR 到的文字」把候选缩到十几个：
 * 点到「开始挑战」时，名字/关键词里含「挑战」「开始」「start」的图标才值得算相关系数。
 */
object TaskPackIconCandidates {
    /** 模板匹配只考虑这些尺寸区间的图标：太小是装饰、太大是整块面板。 */
    private const val MIN_SIDE = 16
    private const val MAX_SIDE = 220

    fun rank(
        entries: List<IconTemplateEntry>,
        hints: List<String>,
        limit: Int = 24,
    ): List<IconTemplateEntry> {
        val needles = hints.map { normalize(it) }.filter { it.length >= 1 }.distinct()
        val scored = entries.asMapSized().mapNotNull { (entry, side) ->
            if (side !in MIN_SIDE..MAX_SIDE) return@mapNotNull null
            val text = normalize(entry.name + " " + entry.keywords + " " + entry.category)
            var score = when {
                needles.isEmpty() -> 0
                needles.any { it == normalize(entry.name) } -> 100
                needles.any { text.contains(it) } -> 80
                needles.any { it.length >= 2 && it.contains(normalize(entry.name)) } -> 60
                else -> 0
            }
            if (needles.isEmpty()) {
                // 没有任何文字线索时，优先「按钮尺寸」的图标，避免把立绘/背景图丢进匹配。
                score = if (side in 32..120) 10 else 0
            } else if (score == 0) {
                return@mapNotNull null
            }
            if (side in 32..120) score += 5
            entry to score
        }
        return scored
            .sortedWith(compareByDescending<Pair<IconTemplateEntry, Int>> { it.second }
                .thenBy { it.first.width.toLong() * it.first.height }
                .thenBy { it.first.id })
            .take(limit)
            .map { it.first }
    }

    private fun List<IconTemplateEntry>.asMapSized(): List<Pair<IconTemplateEntry, Int>> =
        map { it to maxOf(it.width, it.height) }

    /** 去掉空白与标点，避免「开始 挑战！」和「开始挑战」匹配不上。 */
    fun normalize(raw: String): String = raw.lowercase().filter { it.isLetterOrDigit() }
}

/**
 * 在点击点附近做图标模板匹配，返回最像的几个模板。
 *
 * 全部开销都在后台协程里：录制主链只记坐标，匹配结果稍后回填到那条动作上。
 * 匹配失败（OpenCV 未初始化、图太大、没候选）一律返回空列表，不影响录制本身。
 */
class TaskPackIconMatcher(
    private val context: Context,
    private val entries: () -> List<IconTemplateEntry>,
) {
    private val bitmaps = mutableMapOf<String, Bitmap>()
    /** 按「资源 + 缩放比」缓存 Mat：模板匹配每次都要 Mat，重复解码太浪费。 */
    private val mats = mutableMapOf<String, Mat>()

    suspend fun hits(
        frame: Bitmap,
        x: Double,
        y: Double,
        hints: List<String>,
        limit: Int = 3,
    ): List<AiTemplateHit> = withContext(Dispatchers.Default) {
        runCatching {
            if (!OpenCVLoader.initLocal()) return@runCatching emptyList<AiTemplateHit>()
            val candidates = TaskPackIconCandidates.rank(entries(), hints, limit = 24)
            if (candidates.isEmpty()) return@runCatching emptyList<AiTemplateHit>()
            // 帧可能是 720 / 1280 缩放图，而图标资源是按 1920 基准做的，按比例一起缩。
            val ratio = (frame.width.toDouble() / 1920.0).coerceIn(0.2, 2.0)
            val px = (x * frame.width).toInt()
            val py = (y * frame.height).toInt()
            val half = (170 * ratio).toInt().coerceAtLeast(48)
            val left = (px - half).coerceIn(0, (frame.width - 2).coerceAtLeast(0))
            val top = (py - half).coerceIn(0, (frame.height - 2).coerceAtLeast(0))
            val width = (half * 2).coerceAtMost(frame.width - left)
            val height = (half * 2).coerceAtMost(frame.height - top)
            if (width < 32 || height < 32) return@runCatching emptyList<AiTemplateHit>()

            val full = ImageUtils.bitmapToMat(frame)
            val roi = Mat(full, Rect(left, top, width, height))
            try {
                candidates.mapNotNull { entry ->
                    val template = templateMat(entry, ratio) ?: return@mapNotNull null
                    if (template.rows() >= roi.rows() || template.cols() >= roi.cols()) return@mapNotNull null
                    val match = ImageUtils.match(roi, template, null, 0.68, scaleRange = null)
                        ?: return@mapNotNull null
                    // 点击点必须落在命中框内（留一圈容差），否则只是「旁边有个图标」。
                    val mx = match.x + match.templateWidth / 2.0
                    val my = match.y + match.templateHeight / 2.0
                    val tolerance = maxOf(match.templateWidth, match.templateHeight) * 0.9
                    val centerX = (px - left).toDouble()
                    val centerY = (py - top).toDouble()
                    if (kotlin.math.abs(mx - centerX) > tolerance ||
                        kotlin.math.abs(my - centerY) > tolerance
                    ) {
                        return@mapNotNull null
                    }
                    AiTemplateHit(entry.packPath, entry.name, match.score)
                }.sortedByDescending { it.score }.take(limit)
            } finally {
                roi.release()
                full.release()
            }
        }.onFailure { Timber.w(it, "task-pack icon match failed") }.getOrDefault(emptyList())
    }

    private fun templateMat(entry: IconTemplateEntry, ratio: Double): Mat? {
        val key = "${entry.asset}#${(ratio * 100).toInt()}"
        mats[key]?.let { return if (it.empty()) null else it }
        val base = bitmaps.getOrPut(entry.asset) {
            runCatching {
                context.assets.open(entry.asset).use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return null
        }
        if (base.isRecycled) return null
        val w = (base.width * ratio).toInt().coerceAtLeast(8)
        val h = (base.height * ratio).toInt().coerceAtLeast(8)
        val resized = if (w == base.width && h == base.height) {
            base
        } else {
            runCatching { Bitmap.createScaledBitmap(base, w, h, true) }.getOrNull() ?: return null
        }
        val mat = ImageUtils.bitmapToMat(resized)
        if (resized !== base) resized.recycle()
        if (mats.size > 400) {
            mats.values.forEach { runCatching { it.release() } }
            mats.clear()
        }
        if (bitmaps.size > 260) bitmaps.clear()
        mats[key] = mat
        return mat
    }
}
