package com.example.rotaryisolator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Absolute-position canvas for the FREQUALIZER layout designer.
 *
 * Top-level mixer panels use normalized bounds. In edit mode the canvas also
 * hit-tests descendants, so individual knobs, buttons, labels and nested
 * control groups can be moved or resized without replacing their listeners.
 */
internal class FreeformCanvasLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private data class Entry(val view: View, var element: FreeformLayoutElement)
    private data class Target(val view: View, val root: Entry, val id: String, val isRoot: Boolean)
    private enum class GestureMode { NONE, MOVE, RESIZE }

    private val entries = mutableListOf<Entry>()
    private val nestedTransforms = linkedMapOf<String, FreeformLayoutElement>()
    private var editMode = false
    private var selected: Target? = null
    private var gestureMode = GestureMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startElement: FreeformLayoutElement? = null
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var startScaleX = 1f
    private var startScaleY = 1f
    private var onLayoutChanged: ((List<FreeformLayoutElement>) -> Unit)? = null

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = 0xFFFFC43D.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFC43D.toInt()
    }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
    }

    fun setOnLayoutChangedListener(listener: ((List<FreeformLayoutElement>) -> Unit)?) {
        onLayoutChanged = listener
    }

    fun isDesignerEditing(): Boolean = editMode

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        selected = null
        gestureMode = GestureMode.NONE
        if (enabled) entries.forEach { disableAncestorClipping(it.view) }
        invalidate()
    }

    fun addLayoutElement(view: View, element: FreeformLayoutElement) {
        require(entries.none { it.element.id == element.id }) {
            "Duplicate layout element id: " + element.id
        }
        val entry = Entry(view, element.normalized())
        entries.add(entry)
        addView(view, LayoutParams(1, 1))
        if (editMode) disableAncestorClipping(view)
        requestLayout()
    }

    fun removeLayoutElement(id: String) {
        val entry = entries.firstOrNull { it.element.id == id } ?: return
        entries.remove(entry)
        removeView(entry.view)
        nestedTransforms.keys.removeAll { it.startsWith("view_" + id + "/") }
        if (selected?.root === entry) selected = null
        notifyLayoutChanged()
        invalidate()
    }

    fun clearLayoutElements() {
        entries.clear()
        nestedTransforms.clear()
        removeAllViews()
        selected = null
        notifyLayoutChanged()
    }

    fun layoutElements(): List<FreeformLayoutElement> =
        (entries.map { it.element } + nestedTransforms.values).sortedBy { it.zIndex }

    fun restoreNestedTransforms(elements: List<FreeformLayoutElement>) {
        nestedTransforms.clear()
        elements.filter { it.id.startsWith("view_") }.forEach { element ->
            val target = findViewByStablePath(element.id) ?: return@forEach
            applyTransform(target, element)
            nestedTransforms[element.id] = element
        }
        invalidate()
    }

    fun restoreLayoutElements(
        elements: List<FreeformLayoutElement>,
        viewFactory: (FreeformLayoutElement) -> View?
    ) {
        entries.clear()
        nestedTransforms.clear()
        removeAllViews()
        elements.filterNot { it.id.startsWith("view_") }.sortedBy { it.zIndex }.forEach { element ->
            val view = viewFactory(element) ?: return@forEach
            entries.add(Entry(view, element.normalized()))
            addView(view, LayoutParams(1, 1))
        }
        restoreNestedTransforms(elements)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        val h = measuredHeight
        entries.forEach { entry ->
            val e = entry.element
            entry.view.measure(
                MeasureSpec.makeMeasureSpec((w * e.width).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((h * e.height).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        entries.forEach { entry ->
            val e = entry.element
            val childLeft = (w * e.x).toInt().coerceIn(0, max(0, w - 1))
            val childTop = (h * e.y).toInt().coerceIn(0, max(0, h - 1))
            val childWidth = (w * e.width).toInt().coerceAtLeast(1)
            val childHeight = (h * e.height).toInt().coerceAtLeast(1)
            entry.view.layout(childLeft, childTop, min(w, childLeft + childWidth), min(h, childTop + childHeight))
            entry.view.translationZ = e.zIndex.toFloat()
            if (editMode) disableAncestorClipping(entry.view)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        editMode || super.onInterceptTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                selected = findTargetAt(downX, downY)
                val target = selected ?: return true
                val view = target.view
                startElement = if (target.isRoot) target.root.element else null
                startTranslationX = view.translationX
                startTranslationY = view.translationY
                startScaleX = view.scaleX
                startScaleY = view.scaleY
                val rect = targetRect(view)
                val handle = 28f * resources.displayMetrics.density
                gestureMode = if (downX >= rect.right - handle && downY >= rect.bottom - handle) {
                    GestureMode.RESIZE
                } else GestureMode.MOVE
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val target = selected ?: return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (target.isRoot) {
                    val start = startElement ?: return true
                    if (start.locked || width <= 0 || height <= 0) return true
                    target.root.element = when (gestureMode) {
                        GestureMode.MOVE -> start.copy(
                            x = (start.x + dx / width).coerceIn(0f, 1f - start.width),
                            y = (start.y + dy / height).coerceIn(0f, 1f - start.height)
                        )
                        GestureMode.RESIZE -> start.copy(
                            width = (start.width + dx / width).coerceIn(0.04f, 1f - start.x),
                            height = (start.height + dy / height).coerceIn(0.04f, 1f - start.y)
                        )
                        GestureMode.NONE -> start
                    }.normalized()
                    requestLayout()
                } else {
                    val view = target.view
                    when (gestureMode) {
                        GestureMode.MOVE -> {
                            view.translationX = startTranslationX + dx
                            view.translationY = startTranslationY + dy
                        }
                        GestureMode.RESIZE -> {
                            view.scaleX = (startScaleX * (1f + dx / view.width.coerceAtLeast(1))).coerceIn(0.30f, 3f)
                            view.scaleY = (startScaleY * (1f + dy / view.height.coerceAtLeast(1))).coerceIn(0.30f, 3f)
                        }
                        GestureMode.NONE -> Unit
                    }
                    recordNestedTransform(target)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selected != null) notifyLayoutChanged()
                gestureMode = GestureMode.NONE
                startElement = null
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editMode) return
        selected?.let { target ->
            val rect = targetRect(target.view)
            canvas.drawRect(rect, outlinePaint)
            canvas.drawCircle(rect.right, rect.bottom, 7f * resources.displayMetrics.density, handlePaint)
        }
    }

    private fun findTargetAt(x: Float, y: Float): Target? {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val sx = location[0] + x
        val sy = location[1] + y
        entries.asReversed().forEach { entry ->
            if (screenRect(entry.view).contains(sx, sy)) {
                return findDeepestTarget(entry, entry.view, sx, sy, entry.element.id)
            }
        }
        return null
    }

    private fun findDeepestTarget(entry: Entry, view: View, sx: Float, sy: Float, path: String): Target {
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                if (child.visibility != View.VISIBLE || child.width <= 0 || child.height <= 0) continue
                if (screenRect(child).contains(sx, sy)) {
                    return findDeepestTarget(entry, child, sx, sy, path + "/" + index)
                }
            }
        }
        val isRoot = view === entry.view
        return Target(view, entry, if (isRoot) entry.element.id else "view_" + path, isRoot)
    }

    private fun screenRect(view: View): RectF {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return RectF(location[0].toFloat(), location[1].toFloat(),
            location[0] + view.width.toFloat(), location[1] + view.height.toFloat())
    }

    private fun targetRect(view: View): RectF {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return screenRect(view).apply { offset(-location[0].toFloat(), -location[1].toFloat()) }
    }

    private fun recordNestedTransform(target: Target) {
        if (target.isRoot) return
        nestedTransforms[target.id] = FreeformLayoutElement(
            id = target.id,
            kind = "view_transform",
            title = target.view.contentDescription?.toString() ?: target.view.javaClass.simpleName,
            x = 0f, y = 0f, width = 0.1f, height = 0.1f,
            zIndex = target.root.element.zIndex,
            properties = mapOf(
                "translationX" to target.view.translationX.toString(),
                "translationY" to target.view.translationY.toString(),
                "scaleX" to target.view.scaleX.toString(),
                "scaleY" to target.view.scaleY.toString()
            )
        )
    }

    private fun findViewByStablePath(elementId: String): View? {
        val parts = elementId.removePrefix("view_").split("/")
        if (parts.isEmpty()) return null
        var current: View = entries.firstOrNull { it.element.id == parts.first() }?.view ?: return null
        parts.drop(1).forEach { segment ->
            val index = segment.toIntOrNull() ?: return null
            val group = current as? ViewGroup ?: return null
            if (index !in 0 until group.childCount) return null
            current = group.getChildAt(index)
        }
        return current
    }

    private fun applyTransform(view: View, element: FreeformLayoutElement) {
        view.translationX = element.properties["translationX"]?.toFloatOrNull() ?: 0f
        view.translationY = element.properties["translationY"]?.toFloatOrNull() ?: 0f
        view.scaleX = element.properties["scaleX"]?.toFloatOrNull() ?: 1f
        view.scaleY = element.properties["scaleY"]?.toFloatOrNull() ?: 1f
        disableAncestorClipping(view)
    }

    private fun disableAncestorClipping(view: View) {
        var parent = view.parent
        while (parent is ViewGroup && parent !== this) {
            parent.clipChildren = false
            parent.clipToPadding = false
            parent = parent.parent
        }
    }

    private fun notifyLayoutChanged() {
        onLayoutChanged?.invoke(layoutElements())
    }
}
