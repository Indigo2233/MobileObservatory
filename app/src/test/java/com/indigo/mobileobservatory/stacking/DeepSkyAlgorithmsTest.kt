package com.indigo.mobileobservatory.stacking

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.*

class DeepSkyAlgorithmsTest {
    private data class Point(val x:Double,val y:Double,val amplitude:Double)
    private val w=192; private val h=160
    private fun points():List<Point> {
        val random=Random(1234); val stars=ArrayList<Point>()
        while(stars.size<28) {
            val p=Point(14+random.nextDouble()*(w-28),14+random.nextDouble()*(h-28),.15+random.nextDouble()*.4)
            if(stars.none { hypot(it.x-p.x,it.y-p.y)<12 }) stars+=p
        }
        return stars
    }
    private fun field(t:RigidTransform=RigidTransform(),noise:Double=.001,seed:Long=1,amplitude:Double=1.0,stretchX:Double=1.0):LinearImage {
        val random=Random(seed); val data=FloatArray(w*h) { (.04+random.nextGaussian()*noise).toFloat() }
        for(star in points()) {
            val sx=t.cosine*(star.x-t.dx)+t.sine*(star.y-t.dy); val sy=-t.sine*(star.x-t.dx)+t.cosine*(star.y-t.dy)
            for(y in max(0,sy.toInt()-10)..min(h-1,sy.toInt()+10)) for(x in max(0,sx.toInt()-10)..min(w-1,sx.toInt()+10))
                data[y*w+x]+=(amplitude*star.amplitude*exp(-((x-sx).pow(2)/(stretchX*stretchX)+(y-sy).pow(2))/(2*1.2*1.2))).toFloat()
        }
        return LinearImage(w,h,1,data)
    }

    @Test fun subpixelRotationAndTranslationRecoverIndependentAnalyticStars() {
        val truth=RigidTransform(Math.toRadians(4.3),5.35,-3.7)
        val reference=StarRegistration.detect(field(noise=.001))
        val source=StarRegistration.detect(field(truth,noise=.001,seed=2))
        val result=StarRegistration.match(reference,source)
        assertNotNull(result)
        result!!
        val error=points().map { p ->
            val sx=truth.cosine*(p.x-truth.dx)+truth.sine*(p.y-truth.dy); val sy=-truth.sine*(p.x-truth.dx)+truth.cosine*(p.y-truth.dy)
            hypot(result.transform.x(sx,sy)-p.x,result.transform.y(sx,sy)-p.y)
        }.average()
        println("DEEP_SKY registration mean error=$error px, centroid RMS=${result.rmsPixels}, inliers=${result.inliers}")
        assertTrue("Registration error $error",error<.12)
        assertTrue(result.inliers>=20)
    }

    @Test fun rejectsHotPixelsAsStarsAndGeometricOutliers() {
        val image=field(); repeat(16) { image.pixels[(8+it*8)*w+8]=1f }
        val detected=StarRegistration.detect(image)
        assertTrue(detected.stars.size in 25..30)
        val reference=StarRegistration.detect(field())
        val polluted=detected.copy(stars=detected.stars+List(12) { DetectedStar(it*13.7,5.2+it*4.1,.3,1.5,1.0) })
        val match=StarRegistration.match(reference,polluted)
        assertNotNull(match); assertTrue(match!!.rmsPixels<.12)
    }

    @Test fun onlineStackReducesNoiseAndRejectsLateTransientWithoutDarkEdges() {
        val stack=DeepSkyStacker()
        val first=field(noise=.004)
        repeat(20) { frame ->
            val image=field(noise=.004,seed=frame+1L)
            if(frame==12) image.pixels[75*w+4]=.95f
            assertTrue("Frame $frame: ${stack.stats}",stack.add(image,2.5).accepted)
        }
        val snapshot=stack.snapshot()!!
        fun error(image:LinearImage)=sqrt((40 until 100).sumOf { y -> (2 until 9).sumOf { x -> (image.pixels[y*w+x]-.04).pow(2) } }/(60*7))
        println("DEEP_SKY noise single=${error(first)}, stack=${error(snapshot.image)}, ratio=${error(snapshot.image)/error(first)}; transient rejects=${snapshot.stats.rejectedPixelSamples}")
        assertTrue(error(snapshot.image)<error(first)*.38)
        assertTrue(snapshot.stats.rejectedPixelSamples>0)
        assertEquals(.04,snapshot.image.pixels[75*w+4].toDouble(),.004)
        assertEquals(50.0,snapshot.stats.exposureSeconds,1e-9)
        assertTrue(snapshot.image.pixels[0]>.03)
        assertTrue(snapshot.weights[0]>=1)
        val independent=snapshot.image.pixels.copyOf(); stack.add(field(noise=.004,seed=23),2.5)
        assertArrayEquals(independent,snapshot.image.pixels,0f)
    }

    @Test fun invalidWarpEdgesHaveReducedWeightInsteadOfBlackSamples() {
        val stack=DeepSkyStacker(); assertTrue(stack.add(field(noise=.0001),1.0).accepted)
        assertTrue(stack.add(field(RigidTransform(dx=5.25,dy=2.4),noise=.0001),1.0).accepted)
        val s=stack.snapshot()!!
        assertEquals(1f,s.weights[0],0f)
        assertEquals(2f,s.weights[80*w+80],0f)
        assertEquals(.04,s.image.pixels[0].toDouble(),.001)
    }

    @Test fun qualityGatesPauseIndependentStateAndReset() {
        val stack=DeepSkyStacker()
        assertEquals(DeepSkyRejection.TOO_FEW_STARS,stack.add(LinearImage(w,h,1,FloatArray(w*h){.1f}),1.0).reason)
        assertTrue(stack.add(field(),1.0).accepted)
        assertEquals(DeepSkyRejection.CLOUD,stack.add(field(amplitude=.2),1.0).reason)
        assertEquals(DeepSkyRejection.TRAILING,stack.add(field(stretchX=3.5),1.0).reason)
        assertEquals(1,stack.snapshot()!!.stats.accepted)
        stack.reset(); assertNull(stack.snapshot()); assertEquals(0,stack.stats.received)
    }

    @Test fun memoryBudgetAndCancellationFailBeforeMutation() {
        val stack=DeepSkyStacker(DeepSkyConfig(memoryBudgetBytes=1024))
        assertEquals(DeepSkyRejection.MEMORY_BUDGET,stack.add(field(),1.0).reason)
        val cancellable=DeepSkyStacker()
        try { cancellable.add(field(),1.0){true}; fail("Expected cancellation") } catch(_:CancellationException) { }
        assertNull(cancellable.snapshot())
    }
}
