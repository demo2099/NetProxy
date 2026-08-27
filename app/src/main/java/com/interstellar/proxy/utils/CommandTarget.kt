package com.interstellar.proxy.utils

import io.nekohasekai.libbox.Libbox

/**
 * Entry point for one-shot command calls (select outbound, url test, ...).
 * interstellar-android only ever talks to the local box, so this is always local.
 */
object CommandTarget {
    fun standaloneClient(): io.nekohasekai.libbox.CommandClient =
        Libbox.newStandaloneCommandClient()
}
