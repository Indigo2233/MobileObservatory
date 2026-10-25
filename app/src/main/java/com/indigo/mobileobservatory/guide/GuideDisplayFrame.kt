package com.indigo.mobileobservatory.guide

import android.graphics.Bitmap

/** One acquisition supplies the image, source dimensions, markers and diagnostic profile. */
data class GuideDisplayFrame(val bitmap: Bitmap, val width: Int, val height: Int,
    val stars: List<GuideStar>, val primary: GuideStar?, val profile: GuideStarProfile?, val sequence: Long = 0,
    val profiles: Map<GuideStar, GuideStarProfile> = emptyMap())
