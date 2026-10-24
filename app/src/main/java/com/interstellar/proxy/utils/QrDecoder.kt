package com.interstellar.proxy.utils

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码解码（ZXing core，纯 Java）。
 *
 * 为什么不用 ML Kit：它的条码识别依赖 Google Play 服务，国内机器大多没有 GMS，
 * 结果是「相机开了但永远识别不出来」——比不做到还糟。ZXing core 离线可用、零依赖。
 *
 * 两个刻意的取舍：
 * 1. **不按 rotationDegrees 旋转位图**。QR 码本身旋转无关，直接把 Y 平面喂进去就行，
 *    省掉每帧一次整图拷贝 + 旋转（扫码头最怕的就是这个）。
 * 2. **只开 QR_CODE**。多格式会把 PDF417/Aztec/一维码都试一遍，帧率直接砍半。
 */
object QrDecoder {

    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** 相机实时帧。必须在 analyze() 里调用，返回前不会 close（由调用方负责）。 */
    fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        buffer.rewind()

        val raw = ByteArray(rowStride * height)
        val n = minOf(buffer.remaining(), raw.size)
        buffer.get(raw, 0, n)
        if (n < raw.size) raw.fill(0, n, raw.size)

        // 绝大多数设备 Y 平面 pixelStride = 1，可以直接把 rowStride 当 dataWidth 用
        // （ZXing 靠 dataWidth 跳过每行尾部的填充字节）。pixelStride > 1 的机型很少，
        // 兜底压成紧凑数组，否则解出来是斜的、永远识别不到。
        return if (pixelStride == 1) {
            decodeSource(PlanarYUVLuminanceSource(raw, rowStride, height, 0, 0, width, height, false))
        } else {
            val packed = ByteArray(width * height)
            for (y in 0 until height) {
                val rowBase = y * rowStride
                val outBase = y * width
                for (x in 0 until width) {
                    val i = rowBase + x * pixelStride
                    if (i < raw.size) packed[outBase + x] = raw[i]
                }
            }
            decodeSource(PlanarYUVLuminanceSource(packed, width, height, 0, 0, width, height, false))
        }
    }

    /** 相册里的图片。大图先缩到 1600px —— 再大对识别率没帮助，只是慢。 */
    fun decode(bitmap: Bitmap): String? {
        val scaled = if (maxOf(bitmap.width, bitmap.height) > 1600) {
            val ratio = 1600f / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        return decodeSource(RGBLuminanceSource(w, h, pixels))
    }

    private fun decodeSource(source: LuminanceSource): String? {
        val reader = MultiFormatReader()
        return try {
            reader.setHints(hints)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
        } catch (e: Exception) {
            // NotFoundException 是常态（大部分帧里没有码），不要当错误打日志
            null
        } finally {
            reader.reset()
        }
    }
}
