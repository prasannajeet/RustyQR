package com.p2.apps.rustyqr.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.p2.apps.rustyqr.ui.mvi.AnimationPhase
import com.p2.apps.rustyqr.ui.theme.EmphasizedAccelerate
import com.p2.apps.rustyqr.ui.theme.EmphasizedDecelerate
import com.p2.apps.rustyqr.ui.theme.StandardEasing

/**
 * Orchestrates the Generate screen animations.
 *
 * Text-animate-down (300ms [EmphasizedAccelerate] exit) + card-fade-in
 * (200ms [EmphasizedDecelerate] enter with 100ms delay). Text alpha fade uses [StandardEasing].
 * Reverse animation: card fades, text returns to input position.
 *
 * Spec §6 animation timing.
 */
@Composable
fun AnimatedGenerateContent(
    animationPhase: AnimationPhase,
    inputContent: @Composable () -> Unit,
    resultContent: @Composable () -> Unit,
) {
    // Text offset: 0dp when Input, slides down when ShowingResult
    val textOffsetFraction by animateFloatAsState(
        targetValue =
            when (animationPhase) {
                AnimationPhase.Input -> 0f
                AnimationPhase.Animating, AnimationPhase.ShowingResult -> 1f
            },
        animationSpec = tween(durationMillis = 300, easing = EmphasizedAccelerate),
        label = "textOffset",
    )

    val textAlpha by animateFloatAsState(
        targetValue = if (animationPhase == AnimationPhase.ShowingResult) 0.7f else 1f,
        animationSpec = tween(durationMillis = 300, easing = StandardEasing),
        label = "textAlpha",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // QR Result card — fades in with scale from 0.8 using EmphasizedDecelerate (enter)
        AnimatedVisibility(
            visible = animationPhase != AnimationPhase.Input,
            enter =
                scaleIn(
                    initialScale = 0.8f,
                    animationSpec =
                        tween(
                            durationMillis = 200,
                            delayMillis = 100,
                            easing = EmphasizedDecelerate,
                        ),
                ) +
                    fadeIn(
                        animationSpec =
                            tween(
                                durationMillis = 200,
                                delayMillis = 100,
                                easing = EmphasizedDecelerate,
                            ),
                    ),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
            ) {
                resultContent()
            }
        }

        // Input text — scales down and moves toward bottom
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (textOffsetFraction * 300).dp.roundToPx(),
                        )
                    }.alpha(textAlpha)
                    .scale(scale = if (animationPhase == AnimationPhase.Input) 1f else 0.85f)
                    .padding(horizontal = 24.dp),
        ) {
            if (animationPhase == AnimationPhase.Input) {
                inputContent()
            }
        }
    }
}
