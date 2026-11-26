package com.example.rotaryisolator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Absolute-position canvas for the FREQUALIZER layout designer.
 *
 * Children remain the original Android Views, so their listeners and audio
 * callbacks are not replaced. In normal mode this container does not intercept
 * input; in edit mode it intercepts gestures to move or resize the selected item.
 * Bounds are stored as normalized canvas coordinates.
 */
internal class FreeformCanvasLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private data class Entry(
        val view: View,
        var element: FreeformLayoutElement
    )

    private val entries = mutableListOf<Entry>()
    private var editMode = false
    private var selected: Entry? = null
    private var gestureMode = GestureMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startElement: FreeformLayoutElement? = null
    private var onLayoutChanged: ((List<FreeformLayoutElement>) -> Unit)? = null

    private enum class GestureMode { NONE, MOVE, RESIZE }

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
        invalidate()
    }

    fun addLayoutElement(view: View, element: FreeformLayoutElement) {
        require(entries.none { it.element.id == element.id }) {
            "Duplicate layout element id: ${element.id}"
        }
        val normalized = element.normalized()
        val entry = Entry(view, normalized)
        entries.add(entry)
        val lp = LayoutParams(1, 1)
        addView(view, lp)
        view.isClickable = view.isClickable
        requestLayout()
    }

    fun removeLayoutElement(id: String) {
        val entry = entries.firstOrNull { it.element.id == id } ?: return
        entries.remove(entry)
        removeView(entry.view)
        if (selected === entry) selected = null
        notifyLayoutChanged()
        invalidate()
    }

    fun clearLayoutElements() {
        entries.clear()
        removeAllViews()
        selected = null
        notifyLayoutChanged()
    }

    fun layoutElements(): List<FreeformLayoutElement> =
        entries.map { it.element }.sortedBy { it.zIndex }

    fun restoreLayoutElements(
        elements: List<FreeformLayoutElement>,
        viewFactory: (FreeformLayoutElement) -> View?
    ) {
        entries.clear()
        removeAllViews()
        elements.sortedBy { it.zIndex }.forEach { element ->
            val view = viewFactory(element) ?: return@forEach
            entries.add(Entry(view, element.normalized()))
            addView(view, LayoutParams(1, 1))
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        val h = measuredHeight
        entries.forEach { entry ->
            val element = entry.element
            val childWidth = (w * element.width).toInt().coerceAtLeast(1)
            val childHeight = (h * element.height).toInt().coerceAtLeast(1)
            entry.view.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
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
            entry.view.layout(
                childLeft,
                childTop,
                min(w, childLeft + childWidth),
                min(h, childTop + childHeight)
            )
            entry.view.translationZ = e.zIndex.toFloat()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return editMode || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                selected = findEntryAt(downX, downY)
                val entry = selected ?: return true
                startElement = entry.element
                val handleSize = 28f * resources.displayMetrics.density
                val right = entry.view.right.toFloat()
                val bottom = entry.view.bottom.toFloat()
                gestureMode = if (downX >= right - handleSize && downY >= bottom - handleSize) {
                    GestureMode.RESIZE
                } else {
                    GestureMode.MOVE
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val entry = selected ?: return true
                val start = startElement ?: return true
                if (start.locked || width <= 0 || height <= 0) return true
                val dx = (event.x - downX) / width
                val dy = (event.y - downY) / height
                entry.element = when (gestureMode) {
                    GestureMode.MOVE -> start.copy(
                        x = (start.x + dx).coerceIn(0f, 1f - start.width),
                        y = (start.y + dy).coerceIn(0f, 1f - start.height)
                    )
                    GestureMode.RESIZE -> start.copy(
                        width = (start.width + dx).coerceIn(0.04f, 1f - start.x),
                        height = (start.height + dy).coerceIn(0.04f, 1f - start.y)
                    )
                    GestureMode.NONE -> start
                }.normalized()
                requestLayout()
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
        selected?.let { entry ->
            val v = entry.view
            val rect = RectF(
                v.left.toFloat() + 1f,
                v.top.toFloat() + 1f,
                v.right.toFloat() - 1f,
                v.bottom.toFloat() - 1f
            )
            canvas.drawRect(rect, outlinePaint)
            val radius = 7f * resources.displayMetrics.density
            canvas.drawCircle(rect.right, rect.bottom, radius, handlePaint)
        }
    }

    private fun findEntryAt(x: Float, y: Float): Entry? =
        entries.asReversed().firstOrNull { entry ->
            x >= entry.view.left && x <= entry.view.right &&
                y >= entry.view.top && y <= entry.view.bottom
        }

    private fun notifyLayoutChanged() {
        onLayoutChanged?.invoke(layoutElements())
    }
}
