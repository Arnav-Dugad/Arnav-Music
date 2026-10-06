package com.arnav.music.core.firebase

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release builds attest with Play Integrity. Note: Play only vouches for installs from Google Play;
 * sideloaded APKs usually fail attestation, so enforced services reject them (the app falls back).
 */
internal object AppCheckInstaller {
    const val PROVIDER = "Play Integrity"
    fun install() = FirebaseAppCheck.getInstance().installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
}
