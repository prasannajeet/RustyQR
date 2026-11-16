package com.p2.apps.rustyqr

import android.app.Application

/**
 * Application subclass — owns process-scoped singletons. Registered as
 * `android:name` on the `<application>` tag in `AndroidManifest.xml`.
 *
 * The application context it installs into [AppContextHolder] is available
 * for the entire process lifetime, including the brief window between
 * `MainActivity.onDestroy` (rotation) and the next `onCreate`, so bridge
 * calls that only need a [android.content.Context] never see a null holder.
 */
class RustyQrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.context = applicationContext
    }
}
