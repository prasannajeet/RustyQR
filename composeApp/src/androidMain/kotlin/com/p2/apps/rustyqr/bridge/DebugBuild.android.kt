package com.p2.apps.rustyqr.bridge

import android.content.pm.ApplicationInfo
import com.p2.apps.rustyqr.AppContextHolder

actual val isDebugBuild: Boolean
    get() = (AppContextHolder.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
