package com.indigo.mobileobservatory.stacking
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.recording.RawRecordingNormalizer

fun normalizeOperation(frame:FrameData,traits:PixelTraits)=RawRecordingNormalizer.normalize(frame,traits)
fun demosaicOperation(source:FloatArray,width:Int,height:Int):()->LinearImage = { DdfapdDemosaicer.demosaic(source,width,height,0,0) }
fun decoderOperation():(FrameData)->LinearImage = { LinearPixelDecoder.decode(it,PixelTraits(alignment=SampleAlignment.RIGHT),algorithm=DemosaicAlgorithm.DDFAPD) }
fun starDetectionOperation(image:LinearImage):()->StarField = { StarRegistration.detect(image) }
fun registrationOperation(registration:PlanetaryRegistration,image:LinearImage):Double {
    val alignment=registration.align(image)
    val warp=PlanetaryWarp(image.width,image.height,alignment)
    return registration.edgeResidual(image,warp)+alignment.global.correlation
}
fun previewOperation(image:LinearImage):()->Double {
    var frame=0
    return {
        val p=StackProcessingParameters.preset(ProcessingPreset.NATURAL).copy(brightness=.8+(frame++%5)*.1)
        val processed=StackPostProcessor.process(image,p).image
        var sum=0.0
        for(y in 0 until image.height) for(x in 0 until image.width) {
            val v=processed.pixels[(y*image.width+x)*3]
            sum+=(v.coerceIn(0f,1f)*255f+.5f).toInt()
        }
        sum
    }
}
