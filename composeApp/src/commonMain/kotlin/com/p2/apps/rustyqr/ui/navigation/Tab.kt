package com.p2.apps.rustyqr.ui.navigation

/**
 * Top-level tab destinations.
 *
 * No navigation library required — two tabs with no route args use
 * simple [selectedTab] state + `when` block in App.kt.
 */
enum class Tab {
    Scan,
    Generate,
}
