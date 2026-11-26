package com.example.rotaryisolator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Free-positioning host for individually identified FREQUALIZER controls.
 * It does not own or modify audio state: child views retain their listeners,
 * callbacks and parameter bindings.
 */
internal class FreeLayoutHost(
    context: Context,
    private val profile: UiLayoutProfile,
    private val store: UiLayoutProfileStore
) : FrameLayout(context) {

    private val placements = LinkedHashMap<String, UiElementPlacement>()
    private var editMode = false
    private var selectedId: String? = null
    private var moving = false
    private var resizing = false
    private var downX = 0f
    private var downY = 0f
    private var startPlacement: UiElementPlacement? = null
    private val handleSize = 28f * resources.displayMetrics.density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0xFFFFD54F.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFD54F.toInt()
    }

    init {
        clipChildren = false
        clipToPadding = false
        placements.putAll(store.load(profile))
        setWillNotDraw(false)
    }


    /**
     * Detach the rendered controls from the legacy layout tree and register
     * each independently interactive leaf in this full-window host. The old
     * hierarchy is used only once to calculate the initial visual positions;
     * after adoption it no longer constrains movement or sizing.
     *
     * Clickable/focusable composite widgets and views with their own background
     * are kept intact so their existing listeners and drawing remain functional.
     */
    fun adoptLaidOutTree(source: ViewGroup, referenceScale: Float = 1f) {
        if (width <= 0 || height <= 0) return
        val hostLocation = IntArray(2)
        getLocationOnScreen(hostLocation)
        // Keep geometry tied to the original container while extracting its
        // artwork into independent decorative views. This prevents opaque
        // panel backgrounds from forcing all child controls to move as a group.
        data class Candidate(val view: View, val geometry: View, val proposedId: String)
        val candidates = ArrayList<Candidate>()

        fun cloneDrawable(drawable: android.graphics.drawable.Drawable?): android.graphics.drawable.Drawable? {
            if (drawable == null || drawable.alpha <= 0) return null
            // Some Android drawables (notably runtime-created GradientDrawable
            // instances) have no ConstantState. Keep their appearance rather than
            // silently dropping the panel artwork during extraction.
            return drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable.mutate()
        }

        fun collect(view: View, path: String) {
            val group = view as? ViewGroup
            val keepAsUnit = view !== source && (
                group == null ||
                view.isClickable ||
                view.isLongClickable ||
                view.isFocusable
            )
            if (group != null && !keepAsUnit) {
                val backgroundCopy = cloneDrawable(view.background)
                val foregroundCopy = if (android.os.Build.VERSION.SDK_INT >= 23) {
                    cloneDrawable(view.foreground)
                } else null
                if (backgroundCopy != null || foregroundCopy != null) {
                    val decoration = View(this@FreeLayoutHost.context).apply {
                        background = backgroundCopy
                        if (android.os.Build.VERSION.SDK_INT >= 23) foreground = foregroundCopy
                        alpha = view.alpha
                        elevation = view.elevation
                        translationZ = view.translationZ
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        isClickable = false
                        isFocusable = false
                    }
                    candidates.add(Candidate(decoration, view, "element_${path}_decoration"))
                }
                for (index in 0 until group.childCount) {
                    collect(group.getChildAt(index), "$path/$index")
                }
            } else if (view !== source && view.width > 0 && view.height > 0) {
                val stablePart = if (view.id != View.NO_ID) "id_${view.id}" else
                    view.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")
                        ?: (view as? android.widget.TextView)?.text?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")
                        ?: view.javaClass.simpleName.lowercase()
                candidates.add(Candidate(view, view, "element_${view.javaClass.simpleName.lowercase()}_${stablePart}"))
            }
        }

        for (index in 0 until source.childCount) {
            collect(source.getChildAt(index), index.toString())
        }

        val usedIds = HashSet<String>()
        candidates.forEach { candidate ->
            val view = candidate.view
            val geometry = candidate.geometry
            val proposedId = candidate.proposedId
            var elementId = proposedId
            var suffix = 2
            while (!usedIds.add(elementId)) {
                elementId = "${proposedId}_${suffix++}"
            }
            val location = IntArray(2)
            geometry.getLocationOnScreen(location)
            val left = (location[0] - hostLocation[0]).toFloat()
            val top = (location[1] - hostLocation[1]).toFloat()
            val renderedWidth = geometry.width * referenceScale
            val renderedHeight = geometry.height * referenceScale
            val placement = UiElementPlacement(
                elementId = elementId,
                x = (left / width).coerceIn(0f, 0.99f),
                y = (top / height).coerceIn(0f, 0.99f),
                width = (renderedWidth / width).coerceIn(0.01f, 1f),
                height = (renderedHeight / height).coerceIn(0.01f, 1f),
                zOrder = (geometry.elevation + geometry.translationZ).roundToInt()
            ).normalized()
            (view.parent as? ViewGroup)?.removeView(view)
            addElement(elementId, view, placement)
        }
        requestLayout()
    }

    /** Edit mode intercepts gestures; when off, controls receive normal touch input. */
    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        if (!enabled) {
            selectedId = null
            saveProfile()
        }
        invalidate()
    }

    fun isEditMode(): Boolean = editMode

    fun addElement(elementId: String, view: View, defaultPlacement: UiElementPlacement) {
        require(elementId.isNotBlank()) { "A stable UI element ID is required" }
        require((0 until childCount).none { getChildAt(it).tag == elementId }) {
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
        invalidate()
    }

    fun saveProfile() {
        store.save(profile, normalizedSnapshot())
    }

    fun allPlacements(): List<UiElementPlacement> = placements.values.toList()

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = editMode

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val picked = (childCount - 1 downTo 0).asSequence()
                    .map { getChildAt(it) }
                    .firstOrNull { it.visibility == View.VISIBLE && event.x >= it.left && event.x <= it.right && event.y >= it.top && event.y <= it.bottom }
                selectedId = picked?.tag as? String ?: return true
                startPlacement = placements[selectedId]
                resizing = picked != null &&
                    event.x >= picked.right - handleSize && event.y >= picked.bottom - handleSize
                moving = !resizing
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val original = startPlacement ?: return true
                val id = selectedId ?: return true
                val hostWidth = width.coerceAtLeast(1).toFloat()
                val hostHeight = height.coerceAtLeast(1).toFloat()
                val dx = (event.x - downX) / hostWidth
                val dy = (event.y - downY) / hostHeight
                val maxWidth = 1f - original.x
                val maxHeight = 1f - original.y
                val updated = if (resizing) {
                    original.copy(
                        width = (original.width + dx).coerceIn(min(0.04f, maxWidth), maxWidth),
                        height = (original.height + dy).coerceIn(min(0.04f, maxHeight), maxHeight)
                    )
                } else if (moving) {
                    original.copy(
                        x = (original.x + dx).coerceIn(0f, maxWidth),
                        y = (original.y + dy).coerceIn(0f, maxHeight)
                    )
                } else original
                placements[id] = updated.normalized()
                requestLayout()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                startPlacement = null
                moving = false
                resizing = false
                saveProfile()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editMode) return
        val id = selectedId ?: return
        val child = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.tag == id } ?: return
        canvas.drawRect(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat(), outlinePaint)
        canvas.drawRect(child.right - handleSize, child.bottom - handleSize, child.right.toFloat(), child.bottom.toFloat(), handlePaint)
    }

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
        val hostWidth = right - left
        val hostHeight = bottom - top
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val placement = placements[child.tag as? String] ?: continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val x = (placement.x * hostWidth).roundToInt().coerceIn(0, (hostWidth - childWidth).coerceAtLeast(0))
            val y = (placement.y * hostHeight).roundToInt().coerceIn(0, (hostHeight - childHeight).coerceAtLeast(0))
            child.layout(x, y, x + childWidth, y + childHeight)
            child.visibility = if (placement.visible) View.VISIBLE else View.GONE
            child.translationZ = placement.zOrder.toFloat()
        }
    }

    /** Snapshot actual child bounds after a resize, preserving each stable element ID. */
    fun normalizedSnapshot(): List<UiElementPlacement> {
        val hostWidth = width.coerceAtLeast(1).toFloat()
        val hostHeight = height.coerceAtLeast(1).toFloat()
        return placements.values.map { placement ->
            val view = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.tag == placement.elementId }
            if (view == null) placement else placement.copy(
                x = (view.left / hostWidth).coerceIn(0f, 1f),
                y = (view.top / hostHeight).coerceIn(0f, 1f),
                width = (view.width / hostWidth).coerceIn(0.01f, 1f),
                height = (view.height / hostHeight).coerceIn(0.01f, 1f)
            )
        }
    }
}
