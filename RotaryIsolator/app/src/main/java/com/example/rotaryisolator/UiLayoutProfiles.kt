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
        val safeX = (if (x.isFinite()) x else 0f).coerceIn(0f, 1f)
        val safeY = (if (y.isFinite()) y else 0f).coerceIn(0f, 1f)
        val remainingWidth = (1f - safeX).coerceAtLeast(0.01f)
        val remainingHeight = (1f - safeY).coerceAtLeast(0.01f)
        val safeWidth = (if (width.isFinite()) width else 0.1f)
            .coerceIn(0.01f, remainingWidth)
        val safeHeight = (if (height.isFinite()) height else 0.1f)
            .coerceIn(0.01f, remainingHeight)
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
}
