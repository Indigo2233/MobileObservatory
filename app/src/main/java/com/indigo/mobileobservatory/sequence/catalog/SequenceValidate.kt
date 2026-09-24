package com.indigo.mobileobservatory.sequence.catalog

import com.indigo.mobileobservatory.sequence.NinaNode
import com.indigo.mobileobservatory.sequence.NinaValue
import com.indigo.mobileobservatory.sequence.collectionNodes
import com.indigo.mobileobservatory.sequence.doubleField
import com.indigo.mobileobservatory.sequence.expressionIsUnsupported
import com.indigo.mobileobservatory.sequence.expressionNumber

data class SequenceHardwareSnapshot(
    val cameraConnected: Boolean = false,
    val coolingCapable: Boolean = false,
    val dewHeater: Boolean = false,
    val filterWheelConnected: Boolean = false,
    val focuserConnected: Boolean = false,
    val focuserHasTemperature: Boolean = false,
    val guiderConnected: Boolean = false,
    val mountConnected: Boolean = false,
    val coverConnected: Boolean = false,
    val rotatorConnected: Boolean = false,
    val flatPanelConnected: Boolean = false
)

data class SequenceIssue(
    val nodeId: String?,
    val className: String,
    val messageZh: String,
    val messageEn: String
)

fun validateSequence(
    root: NinaNode,
    hardware: SequenceHardwareSnapshot = SequenceHardwareSnapshot()
): List<SequenceIssue> {
    val issues = ArrayList<SequenceIssue>()
    fun walk(node: NinaNode) {
        issues += validateSequenceNode(node, hardware)
        listOf("Items", "Conditions", "Triggers").forEach { field ->
            node.collectionNodes(field).forEach { walk(it) }
        }
        val runner = (node.fields["TriggerRunner"] as? NinaValue.Obj)?.node
        if (runner != null) walk(runner)
    }
    walk(root)
    return issues
}

fun validateSequenceNode(
    node: NinaNode,
    hardware: SequenceHardwareSnapshot = SequenceHardwareSnapshot()
): List<SequenceIssue> {
    val spec = SequenceCatalog.specByClass(node.className) ?: return emptyList()
    val issues = ArrayList<SequenceIssue>()
    when (spec.level) {
        SupportLevel.Pause -> issues += issue(node, "本机暂不支持", "Not supported on this device yet")
        SupportLevel.Retain -> issues += issue(node, "本机暂不支持", "Not supported on this device yet")
        SupportLevel.Execute -> if (!deviceAvailable(spec.device, hardware)) {
            issues += issue(node, "设备未连接", "Required device is not connected")
        }
    }
    spec.fields.forEach { field ->
        if (field.kind == FieldKind.Expression && expressionIsUnsupported(node, field.key)) {
            issues += issue(node, "含表达式，本应用暂不支持", "Expression is not supported")
        }
        val value = when {
            field.proxyPath != null -> {
                val parts = field.proxyPath.split('.')
                var current: NinaNode? = node
                for (part in parts.dropLast(1)) {
                    current = (current?.fields?.get(part) as? NinaValue.Obj)?.node
                }
                current?.let { expressionNumber(it, parts.last()) ?: it.doubleField(parts.last()) }
            }
            field.kind == FieldKind.Expression -> expressionNumber(node, field.key)
            else -> node.doubleField(field.key)
        }
        if (value != null) {
            if (field.min != null && value < field.min) {
                issues += issue(node, "参数低于下限 ${field.min}", "Value is below ${field.min}")
            }
            if (field.max != null && value > field.max) {
                issues += issue(node, "参数超出上限 ${field.max}", "Value is above ${field.max}")
            }
        }
    }
    return issues
}

private fun deviceAvailable(device: SequenceDevice?, hardware: SequenceHardwareSnapshot): Boolean = when (device) {
    null -> true
    SequenceDevice.Camera -> hardware.cameraConnected
    SequenceDevice.CameraCooling -> hardware.cameraConnected && hardware.coolingCapable
    SequenceDevice.DewHeater -> hardware.cameraConnected && hardware.dewHeater
    SequenceDevice.FilterWheel -> hardware.filterWheelConnected
    SequenceDevice.Focuser -> hardware.focuserConnected
    SequenceDevice.FocuserTemperature -> hardware.focuserConnected && hardware.focuserHasTemperature
    SequenceDevice.Guider -> hardware.guiderConnected
    SequenceDevice.Mount -> hardware.mountConnected
    SequenceDevice.Cover -> hardware.coverConnected
    SequenceDevice.Rotator -> hardware.rotatorConnected
    SequenceDevice.FlatPanel -> hardware.flatPanelConnected
    SequenceDevice.Dome, SequenceDevice.SafetyMonitor, SequenceDevice.Switch -> false
}

private fun issue(node: NinaNode, zh: String, en: String) = SequenceIssue(node.id, node.className, zh, en)
