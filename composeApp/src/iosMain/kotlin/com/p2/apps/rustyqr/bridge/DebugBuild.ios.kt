@file:OptIn(ExperimentalNativeApi::class)

package com.p2.apps.rustyqr.bridge

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

actual val isDebugBuild: Boolean
    get() = Platform.isDebugBinary
