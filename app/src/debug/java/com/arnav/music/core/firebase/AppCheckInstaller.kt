package com.arnav.music.core.firebase

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds use the App Check debug provider. On first run Logcat prints a debug secret
 * (tag "DebugAppCheckProvider"); add it in Firebase console → App Check → Manage debug tokens.
 */
internal object AppCheckInstaller {
    const val PROVIDER = "Debug provider"
    fun install() = FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
}
