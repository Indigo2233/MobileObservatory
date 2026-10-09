package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.BuildConfig

/**
 * Build-time switch for the advanced sequencer.
 *
 * The default is hidden while the feature is incomplete, so released APKs carry
 * no sequence entry points. Enable it for development with the Gradle property
 * `sequenceEnabled=true`, or with `Build.ps1 -ShowSequence`.
 *
 * UI code must read this flag rather than the Gradle property directly.
 */
object SequenceFeature {
    val ENABLED: Boolean = BuildConfig.SEQUENCE_ENABLED
}
