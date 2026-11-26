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
    private var drawingPanel = false
    private var drawStartX = 0f
    private var drawStartY = 0f
    private var drawNowX = 0f
    private var drawNowY = 0f
    private var editorTool = "SELECT"
    private var showGrid = false
    private var snapEnabled = true
    private val gridStep get() = 16f * resources.displayMetrics.density
    private val railWidth get() = 76f * resources.displayMetrics.density
    private val toolbarLabels = listOf("SELECT", "PANEL", "GRID", "SNAP", "DUPL", "DELETE", "FRONT", "BACK", "SIZE", "EXPORT", "DONE")
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x334FC3F7; strokeWidth = 1f * resources.displayMetrics.density }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE171B22.toInt(); style = Paint.Style.FILL }
    private val toolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303943.toInt(); style = Paint.Style.FILL }
    private val activeToolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8860B.toInt(); style = Paint.Style.FILL }
    private val toolTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val draftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
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

        // Transfer decorative drawables instead of mutating or cloning them.
        // Runtime GradientDrawables often have no ConstantState; mutating the
        // original instance here can steal its callback from the source view.
        fun takeDecorations(view: View): Pair<android.graphics.drawable.Drawable?, android.graphics.drawable.Drawable?> {
            val background = view.background
            view.background = null
            val foreground = if (android.os.Build.VERSION.SDK_INT >= 23) {
                view.foreground.also { view.foreground = null }
            } else null
            return background to foreground
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
                val (backgroundArtwork, foregroundArtwork) = takeDecorations(view)
                if (backgroundArtwork != null || foregroundArtwork != null) {
                    val decoration = View(this@FreeLayoutHost.context).apply {
                        background = backgroundArtwork
                        if (android.os.Build.VERSION.SDK_INT >= 23) foreground = foregroundArtwork
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
                if (handleToolbarTap(event.x, event.y)) return true
                if (event.x >= width - railWidth) return true
                if (editorTool == "PANEL") {
                    drawingPanel = true
                    drawStartX = snap(event.x); drawStartY = snap(event.y)
                    drawNowX = drawStartX; drawNowY = drawStartY
                    invalidate()
                    return true
                }
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
                if (drawingPanel) {
                    drawNowX = snap(event.x.coerceAtMost(width - railWidth))
                    drawNowY = snap(event.y)
                    invalidate()
                    return true
                }
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
                if (drawingPanel) {
                    drawingPanel = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) createPanel(drawStartX, drawStartY, snap(event.x.coerceAtMost(width - railWidth)), snap(event.y))
                    invalidate()
                    return true
                }
                startPlacement = null
                moving = false
                resizing = false
                if (event.actionMasked == MotionEvent.ACTION_UP && selectedId == null) {
                    setEditMode(false)
                    android.widget.Toast.makeText(context, "MODALITÀ LAYOUT DISATTIVATA", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    saveProfile()
                }
                selectedId = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editMode) return
        if (showGrid) drawGrid(canvas)
        if (drawingPanel) canvas.drawRect(minOf(drawStartX, drawNowX), minOf(drawStartY, drawNowY), maxOf(drawStartX, drawNowX), maxOf(drawStartY, drawNowY), draftPaint)
        drawEditorToolbar(canvas)
        val id = selectedId ?: return
        val child = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.tag == id } ?: return
        canvas.drawRect(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat(), outlinePaint)
        canvas.drawRect(child.right - handleSize, child.bottom - handleSize, child.right.toFloat(), child.bottom.toFloat(), handlePaint)
    }


    private fun snap(value: Float): Float = if (!snapEnabled) value else (value / gridStep).roundToInt() * gridStep

    private fun drawGrid(canvas: Canvas) {
        val step = gridStep.coerceAtLeast(4f)
        var x = 0f
        while (x < width - railWidth) { canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += step }
        var y = 0f
        while (y < height) { canvas.drawLine(0f, y, width - railWidth, y, gridPaint); y += step }
    }

    private fun drawEditorToolbar(canvas: Canvas) {
        val left = width - railWidth
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), railPaint)
        val bh = (42f * resources.displayMetrics.density).coerceAtLeast(34f)
        val gap = 3f * resources.displayMetrics.density
        var top = 6f * resources.displayMetrics.density
        toolbarLabels.forEach { label ->
            if (top + bh <= height) {
                val active = when (label) { "SELECT", "PANEL" -> editorTool == label; "GRID" -> showGrid; "SNAP" -> snapEnabled; else -> false }
                canvas.drawRoundRect(left + 5f, top, width - 5f, top + bh, 7f, 7f, if (active) activeToolPaint else toolPaint)
                canvas.drawText(label, left + railWidth / 2f, top + bh / 2f + toolTextPaint.textSize / 3f, toolTextPaint)
            }
            top += bh + gap
        }
    }

    private fun handleToolbarTap(x: Float, y: Float): Boolean {
        if (x < width - railWidth) return false
        val bh = (42f * resources.displayMetrics.density).coerceAtLeast(34f)
        val gap = 3f * resources.displayMetrics.density
        val index = ((y - 6f * resources.displayMetrics.density) / (bh + gap)).toInt()
        if (index !in toolbarLabels.indices) return true
        when (toolbarLabels[index]) {
            "SELECT" -> editorTool = "SELECT"
            "PANEL" -> editorTool = "PANEL"
            "GRID" -> showGrid = !showGrid
            "SNAP" -> snapEnabled = !snapEnabled
            "DUPL" -> duplicateSelectedPanel()
            "DELETE" -> deleteSelected()
            "FRONT" -> changeSelectedLayer(1)
            "BACK" -> changeSelectedLayer(-1)
            "SIZE" -> cycleSelectedSize()
            "EXPORT" -> exportLayout()
            "DONE" -> setEditMode(false)
        }
        invalidate()
        return true
    }

    private fun createPanel(x1: Float, y1: Float, x2: Float, y2: Float) {
        val l = minOf(x1, x2).coerceIn(0f, width - railWidth - 40f)
        val t = minOf(y1, y2).coerceIn(0f, height - 40f)
        val w = kotlin.math.abs(x2 - x1).coerceAtLeast(40f).coerceAtMost(width - railWidth - l)
        val h = kotlin.math.abs(y2 - y1).coerceAtLeast(40f).coerceAtMost(height - t)
        val id = "designer_panel_" + android.os.SystemClock.elapsedRealtimeNanos()
        val panel = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x553B4652); setStroke((2f * resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xFFFFD54F.toInt())
                cornerRadius = 8f * resources.displayMetrics.density
            }
            contentDescription = "Pannello personalizzato"
        }
        addElement(id, panel, UiElementPlacement(id, l / width, t / height, w / width, h / height))
        selectedId = id; editorTool = "SELECT"; saveProfile()
    }

    private fun duplicateSelectedPanel() {
        val id = selectedId ?: return
        val p = placements[id] ?: return
        if (!id.startsWith("designer_panel_")) { android.widget.Toast.makeText(context, "Duplica disponibile per i pannelli creati", android.widget.Toast.LENGTH_SHORT).show(); return }
        val newId = "designer_panel_" + android.os.SystemClock.elapsedRealtimeNanos()
        val copy = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x553B4652); setStroke((2f * resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xFFFFD54F.toInt())
                cornerRadius = 8f * resources.displayMetrics.density
            }
            contentDescription = "Pannello duplicato"
        }
        val next = p.copy(elementId = newId, x = (p.x + 0.025f).coerceAtMost(1f - p.width), y = (p.y + 0.025f).coerceAtMost(1f - p.height))
        addElement(newId, copy, next); selectedId = newId; saveProfile()
    }

    private fun deleteSelected() {
        val id = selectedId ?: return
        if (!id.startsWith("designer_panel_")) { android.widget.Toast.makeText(context, "Gli elementi originali non si eliminano", android.widget.Toast.LENGTH_SHORT).show(); return }
        val v = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.tag == id } ?: return
        removeView(v); placements.remove(id); selectedId = null; saveProfile(); requestLayout()
    }

    private fun changeSelectedLayer(delta: Int) {
        val id = selectedId ?: return
        val p = placements[id] ?: return
        val next = p.copy(zOrder = (p.zOrder + delta).coerceIn(-100, 100))
        placements[id] = next
        (0 until childCount).map { getChildAt(it) }.firstOrNull { it.tag == id }?.translationZ = next.zOrder.toFloat()
        saveProfile(); invalidate()
    }

    private fun cycleSelectedSize() {
        val id = selectedId ?: return
        val p = placements[id] ?: return
        val factors = listOf(0.65f, 0.8f, 1f, 1.2f, 1.4f, 1.6f)
        val next = (p.sizePreset + 1) % factors.size
        val factor = factors[next] / factors[p.sizePreset.coerceIn(0, 5)]
        placements[id] = p.copy(width = (p.width * factor).coerceAtMost(1f - p.x), height = (p.height * factor).coerceAtMost(1f - p.y), sizePreset = next).normalized()
        saveProfile(); requestLayout(); invalidate()
    }

    private fun exportLayout() {
        val elements = org.json.JSONArray()
        normalizedSnapshot().forEach { p ->
            elements.put(org.json.JSONObject().put("id", p.elementId).put("x", p.x).put("y", p.y)
                .put("width", p.width).put("height", p.height).put("zOrder", p.zOrder)
                .put("sizePreset", p.sizePreset).put("visible", p.visible))
        }
        val payload = org.json.JSONObject().put("format", "FREQUALIZER_LAYOUT").put("version", 1)
            .put("profile", profile.storageKey).put("elements", elements).toString(2)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FREQUALIZER layout", payload))
        android.widget.Toast.makeText(context, "Layout JSON copiato negli appunti", android.widget.Toast.LENGTH_LONG).show()
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
