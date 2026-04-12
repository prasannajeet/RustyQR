package com.p2.apps.rustyqr.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/**
 * MD3 easing constants for use with [androidx.compose.animation.core.tween].
 *
 * Reference: Material Design 3 motion spec (legacy easing/duration system for transitions).
 * Components that persist on screen use [StandardEasing]; enter uses [EmphasizedDecelerate];
 * exit uses [EmphasizedAccelerate].
 */
val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
