package com.indigo.mobileobservatory.camera

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Two app-cache descriptors stand in for cameras; no actual USB device is opened or closed. */
@RunWith(AndroidJUnit4::class)
class UsbFdOwnershipInstrumentedTest {
    @Test fun releasingOneConnectionKeepsTheOtherRegistrationAndDoubleCloseCannotReleaseIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = File(context.cacheDir, "wf-usb-ownership").apply { mkdirs() }
        val file = File(cache, "descriptor.bin").apply { writeBytes(byteArrayOf(18, 1, 0, 2, 0, 0, 0, 64)) }
        val register = UsbHelper.javaClass.getDeclaredMethod("nativeRegisterUsbFd", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val unregister = UsbHelper.javaClass.getDeclaredMethod("nativeUnregisterUsbFd",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val has = UsbHelper.javaClass.getDeclaredMethod("nativeHasUsbFd",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { first ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { second ->
                try {
                    assertEquals(true, register.invoke(UsbHelper, cache.absolutePath, 250, 250, first.fd))
                    assertEquals(true, register.invoke(UsbHelper, cache.absolutePath, 250, 251, second.fd))
                    assertEquals(true, has.invoke(UsbHelper, 250, 250)); assertEquals(true, has.invoke(UsbHelper, 250, 251))
                    unregister.invoke(UsbHelper, 250, 250)
                    assertEquals(false, has.invoke(UsbHelper, 250, 250)); assertEquals(true, has.invoke(UsbHelper, 250, 251))
                    unregister.invoke(UsbHelper, 250, 250)
                    assertEquals(true, has.invoke(UsbHelper, 250, 251))
                } finally {
                    unregister.invoke(UsbHelper, 250, 250); unregister.invoke(UsbHelper, 250, 251)
                }
            }
        }
    }
}
