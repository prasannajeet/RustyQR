package com.p2.apps.rustyqr.bridge

import android.view.HapticFeedbackConstants
import com.p2.apps.rustyqr.MainActivityHolder

actual fun triggerHaptic() {
    MainActivityHolder.activity
        ?.window
        ?.decorView
        ?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}
