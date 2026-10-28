package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData

/** A frame may use only the settings captured for its SDK acquisition run. */
class ScienceFrameProvenance {
    private var generation: Long? = null
    private var settings: StackSessionKey? = null
    private var latest: StackSessionKey? = null

    @Synchronized fun clear() { generation = null; settings = null; latest = null }

    @Synchronized fun bind(run: Long, snapshot: StackSessionKey) {
        generation = run; settings = snapshot; latest = null
    }

    @Synchronized fun frame(frame: FrameData, activeRun: Long?): StackSessionKey? {
        if (frame.previewOnly || activeRun == null || generation != activeRun || frame.captureGeneration != activeRun) return null
        return settings?.copy(width = frame.width, height = frame.height, format = frame.pixelFormat)
            ?.also { latest = it }
    }

    @Synchronized fun latest(activeRun: Long?): StackSessionKey? =
        latest.takeIf { activeRun != null && activeRun == generation }
}
