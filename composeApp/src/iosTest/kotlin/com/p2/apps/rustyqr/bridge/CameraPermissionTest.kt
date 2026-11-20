package com.p2.apps.rustyqr.bridge

import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraPermissionTest {
    @Test
    fun deniedIsPermanentlyDenied() = assertTrue(isPermanentlyDenied(AVAuthorizationStatusDenied))

    @Test
    fun restrictedIsPermanentlyDenied() = assertTrue(isPermanentlyDenied(AVAuthorizationStatusRestricted))

    @Test
    fun authorizedIsNotPermanentlyDenied() = assertFalse(isPermanentlyDenied(AVAuthorizationStatusAuthorized))

    @Test
    fun notDeterminedIsNotPermanentlyDenied() = assertFalse(isPermanentlyDenied(AVAuthorizationStatusNotDetermined))
}
