package com.interstellar.proxy.utils

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 解析 RFC3339 时间戳（mihomo / Clash API 的 `start` 字段）。
 *
 * 为什么不用 `java.time.OffsetDateTime`：它是 **API 26** 才有的类，而本应用
 * minSdk = 24。调用点虽然包在 `runCatching` 里（Kotlin 的 runCatching 捕获
 * Throwable）所以不会崩，但 Android 7 上会静默失败 —— 连接列表的时间全部
 * 退化成「现在」，按时间排序也跟着乱。
 *
 * mihomo 用的是 Go 的 RFC3339Nano，小数秒可能是 1~9 位；`SimpleDateFormat`
 * 只认 3 位，所以先规范化：小数秒截断/补齐到毫秒，`Z` 换成 `+00:00`。
 */
object Rfc3339 {
    private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"

    /** 解析失败返回 null（调用方自己决定回退值）。 */
    fun parseToEpochMillis(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        // 最短也要 yyyy-MM-ddTHH:mm:ss
        if (raw.length < 19) return null

        return runCatching {
            val base = raw.substring(0, 19) // yyyy-MM-ddTHH:mm:ss
            var rest = raw.substring(19)

            var fraction = "000"
            if (rest.startsWith(".")) {
                var end = 1
                while (end < rest.length && rest[end].isDigit()) end++
                fraction = rest.substring(1, end).padEnd(3, '0').take(3)
                rest = rest.substring(end)
            }

            val offset = when {
                rest.isEmpty() -> "+00:00"
                rest.equals("Z", ignoreCase = true) -> "+00:00"
                else -> rest
            }

            SimpleDateFormat(PATTERN, Locale.US).parse("$base.$fraction$offset")?.time
        }.getOrNull()
    }
}
