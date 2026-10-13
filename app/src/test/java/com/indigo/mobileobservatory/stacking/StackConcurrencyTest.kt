package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StackConcurrencyTest {
    private val key=StackSessionKey("camera",2,2,PixelFormat.MONO8,Roi(0,0,2,2),1,1,1_000_000.0,0f,"L","normal")
    private fun frame(id:Long)=FrameData(ByteArray(4){64},2,2,PixelFormat.MONO8,id,id)
    private class Engine : LiveStackEngine {
        var count=0
        override fun add(image:LinearImage,exposureSeconds:Double,cancelled:()->Boolean):LiveFrameOutcome {
            count++;return LiveFrameOutcome(true,8,0.0,0.0)
        }
        override fun snapshot(state:LiveStackState)=LiveStackSnapshot(LinearImage(2,2,1,FloatArray(4){.25f}),FloatArray(4){count.toFloat()},state)
    }

    @Test fun continuouslyReplenishedLiveQueueYieldsToAnExportOrPauseJob()= runTest {
        val engine=Engine();lateinit var session:LiveStackSession;var clock=0L
        session=LiveStackSession(this,{engine},{_,_->LinearImage(2,2,1,FloatArray(4){.25f})},
            { snapshot -> if(snapshot!=null) session.offer(frame(snapshot.state.accepted+1),key) },
            nowNanos={clock+=2_000_000_000;clock})
        try {
            session.start(key);session.offer(frame(1),key)
            val competing=launch { session.pause("export") }
            runCurrent()
            assertTrue(competing.isCompleted)
            assertEquals(LiveStackPhase.PAUSED,session.state.value.phase)
            assertEquals(1,engine.count)
            assertEquals(1L,session.state.value.dropped)
        } finally { session.close();runCurrent() }
    }

    @Test fun nonConsecutiveOldFrameIdsNeverDoubleCountExposure()= runTest {
        val engine=Engine()
        val session=LiveStackSession(this,{engine},{_,_->LinearImage(2,2,1,FloatArray(4){.25f})},{})
        try {
            session.start(key);session.offer(frame(10),key);runCurrent()
            session.offer(frame(11),key);runCurrent()
            session.offer(frame(10),key);session.offer(frame(9),key);runCurrent()
            assertEquals(2L,session.state.value.accepted)
            assertEquals(2L,session.state.value.duplicates)
            assertEquals(2.0,session.state.value.exposureSeconds,0.0)
        } finally { session.close();runCurrent() }
    }

    @Test fun invalidationCountsEveryDiscardedQueuedFrame()= runTest {
        val session=LiveStackSession(this,{Engine()},{_,_->LinearImage(2,2,1,FloatArray(4){.25f})},{})
        try {
            session.start(key);session.offer(frame(1),key);runCurrent()
            session.offer(frame(2),key);session.offer(frame(3),key)
            session.invalidate("camera_disconnected");runCurrent()
            assertEquals(3L,session.state.value.received)
            assertEquals(1L,session.state.value.accepted)
            assertEquals(2L,session.state.value.dropped)
        } finally { session.close();runCurrent() }
    }
}
