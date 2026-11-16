package com.p2.apps.rustyqr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2.apps.rustyqr.model.CameraFailure
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.scan_camera_denied_cd
import rustyqr.composeapp.generated.resources.scan_camera_failed_body
import rustyqr.composeapp.generated.resources.scan_camera_failed_title
import rustyqr.composeapp.generated.resources.scan_camera_no_camera_body
import rustyqr.composeapp.generated.resources.scan_camera_no_camera_title
import rustyqr.composeapp.generated.resources.scan_grant_permission
import rustyqr.composeapp.generated.resources.scan_open_settings
import rustyqr.composeapp.generated.resources.scan_permission_body
import rustyqr.composeapp.generated.resources.scan_permission_title
import rustyqr.composeapp.generated.resources.scan_try_again

/**
 * Shown when camera permission is missing. Both message screens apply [WindowInsets.safeDrawing] before their
 * padding, like the idle screen: the parent box draws edge to edge, and centered text must stay clear of cutouts
 * and system bars.
 */
@Composable
internal fun PermissionDeniedContent(
    isPermanentlyDenied: Boolean,
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val columnModifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp)
    Column(
        modifier = columnModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CameraOffIcon(contentDescription = stringResource(Res.string.scan_camera_denied_cd))

        Spacer(modifier = Modifier.height(24.dp))

        MessageBlock(
            title = stringResource(Res.string.scan_permission_title),
            body = stringResource(Res.string.scan_permission_body),
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (isPermanentlyDenied) {
            AmberButton(
                text = stringResource(Res.string.scan_open_settings),
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AmberButton(
                text = stringResource(Res.string.scan_grant_permission),
                onClick = onGrantPermission,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Shown when the camera is unusable; mirrors [PermissionDeniedContent]. Retryable failures offer "Try Again", which
 * restarts the camera; [CameraFailure.NoCamera] shows the message alone.
 */
@Composable
internal fun CameraFailureContent(
    failure: CameraFailure,
    onTryAgain: () -> Unit,
) {
    val (titleRes, bodyRes) = failure.messages()
    val columnModifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp)
    Column(
        modifier = columnModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The title already says what happened, so the icon adds no spoken description.
        CameraOffIcon(contentDescription = null)

        Spacer(modifier = Modifier.height(24.dp))

        MessageBlock(title = stringResource(titleRes), body = stringResource(bodyRes))

        // Retrying can never help without a camera, so that failure shows the message alone.
        if (failure.isRetryable) {
            Spacer(modifier = Modifier.height(32.dp))

            AmberButton(
                text = stringResource(Res.string.scan_try_again),
                onClick = onTryAgain,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun CameraFailure.messages(): Pair<StringResource, StringResource> =
    when (this) {
        CameraFailure.NoCamera -> Res.string.scan_camera_no_camera_title to Res.string.scan_camera_no_camera_body
        CameraFailure.CouldNotStart -> Res.string.scan_camera_failed_title to Res.string.scan_camera_failed_body
    }

/** Camera icon with a strike-through line. */
@Composable
private fun CameraOffIcon(contentDescription: String?) {
    Box(contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.CameraAlt,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // Strike-through line
        val strikeModifier =
            Modifier
                .size(width = 80.dp, height = 2.dp)
                .background(MaterialTheme.colorScheme.error)
        Box(
            modifier = strikeModifier,
        )
    }
}

@Composable
private fun ColumnScope.MessageBlock(
    title: String,
    body: String,
) {
    val titleStyle =
        MaterialTheme.typography.bodyLarge.copy(
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
        )
    Text(
        text = title,
        style = titleStyle,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(8.dp))

    val bodyStyle =
        MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    Text(
        text = body,
        style = bodyStyle,
        textAlign = TextAlign.Center,
    )
}
