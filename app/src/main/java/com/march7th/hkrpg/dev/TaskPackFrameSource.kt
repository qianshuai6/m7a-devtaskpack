package com.march7th.hkrpg.dev

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.manager.RemoteServiceManager
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 开发者录制器取帧：复用原项目已有的 `captureFramePng` 与调试文件读取能力。
 * 不修改 AIDL，也不在特权进程增加新接口。
 */
class TaskPackFrameSource(
    private val pathConfig: CorePathConfig,
) {
    suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val service = RemoteServiceManager.getInstanceOrNull() ?: return@runCatching null
            val saved = service.captureFramePng(pathConfig.coreDebugScreenshotsDir)
                ?: return@runCatching null
            val file = File(saved)

            // App 目录模式下可直接读；core 分离时文件在 /data/local/tmp，走已有 PFD。
            BitmapFactory.decodeFile(saved)?.let { bitmap ->
                runCatching { file.delete() }
                return@runCatching bitmap
            }
            val pfd = service.openCoreDebugFile("screenshots/${file.name}")
                ?: return@runCatching null
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.onFailure { Timber.w(it, "TaskPackFrameSource.capture failed") }
            .getOrNull()
    }

    /** 抓一帧并按最长边缩放，供 UI 实时预览，避免全尺寸帧占内存。 */
    suspend fun captureScaled(maxSide: Int = 1280): Bitmap? = withContext(Dispatchers.IO) {
        val bitmap = capture() ?: return@withContext null
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return@withContext bitmap
        val ratio = maxSide.toDouble() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
        bitmap.recycle()
        scaled
    }

    /**
     * 抓取当前画面并编码成 base64 JPEG，供「AI 主动索取画面」时以 `image_url` 发送。
     * 缩放 + JPEG 压缩控制体积，避免一次请求塞进几 MB 的 base64。
     */
    suspend fun captureJpegBase64(maxSide: Int = 1280, quality: Int = 78): String? =
        withContext(Dispatchers.IO) {
            val bitmap = capture() ?: return@withContext null
            try {
                val longest = max(bitmap.width, bitmap.height)
                val scaled = if (longest > maxSide) {
                    val ratio = maxSide.toDouble() / longest
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                        (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    bitmap
                }
                val bytes = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
                if (scaled !== bitmap) scaled.recycle()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                bitmap.recycle()
            }
        }
}
