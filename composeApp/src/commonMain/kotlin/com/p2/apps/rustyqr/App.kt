package com.p2.apps.rustyqr

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.p2.apps.rustyqr.ui.components.PillTabBar
import com.p2.apps.rustyqr.ui.navigation.Tab
import com.p2.apps.rustyqr.ui.screen.GenerateScreen
import com.p2.apps.rustyqr.ui.screen.ScanScreen
import com.p2.apps.rustyqr.ui.theme.RustyQrTheme
import com.p2.apps.rustyqr.ui.theme.StandardEasing

/**
 * Root composable.
 *
 * Hosts tab state and switches between Scan / Generate screens via [Crossfade].
 * Tab state is hoisted here; camera lifecycle is tied to [Tab.Scan] visibility.
 */
@Composable
fun App() {
    RustyQrTheme {
        var selectedTab by remember { mutableStateOf(Tab.Scan) }
        AppContent(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
        )
    }
}

@Composable
private fun AppContent(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        ) {
            // Screen content fills remaining space
            Box(modifier = Modifier.weight(1f)) {
                Crossfade(
                    targetState = selectedTab,
                    animationSpec = tween(durationMillis = 300, easing = StandardEasing),
                    label = "tabCrossfade",
                ) { tab ->
                    when (tab) {
                        Tab.Scan -> ScanScreen()
                        Tab.Generate -> GenerateScreen()
                    }
                }
            }
            // Pill tab bar anchored at bottom with nav bar padding + IME padding
            PillTabBar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}
