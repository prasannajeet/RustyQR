package com.p2.apps.rustyqr

import android.content.Context
import androidx.startup.Initializer

/**
 * Seeds [AppContextHolder] with the application context. Declared in this library's manifest under
 * `androidx.startup.InitializationProvider`, so it runs before `Application.onCreate` without any app-module code.
 */
internal class AppContextInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        AppContextHolder.context = context.applicationContext
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
