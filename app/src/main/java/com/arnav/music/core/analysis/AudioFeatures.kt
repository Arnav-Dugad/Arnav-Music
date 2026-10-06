package com.arnav.music.core.analysis

import kotlin.math.abs
import kotlin.math.roundToInt

/** Constants shared by the analyzer, the database rows it writes and the visuals that read them. */
object AudioFeatures {
    /** One envelope byte per this many milliseconds of audio. */
    const val ENVELOPE_STEP_MS = 500L

    /** Bump when the analysis changes; older rows are then re-analyzed. */
    const val VERSION = 1

    /** Short human label for an energy score, e.g. "High energy". */
    fun energyLabel(energy: Float): String = when {
        energy >= 0.66f -> "High energy"
        energy >= 0.38f -> "Medium energy"
        else -> "Low energy"
    }

    /** "124 BPM · −9 LUFS · High energy"; parts that weren't measured are left out. */
    fun describe(bpm: Float, loudnessDb: Float, energy: Float): String = buildList {
        if (bpm > 0f) add("${bpm.roundToInt()} BPM")
        if (loudnessDb > -69f) {
            val l = loudnessDb.roundToInt()
            add((if (l < 0) "−" else "") + "${abs(l)} LUFS")
        }
        add(energyLabel(energy))
    }.joinToString(" · ")
}
