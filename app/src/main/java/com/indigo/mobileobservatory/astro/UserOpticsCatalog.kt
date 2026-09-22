package com.indigo.mobileobservatory.astro

import java.util.Locale

/**
 * User-editable telescope and camera lists for the star-map FOV sheet.
 * Built-in catalogs seed the first run; after that the named copies are the source of truth.
 */
object UserOpticsCatalog {
    const val TELESCOPES_KEY = "star_map_user_telescopes"
    const val CAMERAS_KEY = "star_map_user_cameras"

    fun loadTelescopes(stored: String?): List<TelescopeSpec> {
        val parsed = parseTelescopes(stored)
        return parsed.ifEmpty { seedTelescopes() }
    }

    fun loadCameras(stored: String?): List<SensorSpec> {
        val parsed = parseCameras(stored)
        return parsed.ifEmpty { seedCameras() }
    }

    fun seedTelescopes(): List<TelescopeSpec> = OpticsEquipment.defaultTelescopes

    fun seedCameras(): List<SensorSpec> = OpticsEquipment.defaultSensors

    fun formatTelescopes(list: List<TelescopeSpec>): String =
        list.joinToString("\n") { spec ->
            encode(
                spec.id,
                spec.name,
                formatNumber(spec.focalLengthMm),
                spec.apertureMm?.let(::formatNumber).orEmpty()
            )
        }

    fun formatCameras(list: List<SensorSpec>): String =
        list.joinToString("\n") { spec ->
            encode(
                spec.id,
                spec.name,
                formatNumber(spec.pixelSizeUm),
                spec.widthPx.toString(),
                spec.heightPx.toString()
            )
        }

    fun parseTelescopes(stored: String?): List<TelescopeSpec> =
        decodeRecords(stored).mapNotNull { cols ->
            val id = cols.getOrNull(0)?.trim().orEmpty()
            val name = cols.getOrNull(1)?.trim().orEmpty()
            val fl = cols.getOrNull(2)?.toDoubleOrNull()
            if (id.isEmpty() || name.isEmpty() || fl == null || fl <= 0.0) return@mapNotNull null
            TelescopeSpec(
                id = id,
                name = name,
                focalLengthMm = fl,
                apertureMm = cols.getOrNull(3)?.toDoubleOrNull()?.takeIf { it > 0.0 }
            )
        }

    fun parseCameras(stored: String?): List<SensorSpec> =
        decodeRecords(stored).mapNotNull { cols ->
            val id = cols.getOrNull(0)?.trim().orEmpty()
            val name = cols.getOrNull(1)?.trim().orEmpty()
            val um = cols.getOrNull(2)?.toDoubleOrNull()
            val width = cols.getOrNull(3)?.toIntOrNull()
            val height = cols.getOrNull(4)?.toIntOrNull()
            if (
                id.isEmpty() ||
                id == OpticsEquipment.CONNECTED_SENSOR_ID ||
                name.isEmpty() ||
                um == null || um <= 0.0 ||
                width == null || width <= 0 ||
                height == null || height <= 0
            ) {
                return@mapNotNull null
            }
            SensorSpec(id, name, um, width, height)
        }

    fun uniqueName(existing: Collection<String>, base: String): String {
        val trimmed = base.trim().ifEmpty { base }
        val names = existing.map { it.trim() }.toSet()
        if (trimmed !in names) return trimmed
        var n = 2
        while ("$trimmed $n" in names) n++
        return "$trimmed $n"
    }

    fun addTelescope(
        current: List<TelescopeSpec>,
        name: String,
        focalLengthMm: Double,
        apertureMm: Double?,
        id: String
    ): List<TelescopeSpec> {
        val spec = TelescopeSpec(
            id = id,
            name = uniqueName(current.map { it.name }, name),
            focalLengthMm = focalLengthMm.takeIf { it > 0.0 } ?: 500.0,
            apertureMm = apertureMm?.takeIf { it > 0.0 }
        )
        return current + spec
    }

    fun addCamera(
        current: List<SensorSpec>,
        name: String,
        pixelSizeUm: Double,
        widthPx: Int,
        heightPx: Int,
        id: String
    ): List<SensorSpec> {
        val spec = SensorSpec(
            id = id,
            name = uniqueName(current.map { it.name }, name),
            pixelSizeUm = pixelSizeUm.takeIf { it > 0.0 } ?: 3.76,
            widthPx = widthPx.takeIf { it > 0 } ?: 1920,
            heightPx = heightPx.takeIf { it > 0 } ?: 1080
        )
        return current + spec
    }

    fun replaceTelescope(current: List<TelescopeSpec>, updated: TelescopeSpec): List<TelescopeSpec> {
        val previous = current.firstOrNull { it.id == updated.id } ?: return current
        val name = updated.name.trim().ifEmpty { previous.name }
        val fl = updated.focalLengthMm.takeIf { it > 0.0 } ?: previous.focalLengthMm
        return current.map {
            if (it.id == updated.id) updated.copy(name = name, focalLengthMm = fl) else it
        }
    }

    fun replaceCamera(current: List<SensorSpec>, updated: SensorSpec): List<SensorSpec> {
        if (updated.id == OpticsEquipment.CONNECTED_SENSOR_ID) return current
        val previous = current.firstOrNull { it.id == updated.id } ?: return current
        val name = updated.name.trim().ifEmpty { previous.name }
        val um = updated.pixelSizeUm.takeIf { it > 0.0 } ?: previous.pixelSizeUm
        val width = updated.widthPx.takeIf { it > 0 } ?: previous.widthPx
        val height = updated.heightPx.takeIf { it > 0 } ?: previous.heightPx
        return current.map {
            if (it.id == updated.id) {
                updated.copy(name = name, pixelSizeUm = um, widthPx = width, heightPx = height)
            } else {
                it
            }
        }
    }

    fun removeTelescope(current: List<TelescopeSpec>, id: String): List<TelescopeSpec> {
        if (current.size <= 1) return current
        val next = current.filter { it.id != id }
        return next.ifEmpty { current }
    }

    fun removeCamera(current: List<SensorSpec>, id: String): List<SensorSpec> {
        if (id == OpticsEquipment.CONNECTED_SENSOR_ID) return current
        if (current.size <= 1) return current
        val next = current.filter { it.id != id }
        return next.ifEmpty { current }
    }

    fun newId(prefix: String, nowMs: Long): String = "${prefix}_$nowMs"

    fun displayName(
        config: OpticsTrainConfig,
        telescopes: List<TelescopeSpec>,
        sensors: List<SensorSpec>,
        fallback: String
    ): String {
        val named = when (config.mode) {
            FovInstrumentMode.EYEPIECE ->
                telescopes.firstOrNull { it.id == config.telescopeId }?.name
            FovInstrumentMode.SENSOR ->
                sensors.firstOrNull { it.id == config.sensorId }?.name
        }
        return named?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    }

    private fun encode(vararg fields: String): String =
        fields.joinToString("\t") { field ->
            field.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        }

    private fun decodeRecords(stored: String?): List<List<String>> {
        if (stored.isNullOrBlank()) return emptyList()
        return stored.lineSequence()
            .map { it.split('\t') }
            .filter { it.isNotEmpty() && it[0].isNotBlank() }
            .toList()
    }

    private fun formatNumber(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            "%.4f".format(Locale.US, value).trimEnd('0').trimEnd('.')
        }
    }
}
