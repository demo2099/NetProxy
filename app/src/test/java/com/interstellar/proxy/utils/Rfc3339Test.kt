package com.interstellar.proxy.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * mihomo 的 `start` 是 Go 的 RFC3339Nano：小数秒 0~9 位，时区可能是 Z 或 ±hh:mm。
 * 这些用例覆盖 Android 7（API 24）上必须也能解析的形态。
 */
class Rfc3339Test {
    private val expected = 1794021932888L // 2026-11-07T11:25:32.888+08:00

    @Test
    fun parsesMillisecondsWithPositiveOffset() {
        assertEquals(expected, Rfc3339.parseToEpochMillis("2026-11-07T11:25:32.888+08:00"))
    }

    @Test
    fun parsesUtcZ() {
        assertEquals(expected, Rfc3339.parseToEpochMillis("2026-11-07T03:25:32.888Z"))
    }

    @Test
    fun parsesWithoutFraction() {
        // 没有小数秒 ⇒ 毫秒位补 000
        assertEquals(1794021932000L, Rfc3339.parseToEpochMillis("2026-11-07T11:25:32+08:00"))
    }

    @Test
    fun truncatesNanoseconds() {
        assertEquals(expected, Rfc3339.parseToEpochMillis("2026-11-07T11:25:32.888123456+08:00"))
    }

    @Test
    fun padsSingleDigitFraction() {
        // 0.8s == 800ms
        assertEquals(1794021932800L, Rfc3339.parseToEpochMillis("2026-11-07T11:25:32.8+08:00"))
    }

    @Test
    fun parsesNegativeOffset() {
        assertEquals(1794068732000L, Rfc3339.parseToEpochMillis("2026-11-07T11:25:32.000-05:00"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Rfc3339.parseToEpochMillis(null))
        assertNull(Rfc3339.parseToEpochMillis(""))
        assertNull(Rfc3339.parseToEpochMillis("not-a-time"))
        assertNull(Rfc3339.parseToEpochMillis("2026-11-07"))
    }
}
