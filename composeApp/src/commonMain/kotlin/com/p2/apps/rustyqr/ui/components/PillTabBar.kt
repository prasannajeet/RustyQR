package com.p2.apps.rustyqr.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2.apps.rustyqr.ui.navigation.Tab
import com.p2.apps.rustyqr.ui.theme.StandardEasing
import org.jetbrains.compose.resources.stringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.tab_generate
import rustyqr.composeapp.generated.resources.tab_scan

// Brand pill shape — intentional MD3 deviation (full pill container).
private val PillShape = RoundedCornerShape(24.dp)

// Tab item shape — intentional MD3 deviation (inner pill within container).
private val TabShape = RoundedCornerShape(20.dp)

/**
 * Pill-style tab toggle with amber fill animation.
 *
 * Dark pill container ([surfaceContainerHighest]) containing two tabs.
 * Active tab: amber fill ([primary]), dark text. Inactive: transparent, muted text.
 *
 * Accessibility: tabs are announced as "Scan tab, selected, 1 of 2" etc. via [selectableGroup]
 * and per-tab [semantics].
 */
@Composable
fun PillTabBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 12.dp)
                .height(52.dp)
                .clip(PillShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEachIndexed { index, tab ->
                PillTabItem(
                    tab = tab,
                    isSelected = tab == selectedTab,
                    tabIndex = index + 1,
                    totalTabs = Tab.entries.size,
                    onSelected = { onTabSelected(tab) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun PillTabItem(
    tab: Tab,
    isSelected: Boolean,
    tabIndex: Int,
    totalTabs: Int,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
        animationSpec = tween(durationMillis = 300, easing = StandardEasing),
        label = "tabBackground",
    )
    val textColor by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = tween(durationMillis = 300, easing = StandardEasing),
        label = "tabTextColor",
    )
    val tabStateDescription = if (isSelected) "selected" else "not selected"
    Box(
        modifier =
            modifier
                .clip(TabShape)
                .background(bgColor)
                .semantics {
                    // stateDescription is merged into the selectable node below so
                    // TalkBack announces e.g. "Scan tab, selected, 1 of 2".
                    stateDescription = "$tabStateDescription, $tabIndex of $totalTabs"
                }.selectable(
                    selected = isSelected,
                    role = Role.Tab,
                    onClick = onSelected,
                ).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val label =
            when (tab) {
                Tab.Scan -> stringResource(Res.string.tab_scan)
                Tab.Generate -> stringResource(Res.string.tab_generate)
            }
        Text(
            text = label,
            color = textColor,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                ),
        )
    }
}
