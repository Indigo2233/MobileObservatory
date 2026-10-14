package com.indigo.mobileobservatory.media

import org.junit.Assert.*
import org.junit.Test

class ExternalMediaImporterTest {
    @Test fun onlySupportedContentTypesEnterTheExternalViewer() {
        assertEquals("ser", ExternalMediaImporter.supportedExtension("sky.SER", "application/octet-stream"))
        assertEquals("pser", ExternalMediaImporter.supportedExtension("sky.PSER", "application/octet-stream"))
        assertEquals("fits", ExternalMediaImporter.supportedExtension("opaque-document", "application/fits"))
        assertEquals("jpg", ExternalMediaImporter.supportedExtension(null, "image/jpeg"))
        assertEquals("png", ExternalMediaImporter.supportedExtension("image.png", "image/png"))
        assertNull(ExternalMediaImporter.supportedExtension("movie.mp4", "video/mp4"))
        assertNull(ExternalMediaImporter.supportedExtension("archive.zip", "application/octet-stream"))
        assertNull(ExternalMediaImporter.supportedExtension("opaque-document", "application/octet-stream"))
    }
}
