package com.indigo.mobileobservatory.sequence.catalog

import com.indigo.mobileobservatory.sequence.NinaValue
import com.indigo.mobileobservatory.sequence.SequenceSlot
import com.indigo.mobileobservatory.sequence.putExpression

enum class SupportLevel { Execute, Pause, Retain }

enum class SequenceDevice {
    Camera,
    CameraCooling,
    DewHeater,
    FilterWheel,
    Focuser,
    FocuserTemperature,
    Guider,
    Mount,
    Cover,
    Rotator,
    FlatPanel,
    Dome,
    SafetyMonitor,
    Switch
}

enum class FieldKind { Number, Text, Bool, Expression }

data class FieldSpec(
    val key: String,
    val kind: FieldKind,
    val labelZh: String,
    val labelEn: String,
    val defaultNumber: Double? = null,
    val defaultText: String? = null,
    val defaultBool: Boolean? = null,
    val integral: Boolean = true,
    val min: Double? = null,
    val max: Double? = null,
    val unit: String? = null,
    val proxyPath: String? = null
) {
    val editPath: String get() = proxyPath ?: key

    fun write(fields: LinkedHashMap<String, NinaValue>) {
        when (kind) {
            FieldKind.Expression -> {
                val value = defaultNumber ?: 0.0
                putExpression(fields, key, value)
            }
            FieldKind.Bool -> fields[key] = NinaValue.Bool(defaultBool ?: false)
            FieldKind.Text -> fields[key] = NinaValue.Text(defaultText.orEmpty())
            FieldKind.Number -> {
                val value = defaultNumber ?: 0.0
                fields[key] = NinaValue.Num(value, integral || value % 1.0 == 0.0)
            }
        }
    }
}

data class SequenceTypeSpec(
    val id: String,
    val type: String,
    val slot: SequenceSlot,
    val groupZh: String,
    val groupEn: String,
    val titleZh: String,
    val titleEn: String,
    val level: SupportLevel,
    val fields: List<FieldSpec> = emptyList(),
    val device: SequenceDevice? = null,
    val isSet: Boolean = false,
    val listed: Boolean = true,
    val hiddenByDefault: Boolean = level == SupportLevel.Retain,
    val factory: () -> com.indigo.mobileobservatory.sequence.NinaNode
)

fun ninaType(path: String): String = "NINA.Sequencer.$path, NINA.Sequencer"
