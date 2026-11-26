package com.example.rotaryisolator

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Free-positioning host for migration of FREQUALIZER controls away from
 * section/zone-based LinearLayouts. Each direct child has its own stable ID,
 * normalized rectangle and z-order. It deliberately knows nothing about audio.
 *
 * Child views keep their own listeners, tags, state and parameter bindings.
 */
internal class FreeLayoutHost(
    context: Context,
    private val profile: UiLayoutProfile,
    private val store: UiLayoutProfileStore
) : FrameLayout(context) {

    private val placements = LinkedHashMap<String, UiElementPlacement>()

    init {
        clipChildren = false
        clipToPadding = false
        placements.putAll(store.load(profile))
    }

    fun addElement(
        elementId: String,
        view: View,
        defaultPlacement: UiElementPlacement
    ) {
        require(elementId.isNotBlank()) { "A stable element ID is required" }
        require(placements[elementId] == null || children.none { it.tag == elementId }) {
            "Duplicate UI element ID: $elementId"
        }
        val placement = (placements[elementId] ?: defaultPlacement.copy(elementId = elementId)).normalized()
        placements[elementId] = placement
        view.tag = elementId
        addView(view)
        requestLayout()
    }

    fun placementOf(elementId: String): UiElementPlacement? = placements[elementId]

    fun updatePlacement(placement: UiElementPlacement) {
        if (placement.elementId.isBlank()) return
        placements[placement.elementId] = placement.normalized()
        requestLayout()
    }

    fun saveProfile() {
        store.save(profile, placements.values)
    }

    fun allPlacements(): List<UiElementPlacement> = placements.values.toList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val placement = placements[child.tag as? String] ?: continue
            val childWidth = (width * placement.width).roundToInt().coerceAtLeast(1)
            val childHeight = (height * placement.height).roundToInt().coerceAtLeast(1)
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val placement = placements[child.tag as? String] ?: continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val x = (placement.x * width).roundToInt().coerceIn(0, (width - childWidth).coerceAtLeast(0))
            val y = (placement.y * height).roundToInt().coerceIn(0, (height - childHeight).coerceAtLeast(0))
            child.layout(x, y, x + childWidth, y + childHeight)
            child.visibility = if (placement.visible) View.VISIBLE else View.GONE
            child.translationZ = placement.zOrder.toFloat()
        }
    }

    /** Snapshot placements after a host resize without changing element identity. */
    fun normalizedSnapshot(): List<UiElementPlacement> {
        val width = width.coerceAtLeast(1).toFloat()
        val height = height.coerceAtLeast(1).toFloat()
        return placements.values.map { placement ->
            val view = children.firstOrNull { it.tag == placement.elementId }
            if (view == null) placement else placement.copy(
                x = (view.left / width).coerceIn(0f, 1f),
                y = (view.top / height).coerceIn(0f, 1f),
                width = (view.width / width).coerceIn(0.01f, 1f),
                height = (view.height / height).coerceIn(0.01f, 1f)
            )
        }
    }
}
