package com.indigo.mobileobservatory.mount

import com.indigo.mobileobservatory.pointing.AxisReadingKind
import com.indigo.mobileobservatory.pointing.MountAxisObservation
import com.indigo.mobileobservatory.pointing.MountModelRevision
import com.indigo.mobileobservatory.pointing.PointingMountCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MountAccessMode { READ_WRITE, READ_ONLY }

data class MountRawAxisCoordinates(val axis1Deg: Double, val axis2Deg: Double,
    val resolutionDeg: Double, val kind: AxisReadingKind)
data class MountSessionCoordinates(val coordinates: MountCoordinates, val axes: MountRawAxisCoordinates? = null)

/** Protocol facts and software access policy are independent. No model-name inference. */
internal class MountPointingAccess {
    private val _access = MutableStateFlow(MountAccessMode.READ_WRITE)
    val accessMode = _access.asStateFlow()
    private val _capabilities = MutableStateFlow(PointingMountCapabilities())
    val capabilities = _capabilities.asStateFlow()
    private val _revision = MutableStateFlow(MountModelRevision(0, 0, 0, "UNKNOWN"))
    val revision = _revision.asStateFlow()
    private val _axis = MutableStateFlow<MountAxisObservation?>(null)
    val axisObservation = _axis.asStateFlow()
    private var connected = false
    private var protocolCapabilities = PointingMountCapabilities()

    @Synchronized fun setAccessMode(mode: MountAccessMode) {
        if (_access.value == mode) return
        _access.value = mode
        modelChanged()
        publish()
    }
    fun requireWritable() { check(_access.value == MountAccessMode.READ_WRITE) { "Mount connection is read-only." } }
    fun requireWireAllowed(payload: ByteArray) {
        if (_access.value == MountAccessMode.READ_WRITE) return
        check(MountReadOnlyWirePolicy.permits(payload)) { "Mount connection is read-only." }
    }

    @Synchronized fun connected(capabilities: PointingMountCapabilities, mode: String) {
        if (!connected) {
            _revision.value = MountModelRevision(_revision.value.connection + 1, 0, 0, mode)
            _axis.value = null
        } else if (mode != _revision.value.mode || capabilities != protocolCapabilities) {
            _revision.value = _revision.value.copy(model = _revision.value.model + 1, mode = mode)
            _axis.value = null
        }
        connected = true; protocolCapabilities = capabilities; publish()
    }
    @Synchronized fun disconnected() {
        connected = false; protocolCapabilities = PointingMountCapabilities()
        _revision.value = _revision.value.copy(connection = _revision.value.connection + 1, mode = "UNKNOWN")
        _axis.value = null; publish()
    }
    @Synchronized fun modelChanged(zero: Boolean = false) {
        _revision.value = _revision.value.copy(model = _revision.value.model + 1,
            zero = _revision.value.zero + if (zero) 1 else 0)
        _axis.value = null
    }
    @Synchronized fun observe(axes: MountRawAxisCoordinates?, observedRevision: MountModelRevision, startedMs: Long, endedMs: Long) {
        if (axes == null || !connected || observedRevision != _revision.value || endedMs < startedMs ||
            axes.kind != _capabilities.value.axisReading || axes.kind == AxisReadingKind.NONE ||
            !axes.axis1Deg.isFinite() || !axes.axis2Deg.isFinite() || !axes.resolutionDeg.isFinite() || axes.resolutionDeg <= 0) return
        _axis.value = MountAxisObservation(axes.axis1Deg, axes.axis2Deg, startedMs + (endedMs - startedMs) / 2,
            axes.resolutionDeg, axes.kind, observedRevision)
    }
    private fun publish() {
        _capabilities.value = if (!connected) PointingMountCapabilities() else if (_access.value == MountAccessMode.READ_WRITE) protocolCapabilities
        else protocolCapabilities.copy(goto = false, sync = false, tracking = false, manualMove = false,
            home = false, setHome = false, writeSite = false, writeTime = false)
    }
}

/** Only implemented query packets and stops are allowed; unknown commands fail closed. */
internal object MountReadOnlyWirePolicy {
    fun permits(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val text = payload.toString(Charsets.US_ASCII)
        if (text.startsWith(":") && text.endsWith("#")) {
            return text in setOf(":GR#", ":GD#", ":Gt#", ":Gg#", ":GC#", ":GL#", ":GUT#", ":GLS#", ":GEP#",
                ":GVP#", ":GVN#", ":GVR#", ":MountInfo#", ":Q#", ":Qn#", ":Qs#", ":Qe#", ":Qw#",
                ":qD#", ":qR#", ":Td#", ":ST0#")
        }
        if (text.startsWith(":") && text.endsWith("\r") && payload.size == 4) {
            return text[1] in "eabgjfKL" && text[2] in "12"
        }
        if (text in setOf("Kx", "J", "m", "e", "w", "h", "p")) return true
        if (payload.size == 2 && payload[0] == 'T'.code.toByte() && payload[1] == 0.toByte()) return true
        return payload.size == 8 && payload[0] == 'P'.code.toByte() && payload[1].toInt() == 2 &&
            payload[2].toInt() in 16..17 && payload[3].toInt() in 36..37 &&
            payload.drop(4).all { it == 0.toByte() }
    }
}
