package com.march7th.hkrpg.dev

import android.content.Context
import android.graphics.Bitmap
import com.march7th.hkrpg.starrail.engine.ImageUtils
import com.march7th.hkrpg.starrail.engine.OcrEngine
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Rect
import timber.log.Timber

/**
 * 录制器的 App 进程 OCR。模型和引擎都复用原项目内置实现。
 *
 * ## 为什么必须分块 / 局部识别
 *
 * 原引擎 `OcrEngine.detect()` 会把最长边超过 960px 的图**整体等比缩小到 960**。
 * 横屏游戏帧通常是 2400×1080，缩完变成 960×432 —— 按钮上 24px 的文字只剩
 * 不到 10px，检测模型基本找不到框，这就是「点击位置根本 OCR 不到」的根因。
 *
 * 这里不改原引擎（保持与原项目执行器一致），而是在 dev 层：
 * - 点击取词 [textAt]：只对点击点周围、最长边 ≤900 的 ROI 做识别，完全不缩放；
 *   再补一次更小的紧邻 ROI（会触发引擎的放大逻辑），最后全帧低阈值兜底。
 * - 给 AI 的全屏文字 [readScreen]：把整帧切成最长边 ≤960 的小块逐块识别，
 *   合并去重，避免整帧缩小导致文字丢失。
 */
class TaskPackOcr(
    private val context: Context,
) {
    @Volatile
    private var initialized = false

    private fun ensureReady(): Boolean {
        if (initialized && OcrEngine.isReady) return true
        val ok = runCatching {
            if (!OpenCVLoader.initLocal()) return@runCatching false
            val cache = File(context.cacheDir, "taskpack_dev_ocr").apply { mkdirs() }
            OcrEngine.setCacheDir(cache)
            OcrEngine.init(context.assets, OcrEngine.ASSET_PREFIX)
        }.onFailure { Timber.w(it, "task-pack OCR init failed") }.getOrDefault(false)
        initialized = ok
        return ok
    }

    /**
     * 找点击位置上的文字。三级策略，尽量把「点到了什么」写准，又不误抓旁边说明：
     * 1) 点击点 ROI（900×600，不缩放）→ 点落在文本框内（含触控边距）；
     * 2) 紧邻 ROI（560×380，触发放大）→ 同上 + 最近文本框；
     * 3) 全帧低阈值 → 最近文本框，仍限制最大距离。
     * 返回的文字已去空格，供 note 使用。
     */
    suspend fun textAt(frame: Bitmap, normalizedX: Double, normalizedY: Double): String? =
        withContext(Dispatchers.IO) {
            if (!ensureReady()) return@withContext null
            runCatching {
                val mat = ImageUtils.bitmapToMat(frame)
                try {
                    val px = (normalizedX * frame.width).roundToInt().coerceIn(0, frame.width - 1)
                    val py = (normalizedY * frame.height).roundToInt().coerceIn(0, frame.height - 1)
                    val padding = max(14, (frame.width * 0.015).roundToInt())
                    val maxDist = min(frame.width, frame.height) * 0.15

                    val main = centeredRoi(px, py, frame.width, frame.height, 900, 600)
                    pick(OcrEngine.run(mat, main), px, py, padding, maxDist)?.let {
                        return@withContext it
                    }
                    val tight = centeredRoi(px, py, frame.width, frame.height, 560, 380)
                    pick(OcrEngine.run(mat, tight), px, py, padding, maxDist * 0.8)?.let {
                        return@withContext it
                    }
                    pick(
                        OcrEngine.run(mat), px, py, padding * 2, maxDist * 1.5,
                        scoreFloor = 0.28f,
                    )
                } finally {
                    mat.release()
                }
            }.onFailure { Timber.w(it, "task-pack OCR failed") }.getOrNull()
        }

    /** 拉框 OCR 的返回：识别到的每一行文字，以及拼好的整段文本。 */
    data class RegionText(val lines: List<String>, val text: String)

    /**
     * 只识别用户框选的 region（归一化 [x,y,w,h]）里的文字，供「拉框 OCR 文字」使用。
     *
     * 与 [readScreen] 一样分块，但块只覆盖 ROI 且最长边 ≤900，避免整帧缩小把小字抹掉；
     * 另外对小 ROI 会先放大 2 倍再识别，明显提升按钮小字的召回。
     */
    suspend fun readRegion(frame: Bitmap, region: List<Double>): RegionText =
        withContext(Dispatchers.IO) {
            if (!ensureReady() || region.size != 4) return@withContext RegionText(emptyList(), "")
            runCatching {
                val x = (region[0].coerceIn(0.0, 1.0) * frame.width).roundToInt()
                    .coerceIn(0, frame.width - 1)
                val y = (region[1].coerceIn(0.0, 1.0) * frame.height).roundToInt()
                    .coerceIn(0, frame.height - 1)
                val r = ((region[0] + region[2]).coerceIn(0.0, 1.0) * frame.width).roundToInt()
                    .coerceIn(x + 1, frame.width)
                val b = ((region[1] + region[3]).coerceIn(0.0, 1.0) * frame.height).roundToInt()
                    .coerceIn(y + 1, frame.height)
                val crop = Bitmap.createBitmap(frame, x, y, r - x, b - y)
                try {
                    // 小区域放大后再识别，引擎内部最长边限制 960，放大后仍不超限。
                    val scale = if (max(crop.width, crop.height) < 420) 2.0 else 1.0
                    val work = if (scale > 1.0) {
                        Bitmap.createScaledBitmap(
                            crop, (crop.width * scale).roundToInt(), (crop.height * scale).roundToInt(), true
                        )
                    } else crop
                    val seen = LinkedHashSet<String>()
                    try {
                        val mat = ImageUtils.bitmapToMat(work)
                        try {
                            for (tile in tiles(work.width, work.height)) {
                                for (res in OcrEngine.run(mat, tile)) {
                                    val text = res.normalized()
                                    if (text.isNotBlank() && res.score >= 0.28f) seen += text
                                }
                            }
                        } finally {
                            mat.release()
                        }
                    } finally {
                        if (work !== crop) work.recycle()
                    }
                    val lines = seen.toList()
                    RegionText(lines, lines.joinToString("\n").take(500))
                } finally {
                    crop.recycle()
                }
            }.onFailure { Timber.w(it, "task-pack readRegion failed") }
                .getOrDefault(RegionText(emptyList(), ""))
        }

    /**
     * 给 AI 的当前画面文字：整帧分块识别后合并去重。
     * 分块只为保证每块最长边 ≤960，不被引擎整体缩小。
     */
    suspend fun readScreen(frame: Bitmap): String = withContext(Dispatchers.IO) {
        if (!ensureReady()) return@withContext ""
        runCatching {
            val mat = ImageUtils.bitmapToMat(frame)
            try {
                val seen = LinkedHashSet<String>()
                for (tile in tiles(frame.width, frame.height)) {
                    for (r in OcrEngine.run(mat, tile)) {
                        val text = r.normalized()
                        if (text.isNotBlank() && r.score >= 0.32f) seen.add(text)
                    }
                }
                seen.joinToString("\n").take(4_000)
            } finally {
                mat.release()
            }
        }.onFailure { Timber.w(it, "task-pack readScreen failed") }.getOrDefault("")
    }

    /** 环境页/AI 页自检：把 OCR 是否就绪、各路识别条数直接摆出来，方便排查「识别不到」。 */
    suspend fun diagnose(frame: Bitmap): String = withContext(Dispatchers.IO) {
        buildString {
            val ready = ensureReady()
            appendLine("OCR 引擎：${if (ready) "已就绪" else "未就绪（OpenCV 或模型加载失败）"}")
            appendLine("画面尺寸：${frame.width}×${frame.height}")
            if (!ready) return@buildString
            val mat = ImageUtils.bitmapToMat(frame)
            try {
                val tileList = tiles(frame.width, frame.height)
                val fullCount = OcrEngine.run(mat).size
                var tileCount = 0
                for (tile in tileList) tileCount += OcrEngine.run(mat, tile).size
                appendLine("分块方案：${tileList.size} 块，每块最长边 ≤960，不整帧缩小")
                appendLine("整帧识别条数：$fullCount（横屏大图被缩到 960，偏少属正常）")
                appendLine("分块识别条数：$tileCount（给 AI 的文字上下文以此为准）")
                appendLine("点击取词：主 ROI 900×600 → 紧邻 ROI 560×380（放大）→ 全帧低阈值回退")
                if (tileCount == 0) {
                    appendLine("诊断：未识别到任何文字。请确认 Shizuku 已授权、当前帧确实是游戏画面，且画面不是纯黑/加载中。")
                } else if (tileCount > fullCount * 2) {
                    appendLine("诊断：分块明显优于整帧，说明之前整帧缩小正是识别不到的原因，现已修复。")
                }
            } finally {
                mat.release()
            }
        }
    }

    /**
     * 从结果里挑点击点对应的文字。先在「点落在框内」的候选里取面积最小/中心最近的，
     * 再退化到「到框边界距离」最近的文本框，并限制最大距离避免抓到远处说明。
     */
    private fun pick(
        results: List<OcrEngine.OcrResult>,
        px: Int,
        py: Int,
        padding: Int,
        maxDist: Double,
        scoreFloor: Float = 0.35f,
    ): String? {
        val valid = results.filter { it.score >= scoreFloor && it.normalized().isNotBlank() }
        if (valid.isEmpty()) return null
        val inside = valid.filter {
            px >= it.x - padding && px <= it.x + it.w + padding &&
                py >= it.y - padding && py <= it.y + it.h + padding
        }
        val insideBest = inside.sortedWith(
            compareBy<OcrEngine.OcrResult> { it.w * it.h }
                .thenBy { abs(it.cx - px) + abs(it.cy - py) }
        ).firstOrNull()
        if (insideBest != null) return insideBest.normalized().take(60)

        val nearest = valid.mapNotNull { r ->
            val dx = max(0, max(r.x - px, px - (r.x + r.w)))
            val dy = max(0, max(r.y - py, py - (r.y + r.h)))
            val d = hypot(dx.toDouble(), dy.toDouble())
            if (d <= maxDist) r to d else null
        }.minByOrNull { it.second }?.first
        return nearest?.normalized()?.take(60)
    }

    /** 以 (px,py) 为中心裁一块不超过 [wantW]×[wantH] 的 ROI，保证不出界。 */
    private fun centeredRoi(px: Int, py: Int, width: Int, height: Int, wantW: Int, wantH: Int): Rect {
        val w = min(width, wantW)
        val h = min(height, wantH)
        val x = (px - w / 2).coerceIn(0, width - w)
        val y = (py - h / 2).coerceIn(0, height - h)
        return Rect(x, y, w, h)
    }

    /** 把整帧切成最长边 ≤960 的块，块间留 24px 重叠，避免切断跨块文字。 */
    private fun tiles(width: Int, height: Int): List<Rect> {
        val cols = max(1, ceil(width / 900.0).toInt())
        val rows = max(1, ceil(height / 900.0).toInt())
        val cw = ceil(width / cols.toDouble()).toInt()
        val ch = ceil(height / rows.toDouble()).toInt()
        val overlap = 24
        return buildList {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val x = (c * cw - overlap).coerceAtLeast(0)
                    val y = (r * ch - overlap).coerceAtLeast(0)
                    val x2 = ((c + 1) * cw + overlap).coerceAtMost(width)
                    val y2 = ((r + 1) * ch + overlap).coerceAtMost(height)
                    add(Rect(x, y, x2 - x, y2 - y))
                }
            }
        }
    }
}
