package com.example.rotaryisolator

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Independent placement data for one complete device/orientation layout.
 *
 * Coordinates are normalized to the usable content area. A placement belongs
 * to one UI element ID; it is never assigned to a section or a forced zone.
 * This model is deliberately separate from audio preferences and DSP state.
 */
internal data class UiElementPlacement(
    val elementId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val zOrder: Int = 0,
    val sizePreset: Int = 2,
    val visible: Boolean = true
) {
    fun normalized(): UiElementPlacement {
        // Reject NaN/infinite coordinates from malformed or partially edited
        // profiles before clamping. Keep the complete rectangle on-screen.
        val safeX = (if (x.isFinite()) x else 0f).coerceIn(0f, 0.99f)
        val safeY = (if (y.isFinite()) y else 0f).coerceIn(0f, 0.99f)
        // Tiny but valid controls must remain resizable as part of a group.
        // A 1% floor prevents shrinking any element that is already smaller.
        val minimumSize = 0.0001f
        val remainingWidth = (1f - safeX).coerceAtLeast(minimumSize)
        val remainingHeight = (1f - safeY).coerceAtLeast(minimumSize)
        val safeWidth = (if (width.isFinite()) width else 0.1f)
            .coerceIn(minimumSize, remainingWidth)
        val safeHeight = (if (height.isFinite()) height else 0.1f)
            .coerceIn(minimumSize, remainingHeight)
        return copy(
            x = safeX,
            y = safeY,
            width = safeWidth,
            height = safeHeight,
            sizePreset = sizePreset.coerceIn(0, 5)
        )
    }
}

/** One profile is stored for each of the four supported device/orientation pairs. */
internal enum class UiLayoutProfile(val storageKey: String) {
    TABLET_LANDSCAPE("tablet_landscape"),
    TABLET_PORTRAIT("tablet_portrait"),
    PHONE_LANDSCAPE("phone_landscape"),
    PHONE_PORTRAIT("phone_portrait");

    companion object {
        fun detect(smallestScreenWidthDp: Int, widthDp: Int, heightDp: Int): UiLayoutProfile {
            val tablet = smallestScreenWidthDp >= 600
            val landscape = widthDp > heightDp
            return when {
                tablet && landscape -> TABLET_LANDSCAPE
                tablet -> TABLET_PORTRAIT
                landscape -> PHONE_LANDSCAPE
                else -> PHONE_PORTRAIT
            }
        }
    }
}

/**
 * Persistence for free-form UI placements. It uses its own preference file:
 * audio parameters, effect settings and DSP preferences remain untouched.
 */
internal class UiLayoutProfileStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("frequalizer_ui_layout_profiles_v1", Context.MODE_PRIVATE)

    fun load(profile: UiLayoutProfile): Map<String, UiElementPlacement> {
        val raw = preferences.getString(profile.storageKey, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            val result = LinkedHashMap<String, UiElementPlacement>()
            val ids = root.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                val item = root.optJSONObject(id) ?: continue
                if (id.isBlank()) continue
                result[id] = UiElementPlacement(
                    elementId = id,
                    x = item.optDouble("x", 0.0).toFloat(),
                    y = item.optDouble("y", 0.0).toFloat(),
                    width = item.optDouble("width", 0.1).toFloat(),
                    height = item.optDouble("height", 0.1).toFloat(),
                    zOrder = item.optInt("zOrder", 0),
                    sizePreset = item.optInt("sizePreset", 2),
                    visible = item.optBoolean("visible", true)
                ).normalized()
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(profile: UiLayoutProfile, placements: Collection<UiElementPlacement>): Boolean {
        if (placements.isEmpty()) return false
        val root = JSONObject()
        placements.forEach { placement ->
            if (placement.elementId.isBlank()) return@forEach
            val item = placement.normalized()
            root.put(item.elementId, JSONObject()
                .put("x", item.x.toDouble())
                .put("y", item.y.toDouble())
                .put("width", item.width.toDouble())
                .put("height", item.height.toDouble())
                .put("zOrder", item.zOrder)
                .put("sizePreset", item.sizePreset)
                .put("visible", item.visible))
        }
        if (root.length() == 0) return false
        val serialized = root.toString()
        val committed = preferences.edit().putString(profile.storageKey, serialized).commit()
        return committed && preferences.getString(profile.storageKey, null) == serialized
    }

    fun clear(profile: UiLayoutProfile) {
        preferences.edit().remove(profile.storageKey).apply()
    }

    /** Export every device/orientation profile as one versioned, portable project. */
    fun exportProject(): String {
        val profiles = JSONObject()
        UiLayoutProfile.values().forEach { profile ->
            val placements = JSONObject()
            load(profile).values.forEach { placement ->
                val item = placement.normalized()
                placements.put(item.elementId, JSONObject()
                    .put("x", item.x.toDouble())
                    .put("y", item.y.toDouble())
                    .put("width", item.width.toDouble())
                    .put("height", item.height.toDouble())
                    .put("zOrder", item.zOrder)
                    .put("sizePreset", item.sizePreset)
                    .put("visible", item.visible))
            }
            profiles.put(profile.storageKey, placements)
        }
        val counts = JSONObject()
        UiLayoutProfile.values().forEach { profile ->
            counts.put(profile.storageKey, profiles.optJSONObject(profile.storageKey)?.length() ?: 0)
        }
        return JSONObject()
            .put("format", "FREQUALIZER_LAYOUT_PROJECT")
            .put("schemaVersion", 1)
            .put("profiles", profiles)
            .put("elementCounts", counts)
            .put("exportedAtEpochMs", System.currentTimeMillis())
            .toString(2)
    }

    /**
     * Validate the entire project before writing anything. The preferences update
     * is committed atomically and touches only this layout store, never audio prefs.
     */
    fun importProject(json: String, fallbackProfile: UiLayoutProfile = UiLayoutProfile.TABLET_LANDSCAPE): Int {
        require(json.length <= 5_000_000) { "File progetto troppo grande" }
        val root = JSONObject(json)
        val format = root.optString("format", "")
        require(format.isBlank() || format == "FREQUALIZER_LAYOUT_PROJECT") { "Formato progetto non riconosciuto" }
        val schemaVersion = root.optInt("schemaVersion", 1)
        require(schemaVersion in 0..2) { "Versione progetto futura non supportata: $schemaVersion" }

        // Accept both the current envelope and earlier exports that used
        // "layouts", omitted empty orientations, or stored profiles at root.
        val profileSource = root.optJSONObject("profiles")
            ?: root.optJSONObject("layouts")
            ?: root
        val parsed = LinkedHashMap<UiLayoutProfile, String>()
        val recognized = listOf(
            UiLayoutProfile.TABLET_LANDSCAPE to listOf("tablet_landscape", "tabletLandscape", "landscape_tablet"),
            UiLayoutProfile.TABLET_PORTRAIT to listOf("tablet_portrait", "tabletPortrait", "portrait_tablet"),
            UiLayoutProfile.PHONE_LANDSCAPE to listOf("phone_landscape", "phoneLandscape", "landscape_phone"),
            UiLayoutProfile.PHONE_PORTRAIT to listOf("phone_portrait", "phonePortrait", "portrait_phone")
        )
        var total = 0

        fun readPlacements(source: JSONObject, profile: UiLayoutProfile): String {
            val clean = JSONObject()
            val keys = source.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                if (id.isBlank() || id == "format" || id == "schemaVersion" ||
                    id == "exportedAtEpochMs" || id == "elementCounts") continue
                val item = source.optJSONObject(id) ?: continue
                require(id.length <= 240) { "ID elemento non valido" }
                // Legacy files used both width/height and w/h.
                val x = item.optDouble("x", item.optDouble("left", Double.NaN))
                val y = item.optDouble("y", item.optDouble("top", Double.NaN))
                val width = item.optDouble("width", item.optDouble("w", Double.NaN))
                val height = item.optDouble("height", item.optDouble("h", Double.NaN))
                require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()) {
                    "Coordinate non valide per $id"
                }
                val placement = UiElementPlacement(
                    elementId = id,
                    x = x.toFloat(),
                    y = y.toFloat(),
                    width = width.toFloat(),
                    height = height.toFloat(),
                    zOrder = item.optInt("zOrder", item.optInt("z", 0)),
                    sizePreset = item.optInt("sizePreset", 2),
                    visible = item.optBoolean("visible", true)
                )
                val safe = placement.normalized()
                clean.put(id, JSONObject()
                    .put("x", safe.x.toDouble())
                    .put("y", safe.y.toDouble())
                    .put("width", safe.width.toDouble())
                    .put("height", safe.height.toDouble())
                    .put("zOrder", safe.zOrder)
                    .put("sizePreset", safe.sizePreset)
                    .put("visible", safe.visible))
                total++
                require(total <= 5000) { "Troppi elementi nel progetto" }
            }
            return clean.toString()
        }

        recognized.forEach { (profile, aliases) ->
            val source = aliases.firstNotNullOfOrNull { key -> profileSource.optJSONObject(key) }
            if (source != null) parsed[profile] = readPlacements(source, profile)
        }

        // Very old single-layout exports did not wrap their elements in a
        // profiles object. Import them into the currently selected orientation.
        if (parsed.isEmpty()) {
            val legacySingle = root.optJSONObject("placements")
                ?: root.optJSONObject("elements")
                ?: root.optJSONObject("layout")
                ?: if (format.isBlank()) root else null
            if (legacySingle != null) parsed[fallbackProfile] = readPlacements(legacySingle, fallbackProfile)
        }

        require(parsed.isNotEmpty()) { "Nessun layout compatibile trovato nel file JSON" }
        require(total > 0) { "Il progetto non contiene elementi validi: nessun layout è stato modificato" }

        // Keep the existing profiles not present in a partial legacy export.
        val backup = JSONObject()
        UiLayoutProfile.values().forEach { profile ->
            val previous = preferences.getString(profile.storageKey, null)
            if (previous != null) backup.put(profile.storageKey, previous)
        }
        val editor = preferences.edit().putString("last_import_backup_v1", backup.toString())
        parsed.forEach { (profile, value) ->
            if (JSONObject(value).length() > 0) editor.putString(profile.storageKey, value)
        }
        check(editor.commit()) { "Impossibile salvare il progetto importato; i layout precedenti sono rimasti invariati" }

        parsed.forEach { (profile, value) ->
            if (JSONObject(value).length() > 0) {
                val stored = preferences.getString(profile.storageKey, null)
                check(stored != null && JSONObject(stored).length() == JSONObject(value).length()) {
                    "Verifica del profilo ${profile.storageKey} non riuscita"
                }
            }
        }
        return total
    }

    /** Restore the full snapshot captured immediately before the last import. */
    fun restoreLastImportBackup(): Boolean {
        val raw = preferences.getString("last_import_backup_v1", null) ?: return false
        val backup = JSONObject(raw)
        val editor = preferences.edit()
        UiLayoutProfile.values().forEach { profile ->
            if (backup.has(profile.storageKey)) {
                editor.putString(profile.storageKey, backup.getString(profile.storageKey))
            } else {
                editor.remove(profile.storageKey)
            }
        }
        return editor.commit()
    }
}
