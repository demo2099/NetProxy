package com.interstellar.proxy.ui

import android.app.Application
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.data.CommonProxyApps
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.utils.CommandTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSelf: Boolean = false,
    val systemApp: Boolean = false,
)

/**
 * Per-app proxy state: launchable app list, selection and persistence.
 * Applying changes hot-reloads the running core (OverrideOptions rebuild).
 */
class PerAppProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _enabled = MutableStateFlow(Settings.perAppProxyEnabled)
    val enabled: StateFlow<Boolean> = _enabled

    private val _mode = MutableStateFlow(Settings.perAppProxyMode)
    val mode: StateFlow<Int> = _mode

    private val _selected = MutableStateFlow(Settings.perAppProxyList)
    val selected: StateFlow<Set<String>> = _selected

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search

    private val _showSystemApps = MutableStateFlow(Settings.perAppProxyShowSystemApps)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps

    init {
        viewModelScope.launch {
            _apps.value = loadApps()
            _loading.value = false
        }
    }

    private suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val context = InterstellarApplication.application
        val pm = context.packageManager
        val self = context.packageName
        val launcherApps = context.getSystemService(LauncherApps::class.java)

        val packageNames = mutableSetOf(self)
        // LauncherApps lists launchable apps for the current user without
        // needing QUERY_ALL_PACKAGES on Android 11+; fall back to PM queries.
        runCatching {
            launcherApps?.getActivityList(null, Process.myUserHandle())?.forEach { info ->
                packageNames.add(info.componentName.packageName)
            }
        }.onFailure {
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { info ->
                if (pm.getLaunchIntentForPackage(info.packageName) != null || info.packageName == self) {
                    packageNames.add(info.packageName)
                }
            }
        }

        packageNames.mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                AppEntry(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString().ifBlank { pkg },
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    isSelf = pkg == self,
                    systemApp = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }.getOrNull()
        }.sortedWith(compareByDescending<AppEntry> { it.isSelf }.thenBy { it.label.lowercase() })
    }

    fun setSearch(value: String) {
        _search.value = value
    }

    fun setShowSystemApps(value: Boolean) {
        _showSystemApps.value = value
        Settings.perAppProxyShowSystemApps = value
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        Settings.perAppProxyEnabled = value
        apply()
    }

    fun setMode(mode: Int) {
        _mode.value = mode
        Settings.perAppProxyMode = mode
        apply()
    }

    fun toggle(packageName: String) {
        val current = _selected.value
        _selected.value = if (packageName in current) current - packageName else current + packageName
        Settings.perAppProxyList = _selected.value
        apply()
    }

    fun selectAllVisible(visible: List<AppEntry>) {
        _selected.value = (_selected.value + visible.map { it.packageName }).toSet()
        Settings.perAppProxyList = _selected.value
        apply()
    }

    /**
     * Check common apps that typically need a proxy (Google, Instagram,
     * Discord, ChatGPT, Grok, …). Unions with the current selection.
     * @return number of newly checked packages
     */
    fun selectCommon(): Int {
        val matched = _apps.value
            .asSequence()
            .filter { !it.isSelf && CommonProxyApps.matches(it.packageName, it.label) }
            .map { it.packageName }
            .toMutableSet()
        val pm = getApplication<Application>().packageManager
        CommonProxyApps.companions.forEach { pkg ->
            if (runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess) {
                matched += pkg
            }
        }
        val added = matched - _selected.value
        if (added.isEmpty()) return 0
        _selected.value = _selected.value + matched
        Settings.perAppProxyList = _selected.value
        apply()
        return added.size
    }

    fun clearSelection() {
        _selected.value = emptySet()
        Settings.perAppProxyList = emptySet()
        apply()
    }

    /**
     * Persisted already; if the core is running, rebuild OverrideOptions via
     * serviceReload (BoxService reads Settings on each startOrReloadService).
     */
    private fun apply() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                CommandTarget.standaloneClient().serviceReload()
            }
        }
    }
}
