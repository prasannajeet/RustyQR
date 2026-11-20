package com.p2.apps.rustyqr.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

// Control points of the MD3 cubic-bezier curves (x1, y1, x2, y2).
private object EasingPoints {
    const val STANDARD_X1 = 0.2f

    const val EMPHASIZED_DECELERATE_X1 = 0.05f
    const val EMPHASIZED_DECELERATE_Y1 = 0.7f
    const val EMPHASIZED_DECELERATE_X2 = 0.1f

    const val EMPHASIZED_ACCELERATE_X1 = 0.3f
    const val EMPHASIZED_ACCELERATE_X2 = 0.8f
    const val EMPHASIZED_ACCELERATE_Y2 = 0.15f
}

/**
 * MD3 easing constants for use with [androidx.compose.animation.core.tween].
 *
 * Reference: Material Design 3 motion spec (legacy easing/duration system for transitions).
 * Components that persist on screen use [StandardEasing]; enter uses [EmphasizedDecelerate];
 * exit uses [EmphasizedAccelerate].
 */
val StandardEasing = CubicBezierEasing(EasingPoints.STANDARD_X1, 0f, 0f, 1f)
val EmphasizedDecelerate =
    CubicBezierEasing(
        EasingPoints.EMPHASIZED_DECELERATE_X1,
        EasingPoints.EMPHASIZED_DECELERATE_Y1,
        EasingPoints.EMPHASIZED_DECELERATE_X2,
        1f,
    )
val EmphasizedAccelerate =
    CubicBezierEasing(
        EasingPoints.EMPHASIZED_ACCELERATE_X1,
        0f,
        EasingPoints.EMPHASIZED_ACCELERATE_X2,
        EasingPoints.EMPHASIZED_ACCELERATE_Y2,
    )
