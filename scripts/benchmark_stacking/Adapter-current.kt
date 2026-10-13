package com.indigo.mobileobservatory.stacking
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.recording.RawRecordingNormalizer

fun normalizeOperation(frame:FrameData,traits:PixelTraits)=RawRecordingNormalizer.normalizeOwnedInPlace(frame,traits)
fun demosaicOperation(source:FloatArray,width:Int,height:Int):()->LinearImage {
    val workspace=DdfapdDemosaicer.Workspace()
    return { DdfapdDemosaicer.demosaicBorrowed(source,width,height,0,0,workspace) }
}
fun decoderOperation():(FrameData)->LinearImage {
    val workspace=DdfapdDemosaicer.Workspace()
    return {
        val traits=PixelTraits(alignment=SampleAlignment.RIGHT)
        val sensor=LinearPixelDecoder.decodeSensor(it,traits)
        LinearPixelDecoder.demosaicSensorBorrowed(sensor,it.pixelFormat,traits,workspace)
    }
}
fun registrationOperation(registration:PlanetaryRegistration,image:LinearImage):Double {
    val prepared=registration.prepareFrame(image)
    val alignment=registration.align(prepared)
    val warp=PlanetaryWarp(image.width,image.height,alignment)
    return registration.edgeResidual(prepared,warp)+alignment.global.correlation
}
fun previewOperation(image:LinearImage):()->Double {
    val cache=BackgroundModelCache();val row=FloatArray(image.width*3);var frame=0
    return {
        val p=StackProcessingParameters.preset(ProcessingPreset.NATURAL).copy(brightness=.8+(frame++%5)*.1)
        val prepared=StackPostProcessor.prepare(image,p,backgroundCache=cache)
        var sum=0.0
        for(y in 0 until image.height) {
            prepared.writePreviewRow(y,row)
            for(x in 0 until image.width) sum+=(row[x*3].coerceIn(0f,1f)*255f+.5f).toInt()
        }
        sum
    }
}
