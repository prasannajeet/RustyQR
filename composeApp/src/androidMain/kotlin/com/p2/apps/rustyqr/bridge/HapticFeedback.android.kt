package com.p2.apps.rustyqr.bridge

import android.view.HapticFeedbackConstants
import com.p2.apps.rustyqr.BridgeHostActivityHolder

actual fun triggerHaptic() {
    BridgeHostActivityHolder.activity
        ?.window
        ?.decorView
        ?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}
