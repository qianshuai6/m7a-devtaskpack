package com.march7th.hkrpg.dev

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale

/**
 * 开发者版画面基准。
 *
 * ## 为什么要跟随真机分辨率
 *
 * 原项目虚拟屏固定 1920×1080（16:9），而全面屏手机横屏普遍是 2.2:1 左右
 * （本机 2712×1220 = 2.223:1）。ROM 给虚拟屏上的游戏做布局时会参考**物理屏比例**，
 * 于是 16:9 的内容被横向拉伸；再把这帧投到按 16:9 测量的 SurfaceView 上，
 * 就表现为「画面变形 + 上下/左右黑边 + 窗口小」。
 *
 * 根治办法是让**虚拟屏分辨率 = 真机物理分辨率**，画面 1:1 投到同尺寸预览，
 * 既铺满又不变形。归一化坐标仍是 0~1，与像素尺寸无关，因此不影响任务包格式，
 * 也不改执行器任何接口。
 */
object TaskPackDisplay {

    /** 真机物理分辨率（横屏：长边为宽）。取不到时用 1920×1080 兜底。 */
    fun deviceLandscape(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val (w, h) = if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(dm)
            if (dm.widthPixels > 0) dm.widthPixels to dm.heightPixels else 1920 to 1080
        }
        val long = maxOf(w, h).coerceAtLeast(640)
        val short = minOf(w, h).coerceAtLeast(360)
        return long to short
    }

    /** 与真机像素密度接近的 DPI，用于虚拟屏密度；异常值回落到 240。 */
    fun deviceDpi(context: Context): Int {
        val dpi = context.resources.displayMetrics.densityDpi
        return if (dpi in 120..640) dpi else 240
    }

    /** 画面角标文字。 */
    fun badge(width: Int, height: Int, mode: String): String =
        "$mode · ${width}×${height}"

    /** 把归一化 region 格式化成可直接粘贴进任务包的 JSON 数组文本。 */
    fun regionText(region: List<Double>): String {
        if (region.size != 4) return "[]"
        return buildString {
            append('[')
            append(region.joinToString(", ") { v -> "%.4f".format(Locale.US, v.coerceIn(0.0, 1.0)) })
            append(']')
        }
    }
}
