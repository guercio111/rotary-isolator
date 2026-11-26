package com.example.rotaryisolator

import android.content.Context
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persistent, device/orientation-specific free-canvas layout data.
 *
 * Coordinates and dimensions are normalized to the canvas (0..1), so a
 * project can be restored on the same device class at a different resolution.
 * This class deliberately has no references to audio/DSP state.
 */
internal data class FreeformLayoutElement(
    val id: String,
    val kind: String,
    val title: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val zIndex: Int = 0,
    val locked: Boolean = false,
    val properties: Map<String, String> = emptyMap()
) {
    fun normalized(): FreeformLayoutElement = copy(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        width = width.coerceIn(0.01f, 1f),
        height = height.coerceIn(0.01f, 1f),
        rotation = rotation % 360f
    )

    fun toJson(): JSONObject {
        val props = JSONObject()
        properties.forEach { (key, value) -> props.put(key, value) }
        return JSONObject()
            .put("id", id)
            .put("kind", kind)
            .put("title", title)
            .put("x", x)
            .put("y", y)
            .put("width", width)
            .put("height", height)
            .put("rotation", rotation)
            .put("zIndex", zIndex)
            .put("locked", locked)
            .put("properties", props)
    }

    companion object {
        fun fromJson(json: JSONObject): FreeformLayoutElement {
            val propsJson = json.optJSONObject("properties") ?: JSONObject()
            val props = linkedMapOf<String, String>()
            val keys = propsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                props[key] = propsJson.optString(key, "")
            }
            return FreeformLayoutElement(
                id = json.getString("id"),
                kind = json.optString("kind", "panel"),
                title = json.optString("title", ""),
                x = json.optDouble("x", 0.05).toFloat(),
                y = json.optDouble("y", 0.05).toFloat(),
                width = json.optDouble("width", 0.25).toFloat(),
                height = json.optDouble("height", 0.25).toFloat(),
                rotation = json.optDouble("rotation", 0.0).toFloat(),
                zIndex = json.optInt("zIndex", 0),
                locked = json.optBoolean("locked", false),
                properties = props
            ).normalized()
        }
    }
}

internal object FreeformLayoutStore {
    private const val PREFS = "frequalizer_free_layout_v1"
    private const val PROJECT_SCHEMA = 1
    private const val KEY_PROJECT = "project_json"

    private val profileIds = listOf(
        "tablet_portrait",
        "tablet_landscape",
        "phone_portrait",
        "phone_landscape"
    )

    fun currentProfile(configuration: Configuration): String {
        val width = configuration.screenWidthDp
        val height = configuration.screenHeightDp
        val isPhone = minOf(width, height) in 1 until 600
        val orientation = if (width > height) "landscape" else "portrait"
        return (if (isPhone) "phone_" else "tablet_") + orientation
    }

    fun load(context: Context, profileId: String): List<FreeformLayoutElement> {
        if (profileId !in profileIds) return emptyList()
        val root = readProject(context)
        val array = root.optJSONArray(profileId) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching { add(FreeformLayoutElement.fromJson(item)) }
            }
        }
    }

    fun save(context: Context, profileId: String, elements: List<FreeformLayoutElement>) {
        require(profileId in profileIds) { "Unknown layout profile: $profileId" }
        val root = readProject(context)
        val array = JSONArray()
        elements.sortedBy { it.zIndex }.forEach { array.put(it.normalized().toJson()) }
        root.put(profileId, array)
        writeProject(context, root)
    }

    /** Exports a complete, portable record containing all four independent layouts. */
    fun exportProject(context: Context): String {
        val source = readProject(context)
        val output = JSONObject()
            .put("application", "FREQUALIZER")
            .put("schemaVersion", PROJECT_SCHEMA)
            .put("coordinateSystem", "normalized_0_to_1")
            .put("profiles", JSONObject())
        val profiles = output.getJSONObject("profiles")
        profileIds.forEach { id ->
            profiles.put(id, source.optJSONArray(id) ?: JSONArray())
        }
        return output.toString(2)
    }

    /** Imports only recognized profiles and validates every element before saving. */
    fun importProject(context: Context, jsonText: String) {
        val project = JSONObject(jsonText)
        require(project.optString("application") == "FREQUALIZER") { "Not a FREQUALIZER layout record" }
        require(project.optInt("schemaVersion", -1) == PROJECT_SCHEMA) { "Unsupported layout record version" }
        val profiles = project.getJSONObject("profiles")
        val clean = JSONObject()
        profileIds.forEach { id ->
            val source = profiles.optJSONArray(id) ?: JSONArray()
            val destination = JSONArray()
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                destination.put(FreeformLayoutElement.fromJson(item).toJson())
            }
            clean.put(id, destination)
        }
        writeProject(context, clean)
    }

    fun supportedProfiles(): List<String> = profileIds.toList()

    private fun readProject(context: Context): JSONObject {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECT, null)
        if (text.isNullOrBlank()) return JSONObject()
        return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
    }

    private fun writeProject(context: Context, project: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROJECT, project.toString())
            .apply()
    }
}
