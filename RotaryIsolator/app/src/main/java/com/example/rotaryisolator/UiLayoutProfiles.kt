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

    fun save(profile: UiLayoutProfile, placements: Collection<UiElementPlacement>) {
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
        preferences.edit().putString(profile.storageKey, root.toString()).apply()
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
        return JSONObject()
            .put("format", "FREQUALIZER_LAYOUT_PROJECT")
            .put("schemaVersion", 1)
            .put("profiles", profiles)
            .toString(2)
    }

    /**
     * Validate the entire project before writing anything. The preferences update
     * is committed atomically and touches only this layout store, never audio prefs.
     */
    fun importProject(json: String): Int {
        require(json.length <= 5_000_000) { "File progetto troppo grande" }
        val root = JSONObject(json)
        require(root.optString("format") == "FREQUALIZER_LAYOUT_PROJECT") { "Formato progetto non riconosciuto" }
        require(root.optInt("schemaVersion", -1) == 1) { "Versione progetto non supportata" }
        val profiles = root.optJSONObject("profiles")
            ?: throw IllegalArgumentException("Profili layout mancanti")
        val parsed = LinkedHashMap<UiLayoutProfile, String>()
        var total = 0
        UiLayoutProfile.values().forEach { profile ->
            val source = profiles.optJSONObject(profile.storageKey)
                ?: throw IllegalArgumentException("Profilo mancante: ${profile.storageKey}")
            val clean = JSONObject()
            val keys = source.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                require(id.isNotBlank() && id.length <= 240) { "ID elemento non valido" }
                val item = source.optJSONObject(id)
                    ?: throw IllegalArgumentException("Dati elemento non validi: $id")
                val placement = UiElementPlacement(
                    elementId = id,
                    x = item.getDouble("x").toFloat(),
                    y = item.getDouble("y").toFloat(),
                    width = item.getDouble("width").toFloat(),
                    height = item.getDouble("height").toFloat(),
                    zOrder = item.optInt("zOrder", 0),
                    sizePreset = item.optInt("sizePreset", 2),
                    visible = item.optBoolean("visible", true)
                )
                require(placement.x.isFinite() && placement.y.isFinite() &&
                    placement.width.isFinite() && placement.height.isFinite()) {
                    "Coordinate non valide per $id"
                }
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
            parsed[profile] = clean.toString()
        }
        val editor = preferences.edit()
        parsed.forEach { (profile, value) -> editor.putString(profile.storageKey, value) }
        check(editor.commit()) { "Impossibile salvare il progetto importato" }
        return total
    }
}
