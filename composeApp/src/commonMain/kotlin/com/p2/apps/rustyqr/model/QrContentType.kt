package com.p2.apps.rustyqr.model

import org.jetbrains.compose.resources.StringResource
import rustyqr.composeapp.generated.resources.Res
import rustyqr.composeapp.generated.resources.content_type_text
import rustyqr.composeapp.generated.resources.content_type_url

/**
 * QR content type classification.
 */
enum class QrContentType(
    val labelRes: StringResource,
) {
    URL(Res.string.content_type_url),
    PLAIN_TEXT(Res.string.content_type_text),
}

/**
 * Detects QR content type via simple prefix matching.
 *
 * Extensible — add enum cases + prefix checks as more types are needed
 * (WIFI:T:, BEGIN:VCARD, etc.).
 */
fun detectContentType(content: String): QrContentType =
    when {
        content.startsWith("http://", ignoreCase = true) ||
            content.startsWith("https://", ignoreCase = true) -> QrContentType.URL
        else -> QrContentType.PLAIN_TEXT
    }
