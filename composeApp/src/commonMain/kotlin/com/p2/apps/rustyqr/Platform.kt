package com.p2.apps.rustyqr

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
