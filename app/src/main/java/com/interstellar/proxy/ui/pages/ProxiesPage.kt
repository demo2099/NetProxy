package com.interstellar.proxy.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.components.SegmentedControl
import kotlinx.coroutines.launch

/**
 * 节点 + 订阅 merged entry: segmented tabs over a swipeable pager.
 * Page 0 (default) is the node picker; page 1 is subscription management.
 */
@Composable
fun ProxiesPage(viewModel: AppViewModel) {
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        SegmentedControl(
            items = listOf("节点", "订阅"),
            selected = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (page) {
                0 -> NodesPage(viewModel)
                else -> SubscriptionsPage(viewModel)
            }
        }
    }
}
