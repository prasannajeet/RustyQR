package com.p2.apps.rustyqr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.scan_viewfinder_cd

/**
 * 4 amber L-shaped corner brackets drawn via Canvas, centered over the camera feed.
 *
 * No connecting lines, no scan line — just the four corners.
 */
@Composable
fun ScannerOverlay(
    modifier: Modifier = Modifier,
    bracketSize: Dp = 240.dp,
    armLength: Dp = 40.dp,
    strokeWidth: Dp = 3.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val viewfinderLabel = stringResource(Res.string.scan_viewfinder_cd)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .semantics { contentDescription = viewfinderLabel },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxSize = bracketSize.toPx()
            val arm = armLength.toPx()
            val stroke = strokeWidth.toPx()

            // Center of canvas
            val cx = size.width / 2f
            val cy = size.height / 2f

            val left = cx - boxSize / 2f
            val top = cy - boxSize / 2f
            val right = cx + boxSize / 2f
            val bottom = cy + boxSize / 2f

            val corners =
                listOf(
                    // Top-left: horizontal right + vertical down
                    Pair(
                        Offset(left, top) to Offset(left + arm, top),
                        Offset(left, top) to Offset(left, top + arm),
                    ),
                    // Top-right: horizontal left + vertical down
                    Pair(
                        Offset(right, top) to Offset(right - arm, top),
                        Offset(right, top) to Offset(right, top + arm),
                    ),
                    // Bottom-left: horizontal right + vertical up
                    Pair(
                        Offset(left, bottom) to Offset(left + arm, bottom),
                        Offset(left, bottom) to Offset(left, bottom - arm),
                    ),
                    // Bottom-right: horizontal left + vertical up
                    Pair(
                        Offset(right, bottom) to Offset(right - arm, bottom),
                        Offset(right, bottom) to Offset(right, bottom - arm),
                    ),
                )

            corners.forEach { (horizontal, vertical) ->
                drawLine(
                    color = color,
                    start = horizontal.first,
                    end = horizontal.second,
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = vertical.first,
                    end = vertical.second,
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
