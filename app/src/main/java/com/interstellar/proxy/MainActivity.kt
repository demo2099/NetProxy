package com.interstellar.proxy

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.ConnectionsViewModel
import com.interstellar.proxy.ui.LogsViewModel
import com.interstellar.proxy.ui.pages.ConnectionsPage
import com.interstellar.proxy.ui.pages.DashboardPage
import com.interstellar.proxy.ui.pages.LogsPage
import com.interstellar.proxy.ui.pages.PerAppProxyPage
import com.interstellar.proxy.ui.pages.ProxiesPage
import com.interstellar.proxy.ui.pages.SettingsPage
import com.interstellar.proxy.ui.pages.SettingsSubPage
import com.interstellar.proxy.ui.pages.setThemeChangedListener
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion
import com.interstellar.proxy.ui.theme.InterstellarTheme

class MainActivity : ComponentActivity() {

    private var pendingStart: (() -> Unit)? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pending = pendingStart
            pendingStart = null
            pending?.invoke()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeVersion by remember { mutableIntStateOf(0) }
            setThemeChangedListener { themeVersion++ }
            // nav lives OUTSIDE the theme key so theme switches never reset
            // the current tab / sub-page
            var nav by remember { mutableStateOf(NavState()) }
            androidx.compose.runtime.key(themeVersion) {
                InterstellarTheme(themeMode = Settings.themeMode, accentId = null) {
                    AppRoot(
                        nav = nav,
                        onNavChange = { nav = it },
                        requestVpnThenStart = { onReady ->
                            val prepare = VpnService.prepare(this)
                            android.util.Log.d("InterstellarUI", "vpn prepare=" + (prepare != null))
                            if (prepare != null) {
                                pendingStart = onReady
                                vpnPermissionLauncher.launch(prepare)
                            } else {
                                onReady()
                            }
                        },
                    )
                }
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Stable fingerprint of a clipboard text, used to avoid re-prompting for the same clip. */
private fun clipFingerprint(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)

/** navigation state: tab index + optional pushed sub-page. */
data class NavState(
    val pages: List<SettingsSubPage> = emptyList(),
)

@Composable
fun AppRoot(
    nav: NavState,
    onNavChange: (NavState) -> Unit,
    requestVpnThenStart: (onReady: () -> Unit) -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val appViewModel: AppViewModel = viewModel()
    val logsViewModel: LogsViewModel = viewModel()
    val connectionsViewModel: ConnectionsViewModel = viewModel()

    DisposableEffect(Unit) {
        appViewModel.connect()
        logsViewModel.connect()
        connectionsViewModel.connect()
        onDispose {
            appViewModel.disconnect()
            logsViewModel.disconnect()
            connectionsViewModel.disconnect()
        }
    }

    fun push(page: SettingsSubPage) = onNavChange(nav.copy(pages = nav.pages + page))
    fun pop() = onNavChange(nav.copy(pages = nav.pages.dropLast(1)))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                ),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                // push navigation: sub-pages slide in from the right
                AnimatedContent(
                    targetState = nav.pages,
                    transitionSpec = {
                        val entering = targetState.size > initialState.size
                        val exiting = targetState.size < initialState.size
                        when {
                            entering -> (
                                slideInHorizontally(tween(300, easing = Motion.Ease)) { it } + fadeIn(tween(300))
                                ) togetherWith (
                                slideOutHorizontally(tween(300, easing = Motion.Ease)) { -it / 3 } + fadeOut(tween(200))
                                )

                            exiting -> (
                                slideInHorizontally(tween(300, easing = Motion.Ease)) { -it / 3 } + fadeIn(tween(200))
                                ) togetherWith (
                                slideOutHorizontally(tween(300, easing = Motion.Ease)) { it } + fadeOut(tween(300))
                                )

                            else -> fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                        }
                    },
                    label = "nav",
                ) { pages ->
                    val current = pages.lastOrNull()
                    if (current != null) {
                        SubPageContainer(
                            title = com.interstellar.proxy.ui.pages.settingsSubPageTitle(current),
                            onBack = { pop() },
                            swipeBack = current == SettingsSubPage.Settings,
                        ) {
                            when (current) {
                                SettingsSubPage.Settings -> SettingsPage(
                                    onOpen = { sub -> push(sub) },
                                    onProxyChanged = { appViewModel.refreshProxyConfig() },
                                )
                                SettingsSubPage.PerApp -> PerAppProxyPage(onBack = { pop() })
                                SettingsSubPage.Connections -> ConnectionsPage(connectionsViewModel, appViewModel)
                                SettingsSubPage.Logs -> LogsPage(logsViewModel)
                                SettingsSubPage.Proxies -> ProxiesPage(appViewModel)
                                SettingsSubPage.Rules -> com.interstellar.proxy.ui.pages.CustomRulesPage(appViewModel)
                                SettingsSubPage.Dns -> com.interstellar.proxy.ui.pages.DnsOverridesPage(appViewModel)
                            }
                        }
                    } else {
                        DashboardPage(
                            viewModel = appViewModel,
                            connectionsViewModel = connectionsViewModel,
                            onStart = { requestVpnThenStart { appViewModel.startProxy() } },
                            onOpenSubPage = { sub -> push(sub) },
                        )
                    }
                }
            }

            // iOS tab bar
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** iOS-style pushed page with back chevron header + system back support. */
@Composable
private fun SubPageContainer(
    title: String,
    onBack: () -> Unit,
    swipeBack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    androidx.activity.compose.BackHandler { onBack() }
    // 右滑返回主页（仅设置页开启，节点页有自己的横向 tab 滑动）
    val back by androidx.compose.runtime.rememberUpdatedState(onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (swipeBack) {
                    Modifier.pointerInput(Unit) {
                        var accum = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { accum = 0f },
                            onDragEnd = {
                                if (accum > 70.dp.toPx()) back()
                            },
                        ) { _, dragAmount -> accum += dragAmount }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = colors.accent,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onBack() }
                    .padding(8.dp)
                    .size(22.dp),
            )
            Text(
                title,
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}
