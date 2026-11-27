package com.example.rotaryisolator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.app.AlertDialog
import kotlin.math.roundToInt

/**
 * Precision layout editor. The editor changes view geometry only; it never
 * changes audio parameters, listeners, DSP or effect state.
 */
/** A component may expose the actual visible bounds inside its Android layout view. */
internal interface FreeLayoutVisualBoundsProvider {
    fun freeLayoutVisualBounds(): RectF
}

/** Keeps a full Android view intact while exposing only its visible content bounds to the editor. */
private class VisualBoundsFrame(
    context: Context,
    private val content: View,
    private val baseWidth: Int,
    private val baseHeight: Int,
    private val visualBounds: RectF
) : FrameLayout(context) {
    init {
        clipChildren = false
        clipToPadding = false
        addView(content, LayoutParams(baseWidth, baseHeight))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val targetWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val targetHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        setMeasuredDimension(targetWidth, targetHeight)
        // The wrapper represents only the visible selection bounds. Keep the
        // original control at its unmodified dimensions: never scale its canvas,
        // rotary dial, labels, touch target or child hierarchy to fit the wrapper.
        content.measure(
            MeasureSpec.makeMeasureSpec(baseWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(baseHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        content.layout(0, 0, baseWidth, baseHeight)
        // Resize the visible control uniformly. Layout placements are scaled
        // proportionally, so one scale factor keeps circles circular and text
        // and rectangular controls at their original aspect ratio.
        val scale = minOf(
            width.toFloat() / visualBounds.width().coerceAtLeast(1f),
            height.toFloat() / visualBounds.height().coerceAtLeast(1f)
        )
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale
        content.translationX = -visualBounds.left * scale
        content.translationY = -visualBounds.top * scale
    }

    /**
     * Schema-v1 projects store only normalized bounds, not the original text
     * metrics. If those metrics changed between app builds, restoring the old
     * rectangle can magnify a label several times. Keep imported plain text at
     * its native size (or smaller), honoring an explicit size preset up to its
     * documented scale. The resulting placement still matches the visible text.
     */
    fun safeImportedTextPlacement(
        placement: UiElementPlacement,
        hostWidth: Int,
        hostHeight: Int
    ): UiElementPlacement? {
        if (content !is TextView) return null
        val w = hostWidth.coerceAtLeast(1).toFloat()
        val h = hostHeight.coerceAtLeast(1).toFloat()
        val naturalWidth = visualBounds.width().coerceAtLeast(1f)
        val naturalHeight = visualBounds.height().coerceAtLeast(1f)
        val presetMax = floatArrayOf(0.65f, 0.8f, 1f, 1.2f, 1.4f, 1.6f)
            .getOrElse(placement.sizePreset.coerceIn(0, 5)) { 1f }
        val scale = minOf(
            presetMax,
            placement.width * w / naturalWidth,
            placement.height * h / naturalHeight
        ).coerceAtLeast(0.0001f)
        return placement.copy(
            width = naturalWidth * scale / w,
            height = naturalHeight * scale / h
        ).normalized()
    }
}

internal class FreeLayoutHost(
    context: Context,
    private val profile: UiLayoutProfile,
    private val store: UiLayoutProfileStore
) : FrameLayout(context) {

    private val placements = LinkedHashMap<String, UiElementPlacement>()
    private val undoHistory = ArrayDeque<Map<String, UiElementPlacement>>()
    private val redoHistory = ArrayDeque<Map<String, UiElementPlacement>>()
    private var editMode = false
    private var selectedId: String? = null
    private val selectedIds = LinkedHashSet<String>()
    private var selectionMode = false
    private var manualMultiSelectionMode = false
    private var selectionStartX = 0f
    private var selectionStartY = 0f
    private var selectionEndX = 0f
    private var selectionEndY = 0f
    private var selectingArea = false
    private var toolbarCollapsed = false
    private var showGrid = false
    private var snapEnabled = true
    private var movementStepDp = 5f
    private val gridStep get() = 16f * resources.displayMetrics.density
    private val expandedRailWidth get() = 108f * resources.displayMetrics.density
    private val railWidth get() = if (toolbarCollapsed) 30f * resources.displayMetrics.density else expandedRailWidth
    private val toolbarLabels = listOf("ELEMENTI", "MULTI", "AREA", "SPOSTA", "DIMENS.", "ELIMINA", "GRIGLIA", "AGGANCIO", "UNDO", "REDO", "CENTRA", "SALVA", "ESPORTA", "IMPORTA", "NASCONDI", "RETURN")
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE171B22.toInt(); style = Paint.Style.FILL }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303943.toInt(); style = Paint.Style.FILL }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9B7417.toInt(); style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x304FC3F7
        strokeWidth = resources.displayMetrics.density
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0xFFFFD54F.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }
    private val handleSize get() = 10f * resources.displayMetrics.density

    init {
        clipChildren = false
        clipToPadding = false
        placements.putAll(store.load(profile))
        setWillNotDraw(false)
        isClickable = false
    }


    /** Extract independent visible controls from the initial legacy layout tree. */
    fun adoptLaidOutTree(source: ViewGroup, referenceScale: Float = 1f) {
        if (width <= 0 || height <= 0) return
        val hostLocation = IntArray(2)
        getLocationOnScreen(hostLocation)
        data class Candidate(val view: View, val geometry: View, val proposedId: String, val visualBounds: RectF? = null, val screenX: Int, val screenY: Int, val width: Int, val height: Int)
        val candidates = ArrayList<Candidate>()

        fun takeDecorations(view: View): Pair<android.graphics.drawable.Drawable?, android.graphics.drawable.Drawable?> {
            val background = view.background
            view.background = null
            val foreground = if (android.os.Build.VERSION.SDK_INT >= 23) view.foreground.also { view.foreground = null } else null
            return background to foreground
        }

        fun collect(view: View, path: String) {
            val group = view as? ViewGroup
            fun hasInteractiveDescendant(parent: ViewGroup): Boolean {
                for (childIndex in 0 until parent.childCount) {
                    val child = parent.getChildAt(childIndex)
                    if (child.visibility == View.VISIBLE &&
                        (child.isClickable || child.isLongClickable || child.isFocusable || child.hasOnClickListeners())) return true
                    val nested = child as? ViewGroup
                    if (nested != null && hasInteractiveDescendant(nested)) return true
                }
                return false
            }
            fun hasSubstantiallySmallerContent(parent: ViewGroup): Boolean {
                val visibleChildren = (0 until parent.childCount).map { parent.getChildAt(it) }
                    .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
                if (visibleChildren.isEmpty()) return false
                val left = visibleChildren.minOf { it.left }
                val top = visibleChildren.minOf { it.top }
                val right = visibleChildren.maxOf { it.right }
                val bottom = visibleChildren.maxOf { it.bottom }
                val unionWidth = (right - left).coerceAtLeast(0).toFloat()
                val unionHeight = (bottom - top).coerceAtLeast(0).toFloat()
                val parentArea = (parent.width.toFloat() * parent.height.toFloat()).coerceAtLeast(1f)
                val contentArea = unionWidth * unionHeight
                // Do not treat a full-width/full-height wrapper as the selectable
                // item when its visible controls occupy only a smaller part of it.
                return unionWidth < parent.width * 0.88f ||
                    unionHeight < parent.height * 0.88f ||
                    contentArea < parentArea * 0.72f
            }
            // Expand oversized clickable/layout wrappers into their real child
            // controls. This keeps each selection rectangle and movement limit
            // tied to the control itself rather than a full-width parent panel.
            val visualProvider = view as? FreeLayoutVisualBoundsProvider
            fun addCandidate(candidateView: View) {
                if (candidateView.width <= 0 || candidateView.height <= 0) return
                val stablePart = if (candidateView.id != View.NO_ID) "id_${candidateView.id}" else
                    candidateView.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")
                    ?: (candidateView as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")
                    ?: candidateView.javaClass.simpleName.lowercase()
                val boundsProvider = candidateView as? FreeLayoutVisualBoundsProvider
                val visualBounds = boundsProvider?.freeLayoutVisualBounds()
                    ?: (candidateView as? TextView)?.let { text -> textViewVisualBounds(text) }
                val screen = IntArray(2)
                candidateView.getLocationOnScreen(screen)
                candidates.add(Candidate(candidateView, candidateView,
                    "element_${candidateView.javaClass.simpleName.lowercase()}_${stablePart}", visualBounds,
                    screen[0], screen[1], candidateView.width, candidateView.height))
            }
            fun hasTextDescendant(parent: ViewGroup): Boolean {
                for (childIndex in 0 until parent.childCount) {
                    val child = parent.getChildAt(childIndex)
                    if (child.visibility != View.VISIBLE) continue
                    if (child is TextView) return true
                    val nested = child as? ViewGroup
                    if (nested != null && hasTextDescendant(nested)) return true
                }
                return false
            }
            val keepAsUnit = view !== source && (visualProvider != null || group == null ||
                ((view.isClickable || view.isLongClickable || view.isFocusable) &&
                    !hasInteractiveDescendant(group) && !hasSubstantiallySmallerContent(group)))
            val exposeTextChildren = group != null && hasTextDescendant(group)
            if (group != null && (!keepAsUnit || exposeTextChildren)) {
                if (!keepAsUnit) {
                    val (backgroundArtwork, foregroundArtwork) = takeDecorations(view)
                    if (backgroundArtwork != null || foregroundArtwork != null) {
                        val decoration = View(this@FreeLayoutHost.context).apply {
                            background = backgroundArtwork
                            if (android.os.Build.VERSION.SDK_INT >= 23) foreground = foregroundArtwork
                            alpha = view.alpha
                            elevation = view.elevation
                            translationZ = view.translationZ
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                        val screen = IntArray(2)
                        view.getLocationOnScreen(screen)
                        candidates.add(Candidate(decoration, view, "__visual_decoration__${path}", null,
                            screen[0], screen[1], view.width, view.height))
                    }
                }
                // A visual-bounds control (for example RotaryControl) is one
                // selectable object. Keep its graphic children attached to it:
                // detaching the dial View creates a competing full-row hit target
                // and leaves the rotary's own selectable frame visually empty.
                // Only expose its text labels as independent elements.
                if (keepAsUnit) addCandidate(view)
                if (visualProvider != null) {
                    fun collectTextDescendants(parent: ViewGroup, parentPath: String) {
                        for (index in 0 until parent.childCount) {
                            val child = parent.getChildAt(index)
                            val childPath = "$parentPath/$index"
                            if (child is TextView) collect(child, childPath)
                            else (child as? ViewGroup)?.let { collectTextDescendants(it, childPath) }
                        }
                    }
                    collectTextDescendants(group, path)
                } else {
                    for (index in 0 until group.childCount) collect(group.getChildAt(index), "$path/$index")
                }
            } else if (view !== source && view.width > 0 && view.height > 0) {
                addCandidate(view)
            }
        }

        for (index in 0 until source.childCount) collect(source.getChildAt(index), index.toString())
        val usedIds = HashSet<String>()
        candidates.forEach { candidate ->
            var elementId = candidate.proposedId
            var suffix = 2
            while (!usedIds.add(elementId)) elementId = "${candidate.proposedId}_${suffix++}"
            val bounds = candidate.visualBounds?.let { sourceBounds ->
                RectF(
                    sourceBounds.left.coerceIn(0f, candidate.width.toFloat()),
                    sourceBounds.top.coerceIn(0f, candidate.height.toFloat()),
                    sourceBounds.right.coerceIn(0f, candidate.width.toFloat()),
                    sourceBounds.bottom.coerceIn(0f, candidate.height.toFloat())
                ).takeIf { it.width() >= 1f && it.height() >= 1f }
            }
            val left = (candidate.screenX - hostLocation[0]).toFloat() + (bounds?.left ?: 0f) * referenceScale
            val top = (candidate.screenY - hostLocation[1]).toFloat() + (bounds?.top ?: 0f) * referenceScale
            val visualWidth = (bounds?.width() ?: candidate.width.toFloat()) * referenceScale
            val visualHeight = (bounds?.height() ?: candidate.height.toFloat()) * referenceScale
            val placement = UiElementPlacement(
                elementId,
                (left / width).coerceIn(0f, 0.99f),
                (top / height).coerceIn(0f, 0.99f),
                (visualWidth / width).coerceIn(0.01f, 1f),
                (visualHeight / height).coerceIn(0.01f, 1f),
                (candidate.geometry.elevation + candidate.geometry.translationZ).roundToInt()
            ).normalized()
            (candidate.view.parent as? ViewGroup)?.removeView(candidate.view)
            // Every selectable item gets an independent, non-scaling frame.
            // DIMENS changes only this editor frame; the original Android view
            // keeps its measured size, canvas, text metrics and touch geometry.
            val frameBounds = bounds ?: RectF(
                0f, 0f,
                candidate.geometry.width.toFloat(),
                candidate.geometry.height.toFloat()
            )
            val editorView = VisualBoundsFrame(
                context, candidate.view, candidate.width,
                candidate.height, frameBounds
            )
            addElement(elementId, editorView, placement)
        }
        requestLayout()
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        // The editor host must not remain a clickable touch sink after RETURN.
        // With editing disabled, all touch events pass directly to the original controls.
        isClickable = enabled
        isFocusable = enabled
        if (!enabled) {
            selectedId = null
            selectedIds.clear()
            selectingArea = false
            selectionMode = false
            manualMultiSelectionMode = false
            saveProfile()
        }
        invalidate()
    }

    fun isEditMode(): Boolean = editMode

    fun addElement(elementId: String, view: View, defaultPlacement: UiElementPlacement) {
        require(elementId.isNotBlank()) { "A stable UI element ID is required" }
        require((0 until childCount).none { getChildAt(it).tag == elementId }) { "Duplicate UI element ID: $elementId" }
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

    fun saveProfile(): Boolean {
        if (placements.isEmpty()) return false
        return store.save(profile, placements.values.toList())
    }
    fun allPlacements(): List<UiElementPlacement> = placements.values.toList()

    fun setProjectActions(onExport: () -> Unit, onImport: () -> Unit) {
        exportProjectAction = onExport
        importProjectAction = onImport
    }

    fun reloadProfile() {
        val imported = store.load(profile)
        // An empty profile means this orientation was not included in the
        // project yet. Never clear a working in-memory layout in that case.
        if (imported.isNotEmpty()) {
            placements.clear()
            placements.putAll(imported)
        }
        // Keep imported geometry byte-for-byte (after normal validation).
        // In particular, do not shrink TextView placements to their current
        // native metrics: that discarded user-defined title dimensions on import.
        if (placements.isNotEmpty()) {
            check(saveProfile()) { "Verifica del salvataggio layout non riuscita" }
        }
        selectedIds.clear()
        selectedId = null
        selectingArea = false
        requestLayout()
        invalidate()
    }

    private var exportProjectAction: (() -> Unit)? = null
    private var importProjectAction: (() -> Unit)? = null


    private fun textViewVisualBounds(view: TextView): RectF? {
        if (view.width <= 0 || view.height <= 0 || view.text.isNullOrEmpty()) return null
        if (view.background != null || view.compoundDrawables.any { it != null }) return null
        val lines = view.text.toString().split('\n')
        val textWidth = lines.maxOfOrNull { view.paint.measureText(it) } ?: return null
        if (textWidth < 1f) return null
        val metrics = view.paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        val textHeight = lineHeight * lines.size
        val horizontal = view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val x = when (horizontal) {
            Gravity.CENTER_HORIZONTAL -> (view.width - textWidth) / 2f
            Gravity.RIGHT -> view.width - view.paddingRight - textWidth
            else -> view.paddingLeft.toFloat()
        }
        val vertical = view.gravity and Gravity.VERTICAL_GRAVITY_MASK
        val y = when (vertical) {
            Gravity.CENTER_VERTICAL -> (view.height - textHeight) / 2f
            Gravity.BOTTOM -> view.height - view.paddingBottom - textHeight
            else -> view.paddingTop.toFloat()
        }
        return RectF(x.coerceAtLeast(0f), y.coerceAtLeast(0f),
            (x + textWidth).coerceAtMost(view.width.toFloat()),
            (y + textHeight).coerceAtMost(view.height.toFloat()))
    }

    private fun isSelectableElement(id: String): Boolean = !id.startsWith("__visual_decoration__")

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = editMode

    /** Tap selects only. Position changes are performed through the precision controls. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (handleToolbarTap(event.x, event.y)) return true
                if (event.x >= width - railWidth) {
                    if (toolbarCollapsed) toolbarCollapsed = false
                    invalidate()
                    return true
                }
                if (selectionMode) {
                    selectionStartX = event.x
                    selectionStartY = event.y
                    selectionEndX = event.x
                    selectionEndY = event.y
                    selectingArea = true
                    invalidate()
                    return true
                }
                // When a label/value overlaps its rotary's larger hit area,
                // select the smallest visible element under the pointer first.
                val picked = (childCount - 1 downTo 0).asSequence()
                    .map { getChildAt(it) }
                    .filter { child ->
                        val childId = child.tag as? String
                        childId != null && isSelectableElement(childId) && child.visibility == View.VISIBLE &&
                            event.x >= child.left && event.x <= child.right &&
                            event.y >= child.top && event.y <= child.bottom
                    }
                    .minByOrNull { child -> child.width.toLong() * child.height.toLong() }
                val pickedId = picked?.tag as? String
                if (manualMultiSelectionMode) {
                    if (pickedId != null) {
                        if (!selectedIds.add(pickedId)) selectedIds.remove(pickedId)
                    }
                } else {
                    selectedIds.clear()
                    pickedId?.let { selectedIds.add(it) }
                }
                selectedId = selectedIds.firstOrNull()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectingArea) {
                    selectionEndX = event.x
                    selectionEndY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selectingArea) {
                    selectionEndX = event.x
                    selectionEndY = event.y
                    val left = minOf(selectionStartX, selectionEndX)
                    val right = maxOf(selectionStartX, selectionEndX)
                    val top = minOf(selectionStartY, selectionEndY)
                    val bottom = maxOf(selectionStartY, selectionEndY)
                    selectedIds.clear()
                    val tolerance = 2f * resources.displayMetrics.density
                    for (i in 0 until childCount) {
                        val child = getChildAt(i)
                        val id = child.tag as? String ?: continue
                        if (!isSelectableElement(id) || child.visibility != View.VISIBLE) continue
                        // Use the exact bounds used by the selection outline and
                        // movement controls; the center must fall inside the marquee.
                        val centerX = (child.left + child.right) * 0.5f
                        val centerY = (child.top + child.bottom) * 0.5f
                        if (centerX >= left - tolerance && centerX <= right + tolerance &&
                            centerY >= top - tolerance && centerY <= bottom + tolerance) {
                            selectedIds.add(id)
                        }
                    }
                    selectedId = selectedIds.firstOrNull()
                    selectingArea = false
                    selectionMode = false
                    Toast.makeText(context, if (selectedIds.isEmpty()) "Nessun elemento completamente incluso" else "${selectedIds.size} elementi selezionati", Toast.LENGTH_SHORT).show()
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (editMode && showGrid) drawGrid(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editMode) return
        selectedIds.filter(::isSelectableElement).forEach { id ->
            val child = findElement(id)
            if (child != null) {
                canvas.drawRect(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat(), outlinePaint)
                canvas.drawCircle(child.right - handleSize, child.bottom - handleSize, handleSize * 0.65f, handlePaint)
            }
        }
        if (selectingArea) {
            val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x334FC3F7
                style = Paint.Style.FILL
            }
            val areaOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF4FC3F7.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2f * resources.displayMetrics.density
            }
            val l = minOf(selectionStartX, selectionEndX)
            val r = maxOf(selectionStartX, selectionEndX)
            val t = minOf(selectionStartY, selectionEndY)
            val b = maxOf(selectionStartY, selectionEndY)
            canvas.drawRect(l, t, r, b, areaPaint)
            canvas.drawRect(l, t, r, b, areaOutline)
        }
        drawEditorToolbar(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        var x = 0f
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += gridStep }
        var y = 0f
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, gridPaint); y += gridStep }
    }

    private fun buttonHeight(): Float {
        val density = resources.displayMetrics.density
        val gap = 2f * density
        val available = height - 10f * density - gap * (toolbarLabels.size - 1)
        return (available / toolbarLabels.size).coerceIn(18f * density, 43f * density)
    }

    private fun drawEditorToolbar(canvas: Canvas) {
        val left = width - railWidth
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), railPaint)
        if (toolbarCollapsed) {
            canvas.drawRoundRect(left + 2f, height / 2f - 34f, width - 2f, height / 2f + 34f, 8f, 8f, activePaint)
            canvas.drawText("MENU", left + railWidth / 2f, height / 2f + textPaint.textSize / 3f, textPaint)
            return
        }
        val bh = buttonHeight()
        val gap = 2f * resources.displayMetrics.density
        var top = 5f * resources.displayMetrics.density
        toolbarLabels.forEach { label ->
            val active = (label == "GRIGLIA" && showGrid) ||
                (label == "AGGANCIO" && snapEnabled) || (label == "AREA" && selectionMode) ||
                (label == "MULTI" && manualMultiSelectionMode)
            canvas.drawRoundRect(left + 5f, top, width - 5f, top + bh, 6f, 6f, if (active) activePaint else buttonPaint)
            canvas.drawText(label, left + railWidth / 2f, top + bh / 2f + textPaint.textSize / 3f, textPaint)
            top += bh + gap
        }
    }

    private fun handleToolbarTap(x: Float, y: Float): Boolean {
        if (x < width - railWidth) return false
        if (toolbarCollapsed) {
            toolbarCollapsed = false
            invalidate()
            return true
        }
        val bh = buttonHeight()
        val gap = 2f * resources.displayMetrics.density
        val index = ((y - 5f * resources.displayMetrics.density) / (bh + gap)).toInt()
        if (index !in toolbarLabels.indices) return true
        when (toolbarLabels[index]) {
            "ELEMENTI" -> showElementPicker()
            "MULTI" -> {
                manualMultiSelectionMode = !manualMultiSelectionMode
                selectionMode = false
                selectingArea = false
                selectedIds.clear()
                selectedId = null
                Toast.makeText(context, if (manualMultiSelectionMode) "Tocca gli elementi per aggiungerli o toglierli dal gruppo" else "Selezione multipla manuale disattivata", Toast.LENGTH_SHORT).show()
            }
            "AREA" -> {
                selectionMode = !selectionMode
                manualMultiSelectionMode = false
                selectingArea = false
                if (selectionMode) { selectedIds.clear(); selectedId = null }
                Toast.makeText(context, if (selectionMode) "Traccia un rettangolo per selezionare più elementi" else "Selezione area disattivata", Toast.LENGTH_SHORT).show()
            }
            "SPOSTA" -> showMoveControls()
            "DIMENS." -> showSizeControls()
            "ELIMINA" -> deleteSelectedElements()
            "GRIGLIA" -> showGrid = !showGrid
            "AGGANCIO" -> snapEnabled = !snapEnabled
            "UNDO" -> undo()
            "REDO" -> redo()
            "CENTRA" -> centerSelected()
            "SALVA" -> { Toast.makeText(context, if (saveProfile()) "Layout salvato e verificato" else "Salvataggio non riuscito: layout non modificato", Toast.LENGTH_SHORT).show() }
            "ESPORTA" -> { if (saveProfile()) exportProjectAction?.invoke() else Toast.makeText(context, "Esportazione annullata: prima salva un layout valido", Toast.LENGTH_LONG).show() }
            "IMPORTA" -> importProjectAction?.invoke()
            "NASCONDI" -> toolbarCollapsed = true
            "RETURN" -> setEditMode(false)
        }
        invalidate()
        return true
    }

    private fun findElement(id: String): View? =
        (0 until childCount).asSequence().map { getChildAt(it) }.firstOrNull { it.tag == id }

    private fun elementTitle(view: View): String {
        val description = view.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val text = (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
        return description ?: text ?: view.javaClass.simpleName
    }

    private fun showEditorDialog(builder: AlertDialog.Builder): AlertDialog {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.let { window ->
                window.setDimAmount(0f)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setBackgroundDrawable(ColorDrawable(0xB8171B22.toInt()))
                // Keep the controls legible while making the underlying layout visible.
                window.decorView.alpha = 0.86f
            }
        }
        dialog.show()
        return dialog
    }

    private fun showElementPicker() {
        val entries = (0 until childCount).mapNotNull { index ->
            val view = getChildAt(index)
            val id = view.tag as? String ?: return@mapNotNull null
            if (!isSelectableElement(id)) return@mapNotNull null
            val hidden = placements[id]?.visible == false
            "${elementTitle(view)}${if (hidden) "  (NASCOSTO)" else ""}  ·  ${index + 1}" to id
        }
        if (entries.isEmpty()) return
        showEditorDialog(AlertDialog.Builder(context)
            .setTitle("SELEZIONA ELEMENTO")
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                val id = entries[which].second
                val placement = placements[id]
                if (placement?.visible == false) {
                    rememberUndo()
                    placements[id] = placement.copy(visible = true).normalized()
                    saveProfile()
                    requestLayout()
                    Toast.makeText(context, "Elemento ripristinato", Toast.LENGTH_SHORT).show()
                }
                selectedIds.clear()
                selectedIds.add(id)
                selectedId = id
                invalidate()
            }
            .setNegativeButton("CHIUDI", null))
    }

    private fun deleteSelectedElements() {
        val targets = selectedPlacements().filter { it.visible }
        if (targets.isEmpty()) {
            Toast.makeText(context, "Seleziona prima gli elementi da eliminare", Toast.LENGTH_SHORT).show()
            return
        }
        rememberUndo()
        targets.forEach { placement ->
            placements[placement.elementId] = placement.copy(visible = false).normalized()
            findElement(placement.elementId)?.visibility = View.GONE
        }
        selectedIds.clear()
        selectedId = null
        selectionMode = false
        manualMultiSelectionMode = false
        requestLayout()
        invalidate()
        saveProfile()
        Toast.makeText(context, "${targets.size} elemento/i rimosso/i dal layout. Ripristino da ELEMENTI.", Toast.LENGTH_LONG).show()
    }

    private fun requireSelection(): Pair<String, UiElementPlacement>? {
        val id = selectedIds.firstOrNull() ?: selectedId ?: run {
            Toast.makeText(context, "Prima seleziona uno o più elementi", Toast.LENGTH_SHORT).show()
            return null
        }
        val placement = placements[id] ?: return null
        return id to placement
    }

    private fun selectedPlacements(): List<UiElementPlacement> {
        val ids = (if (selectedIds.isNotEmpty()) selectedIds.toList() else listOfNotNull(selectedId))
            .filter(::isSelectableElement)
        val hostW = width.coerceAtLeast(1).toFloat()
        val hostH = height.coerceAtLeast(1).toFloat()
        return ids.mapNotNull { id ->
            val stored = placements[id] ?: return@mapNotNull null
            val view = findElement(id) ?: return@mapNotNull stored
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@mapNotNull stored
            // Reconcile geometry against the actual laid-out view so movement limits
            // never include stale or oversized placement bounds.
            stored.copy(
                x = (view.left / hostW).coerceIn(0f, 1f),
                y = (view.top / hostH).coerceIn(0f, 1f),
                width = (view.width / hostW).coerceIn(0.0001f, 1f),
                height = (view.height / hostH).coerceIn(0.0001f, 1f)
            ).normalized()
        }
    }

    private fun rememberUndo() {
        undoHistory.addLast(LinkedHashMap(placements))
        while (undoHistory.size > 100) undoHistory.removeFirst()
        redoHistory.clear()
    }

    private fun applySnapshot(snapshot: Map<String, UiElementPlacement>) {
        placements.clear()
        placements.putAll(snapshot)
        requestLayout()
        invalidate()
        saveProfile()
    }

    private fun undo() {
        if (undoHistory.isEmpty()) { Toast.makeText(context, "Nessuna modifica da annullare", Toast.LENGTH_SHORT).show(); return }
        redoHistory.addLast(LinkedHashMap(placements))
        applySnapshot(undoHistory.removeLast())
    }

    private fun redo() {
        if (redoHistory.isEmpty()) { Toast.makeText(context, "Nessuna modifica da ripristinare", Toast.LENGTH_SHORT).show(); return }
        undoHistory.addLast(LinkedHashMap(placements))
        applySnapshot(redoHistory.removeLast())
    }

    private fun showMoveControls() {
        if (requireSelection() == null) return
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val stepLabel = TextView(context).apply { text = "PASSO DI SPOSTAMENTO"; gravity = Gravity.CENTER; textSize = 13f }
        root.addView(stepLabel)
        val stepChoices = listOf(1f, 5f, 10f, 25f, 50f)
        val stepRow = LinearLayout(context).apply { gravity = Gravity.CENTER }
        stepChoices.forEach { step ->
            val b = Button(context).apply {
                text = step.toInt().toString()
                isAllCaps = false
                setOnClickListener { movementStepDp = step; stepLabel.text = "PASSO: ${step.toInt()} dp" }
            }
            stepRow.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        root.addView(stepRow)
        fun actionButton(label: String, dx: Int, dy: Int) = Button(context).apply {
            text = label; isAllCaps = false
            setOnClickListener { moveSelected(dx, dy, movementStepDp); stepLabel.text = "SPOSTAMENTO: ${movementStepDp.toInt()} dp" }
        }
        val up = actionButton("▲", 0, -1)
        val left = actionButton("◀", -1, 0)
        val center = Button(context).apply { text = "CENTRO"; isAllCaps = false; setOnClickListener { centerSelected() } }
        val right = actionButton("▶", 1, 0)
        val down = actionButton("▼", 0, 1)
        val row1 = LinearLayout(context).apply { gravity = Gravity.CENTER; addView(up, LinearLayout.LayoutParams(dp(76), dp(48))) }
        val row2 = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(left, LinearLayout.LayoutParams(dp(76), dp(48)))
            addView(center, LinearLayout.LayoutParams(dp(92), dp(48)))
            addView(right, LinearLayout.LayoutParams(dp(76), dp(48)))
        }
        val row3 = LinearLayout(context).apply { gravity = Gravity.CENTER; addView(down, LinearLayout.LayoutParams(dp(76), dp(48))) }
        root.addView(row1); root.addView(row2); root.addView(row3)
        showEditorDialog(AlertDialog.Builder(context).setTitle("SPOSTAMENTO PRECISO").setView(root)
            .setNegativeButton("CHIUDI", null))
    }

    private fun moveSelected(dx: Int, dy: Int, stepDp: Float) {
        val targets = selectedPlacements()
        if (targets.isEmpty()) { requireSelection(); return }
        rememberUndo()
        val px = stepDp * resources.displayMetrics.density
        val hostW = width.coerceAtLeast(1).toFloat()
        val hostH = height.coerceAtLeast(1).toFloat()
        val minX = targets.minOf { it.x * hostW }
        val minY = targets.minOf { it.y * hostH }
        val maxX = targets.maxOf { (it.x + it.width) * hostW }
        val maxY = targets.maxOf { (it.y + it.height) * hostH }
        var deltaX = (dx * px).coerceIn(-minX, hostW - maxX)
        var deltaY = (dy * px).coerceIn(-minY, hostH - maxY)
        if (snapEnabled && movementStepDp >= 16f) {
            deltaX = ((minX + deltaX) / gridStep).roundToInt() * gridStep - minX
            deltaY = ((minY + deltaY) / gridStep).roundToInt() * gridStep - minY
        }
        targets.forEach { old ->
            placements[old.elementId] = old.copy(
                x = ((old.x * hostW + deltaX) / hostW).coerceIn(0f, 1f - old.width),
                y = ((old.y * hostH + deltaY) / hostH).coerceIn(0f, 1f - old.height)
            ).normalized()
        }
        requestLayout(); invalidate(); saveProfile()
    }

    private fun showSizeControls() {
        if (requireSelection() == null) return
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val info = TextView(context).apply { gravity = Gravity.CENTER; text = "Regolazione indipendente di larghezza e altezza"; textSize = 13f }
        root.addView(info)
        fun row(title: String, horizontal: Boolean, amount: Float) {
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val minus = Button(context).apply { text = "−"; textSize = 22f; setOnClickListener { resizeSelected(if (horizontal) -amount else 0f, if (horizontal) 0f else -amount) } }
            val plus = Button(context).apply { text = "+"; textSize = 22f; setOnClickListener { resizeSelected(if (horizontal) amount else 0f, if (horizontal) 0f else amount) } }
            val label = TextView(context).apply { text = title; gravity = Gravity.CENTER; textSize = 14f }
            line.addView(minus, LinearLayout.LayoutParams(dp(64), dp(48)))
            line.addView(label, LinearLayout.LayoutParams(0, dp(48), 1f))
            line.addView(plus, LinearLayout.LayoutParams(dp(64), dp(48)))
            root.addView(line)
        }
        row("LARGHEZZA", true, 0.01f)
        row("ALTEZZA", false, 0.01f)
        val uniformRow = LinearLayout(context).apply { gravity = Gravity.CENTER }
        val uniformMinus = Button(context).apply { text = "− UNIFORME"; setOnClickListener { resizeSelectedByFactor(0.95f) } }
        val uniformPlus = Button(context).apply { text = "+ UNIFORME"; setOnClickListener { resizeSelectedByFactor(1.05f) } }
        uniformRow.addView(uniformMinus, LinearLayout.LayoutParams(0, dp(48), 1f))
        uniformRow.addView(uniformPlus, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(uniformRow)
        val presetTitle = TextView(context).apply { text = "PREIMPOSTAZIONI DIMENSIONE"; gravity = Gravity.CENTER; textSize = 12f }
        root.addView(presetTitle)
        val presets = LinearLayout(context).apply { gravity = Gravity.CENTER }
        listOf("XS", "S", "M", "L", "XL", "XXL").forEachIndexed { index, label ->
            val b = Button(context).apply { text = label; setOnClickListener { applySizePreset(index) } }
            presets.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        root.addView(presets)
        showEditorDialog(AlertDialog.Builder(context).setTitle("DIMENSIONI ELEMENTO").setView(root)
            .setNegativeButton("CHIUDI", null))
    }

    private fun resizeSelected(dw: Float, dh: Float) {
        val targets = selectedPlacements()
        if (targets.isEmpty()) { requireSelection(); return }
        val left = targets.minOf { it.x }
        val top = targets.minOf { it.y }
        val right = targets.maxOf { it.x + it.width }
        val bottom = targets.maxOf { it.y + it.height }
        val groupWidth = (right - left).coerceAtLeast(0.02f)
        val groupHeight = (bottom - top).coerceAtLeast(0.02f)
        // Horizontal and vertical resizing are independent. In particular,
        // increasing the height of a multi-selected keyboard must not silently
        // turn into a uniform resize or change only its width.
        val scaleX = if (dw != 0f) 1f + dw / groupWidth else 1f
        val scaleY = if (dh != 0f) 1f + dh / groupHeight else 1f
        resizeSelectedByAxes(scaleX, scaleY)
    }

    /** Resize a selection independently on each axis around its group centre. */
    private fun resizeSelectedByAxes(requestedScaleX: Float, requestedScaleY: Float) {
        val targets = selectedPlacements()
        if (targets.isEmpty()) { requireSelection(); return }
        val hostW = width.coerceAtLeast(1).toFloat()
        val hostH = height.coerceAtLeast(1).toFloat()
        val rects = targets.map { item ->
            floatArrayOf(item.x * hostW, item.y * hostH,
                (item.x + item.width) * hostW, (item.y + item.height) * hostH)
        }
        val left = rects.minOf { it[0] }
        val top = rects.minOf { it[1] }
        val right = rects.maxOf { it[2] }
        val bottom = rects.maxOf { it[3] }
        val centerX = (left + right) * 0.5f
        val centerY = (top + bottom) * 0.5f
        val halfW = (right - left) * 0.5f
        val halfH = (bottom - top) * 0.5f
        val minScaleX = rects.indices.maxOf { index ->
            0.5f / (rects[index][2] - rects[index][0]).coerceAtLeast(0.5f)
        }
        val minScaleY = rects.indices.maxOf { index ->
            0.5f / (rects[index][3] - rects[index][1]).coerceAtLeast(0.5f)
        }
        val maxScaleX = if (halfW > 0f) minOf(centerX / halfW, (hostW - centerX) / halfW) else 1f
        val maxScaleY = if (halfH > 0f) minOf(centerY / halfH, (hostH - centerY) / halfH) else 1f
        val scaleX = requestedScaleX.coerceIn(minScaleX, maxScaleX.coerceAtLeast(1f))
        val scaleY = requestedScaleY.coerceIn(minScaleY, maxScaleY.coerceAtLeast(1f))

        rememberUndo()
        targets.forEachIndexed { index, old ->
            val rect = rects[index]
            val newWidthPx = (rect[2] - rect[0]) * scaleX
            val newHeightPx = (rect[3] - rect[1]) * scaleY
            val newLeftPx = centerX + (rect[0] - centerX) * scaleX
            val newTopPx = centerY + (rect[1] - centerY) * scaleY
            placements[old.elementId] = old.copy(
                x = (newLeftPx / hostW).coerceIn(0f, 1f),
                y = (newTopPx / hostH).coerceIn(0f, 1f),
                width = (newWidthPx / hostW).coerceIn(0.0001f, 1f),
                height = (newHeightPx / hostH).coerceIn(0.0001f, 1f)
            ).normalized()
        }
        requestLayout()
        invalidate()
        saveProfile()
    }

    /** Scale selected items uniformly in physical pixels around the group centre. */
    private fun resizeSelectedByFactor(requestedFactor: Float) {
        val targets = selectedPlacements()
        if (targets.isEmpty()) { requireSelection(); return }
        val hostW = width.coerceAtLeast(1).toFloat()
        val hostH = height.coerceAtLeast(1).toFloat()
        val pixelRects = targets.map { item ->
            floatArrayOf(item.x * hostW, item.y * hostH,
                (item.x + item.width) * hostW, (item.y + item.height) * hostH)
        }
        val left = pixelRects.minOf { it[0] }
        val top = pixelRects.minOf { it[1] }
        val right = pixelRects.maxOf { it[2] }
        val bottom = pixelRects.maxOf { it[3] }
        val centerX = (left + right) * 0.5f
        val centerY = (top + bottom) * 0.5f
        val halfW = (right - left) * 0.5f
        val halfH = (bottom - top) * 0.5f

        // Calculate one shared factor that keeps the entire group on the canvas.
        var maxFactor = Float.MAX_VALUE
        if (halfW > 0f) maxFactor = minOf(maxFactor, centerX / halfW, (hostW - centerX) / halfW)
        if (halfH > 0f) maxFactor = minOf(maxFactor, centerY / halfH, (hostH - centerY) / halfH)
        val minFactor = targets.indices.maxOf { index ->
            val rect = pixelRects[index]
            maxOf(0.5f / (rect[2] - rect[0]).coerceAtLeast(0.5f),
                0.5f / (rect[3] - rect[1]).coerceAtLeast(0.5f))
        }
        val factor = if (requestedFactor < 1f) {
            requestedFactor.coerceAtLeast(minFactor).coerceAtMost(1f)
        } else {
            requestedFactor.coerceAtMost(maxFactor.coerceAtLeast(1f))
        }

        rememberUndo()
        targets.forEachIndexed { index, old ->
            val rect = pixelRects[index]
            val newWidthPx = (rect[2] - rect[0]) * factor
            val newHeightPx = (rect[3] - rect[1]) * factor
            val newLeftPx = centerX + (rect[0] - centerX) * factor
            val newTopPx = centerY + (rect[1] - centerY) * factor
            placements[old.elementId] = old.copy(
                x = (newLeftPx / hostW).coerceIn(0f, 1f),
                y = (newTopPx / hostH).coerceIn(0f, 1f),
                width = (newWidthPx / hostW).coerceIn(0.0001f, 1f),
                height = (newHeightPx / hostH).coerceIn(0.0001f, 1f)
            ).normalized()
        }
        requestLayout()
        invalidate()
        saveProfile()
    }

    private fun applySizePreset(index: Int) {
        val targets = selectedPlacements()
        if (targets.isEmpty()) { requireSelection(); return }
        val factors = listOf(0.65f, 0.8f, 1f, 1.2f, 1.4f, 1.6f)
        val current = factors[targets.first().sizePreset.coerceIn(0, 5)]
        resizeSelectedByFactor(factors[index] / current)
        selectedPlacements().forEach { placement ->
            placements[placement.elementId] = placement.copy(sizePreset = index).normalized()
        }
        requestLayout(); invalidate(); saveProfile()
    }

    private fun centerSelected() {
        val targets = selectedPlacements()
        if (targets.isEmpty()) { requireSelection(); return }
        rememberUndo()
        val left = targets.minOf { it.x }
        val top = targets.minOf { it.y }
        val right = targets.maxOf { it.x + it.width }
        val bottom = targets.maxOf { it.y + it.height }
        val groupW = right - left
        val groupH = bottom - top
        val offsetX = (1f - groupW) / 2f - left
        val offsetY = (1f - groupH) / 2f - top
        targets.forEach { old ->
            placements[old.elementId] = old.copy(
                x = (old.x + offsetX).coerceIn(0f, 1f - old.width),
                y = (old.y + offsetY).coerceIn(0f, 1f - old.height)
            ).normalized()
        }
        requestLayout(); invalidate(); saveProfile()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hostWidth = MeasureSpec.getSize(widthMeasureSpec)
        val hostHeight = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(hostWidth, hostHeight)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val placement = placements[child.tag as? String] ?: continue
            val childWidth = (hostWidth * placement.width).roundToInt().coerceAtLeast(1)
            val childHeight = (hostHeight * placement.height).roundToInt().coerceAtLeast(1)
            child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY))
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

    fun normalizedSnapshot(): List<UiElementPlacement> {
        val hostWidth = width.coerceAtLeast(1).toFloat()
        val hostHeight = height.coerceAtLeast(1).toFloat()
        return placements.values.map { placement ->
            val view = findElement(placement.elementId)
            if (view == null) placement else placement.copy(
                x = (view.left / hostWidth).coerceIn(0f, 1f),
                y = (view.top / hostHeight).coerceIn(0f, 1f),
                width = (view.width / hostWidth).coerceIn(0.0001f, 1f),
                height = (view.height / hostHeight).coerceIn(0.0001f, 1f)
            )
        }
    }
}
