package com.p2.apps.rustyqr.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/**
 * MD3 easing constants for use with [androidx.compose.animation.core.tween]. Each is the cubic-bezier curve
 * (a, b) to (c, d) of the Material Design 3 motion spec.
 *
 * Reference: Material Design 3 motion spec (legacy easing/duration system for transitions).
 * Components that persist on screen use [StandardEasing]; enter uses [EmphasizedDecelerate];
 * exit uses [EmphasizedAccelerate].
 */
val StandardEasing = CubicBezierEasing(a = 0.2f, b = 0f, c = 0f, d = 1f)
val EmphasizedDecelerate = CubicBezierEasing(a = 0.05f, b = 0.7f, c = 0.1f, d = 1f)
val EmphasizedAccelerate = CubicBezierEasing(a = 0.3f, b = 0f, c = 0.8f, d = 0.15f)
