package com.p2.apps.rustyqr.bridge

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

/**
 * iOS actual — triggers medium-weight haptic feedback via [UIImpactFeedbackGenerator].
 */
actual fun triggerHaptic() {
    UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
        .impactOccurred()
}
