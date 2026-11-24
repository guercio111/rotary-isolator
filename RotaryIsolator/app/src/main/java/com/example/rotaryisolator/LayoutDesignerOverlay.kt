package com.example.rotaryisolator
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

/** Visual editor for existing tablet views; audio controls and listeners are never replaced. */
class LayoutDesignerOverlay(
    context: Context,
    private val targetRoot: ViewGroup,
    private val preferences: SharedPreferences,
    private val profile: String,
    private val onExit: () -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val prefix = "layout_designer_" + profile + "_"
    private val deletedPrefix = "layout_designer_deleted_" + profile + "_"
    private val initialVisibility = HashMap<View, Int>()
    private data class ViewState(val x: Float, val y: Float, val sx: Float, val sy: Float, val visibility: Int)
    private val undoHistory = java.util.ArrayDeque<Map<View, ViewState>>()
    private var selected: View? = null
    private val selectedViews = ArrayList<View>()
    private var moving: View? = null
    private var lastX = 0f
    private var lastY = 0f
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var marqueeSelecting = false
    private var marqueeActive = false
    private var marqueeStartX = 0f
    private var marqueeStartY = 0f
    private var marqueeX = 0f
    private var marqueeY = 0f
    private var snapEnabled = true
    private val gridStep get() = 8f * density
    private val moveOrigins = HashMap<View, Pair<Float, Float>>()
    private var moveBoundsLeft = 0f
    private var moveBoundsTop = 0f
    private var resizing = false
    private var rightButtonMoving = false
    private var resizeLeft = false
    private var resizeRight = false
    private var resizeTop = false
    private var resizeBottom = false
    private var pendingHit: View? = null
    private var dragStarted = false
    private var hitWasSelected = false
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC928.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f*density
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC928.toInt() }
    private val marqueePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF42C8FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f*density
    }
    private val marqueeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2242C8FF; style = Paint.Style.FILL
    }

    init {
        setWillNotDraw(false); isClickable = true; setBackgroundColor(0x18000000)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(0xEE171B20.toInt())
            setPadding((5*density).toInt(), (3*density).toInt(), (5*density).toInt(), (3*density).toInt())
        }
        fun action(title: String, callback: () -> Unit) {
            bar.addView(Button(context).apply {
                text = title; textSize = 10f; isAllCaps = false
                setOnClickListener { callback() }
            }, LinearLayout.LayoutParams(0, (42*density).toInt(), 1f).apply {
                leftMargin = (3*density).toInt(); rightMargin = (3*density).toInt()
            })
        }
        action("SALVA") { saveAll(); Toast.makeText(context, "Layout salvato", Toast.LENGTH_SHORT).show() }
        action("RIPRISTINA") { resetAll(); Toast.makeText(context, "Layout originale ripristinato", Toast.LENGTH_SHORT).show() }
        action("GRUPPO") { selectParentGroup() }
        action("DESELEZIONA") { selectedViews.clear(); selected = null; invalidate() }
        action("X") { deleteSelected() }
        fun sizeAction(title: String, factor: Float) {
            val repeatHandler = Handler(Looper.getMainLooper())
            lateinit var repeatAction: Runnable
            bar.addView(Button(context).apply {
                text = title; textSize = 11f; isAllCaps = false
                setPadding((3*density).toInt(), 0, (3*density).toInt(), 0)
                contentDescription = if (factor > 1f) "Tieni premuto per allargare la finestra" else "Tieni premuto per ridurre la finestra"
                setOnClickListener { }
                repeatAction = object : Runnable {
                    override fun run() {
                        if (isPressed) {
                            resizeSelectedByStep(factor, false)
                            repeatHandler.postDelayed(this, 240L)
                        }
                    }
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (selectedViews.isNotEmpty()) {
                                resizeSelectedByStep(factor, true)
                                repeatHandler.postDelayed(repeatAction, 420L)
                            } else {
                                Toast.makeText(context, "Seleziona uno o più elementi", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            repeatHandler.removeCallbacks(repeatAction)
                            view.performClick()
                            true
                        }
                        else -> true
                    }
                }
            }, LinearLayout.LayoutParams((88*density).toInt(), (42*density).toInt()).apply {
                leftMargin = (2*density).toInt(); rightMargin = (2*density).toInt()
            })
        }
        sizeAction("−  RIDUCI", 0.90f)
        sizeAction("+  ALLARGA", 1.10f)
        action("ANNULLA") { undoLast() }
        action("SNAP 8dp") {
            snapEnabled = !snapEnabled
            Toast.makeText(context, if (snapEnabled) "Aggancio griglia attivo (8 dp)" else "Aggancio griglia disattivato", Toast.LENGTH_SHORT).show()
        }
        action("FINE") { saveAll(); onExit() }
        // Keep the action bar away from the lower resize handles and content.
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, (48*density).toInt(), Gravity.TOP))
        walk(targetRoot, "") { v, _ ->
            if (v !== targetRoot) {
                initialVisibility[v] = v.visibility
                (v as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
            }
        }
        (targetRoot as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
        applySaved()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val o = IntArray(2); getLocationOnScreen(o)
        selectedViews.forEach { v ->
            if (v.width <= 0 || v.height <= 0) return@forEach
            val p = IntArray(2); v.getLocationOnScreen(p)
            val l = (p[0]-o[0]).toFloat(); val t = (p[1]-o[1]).toFloat()
            val r = l + v.width*v.scaleX; val b = t + v.height*v.scaleY
            canvas.drawRect(RectF(l,t,r,b), outline)
        }
        if (marqueeSelecting && marqueeActive) {
            val rect = RectF(minOf(marqueeStartX,marqueeX)-o[0], minOf(marqueeStartY,marqueeY)-o[1],
                maxOf(marqueeStartX,marqueeX)-o[0], maxOf(marqueeStartY,marqueeY)-o[1])
            canvas.drawRect(rect, marqueeFill)
            canvas.drawRect(rect, marqueePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.rawX; val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findHit(targetRoot,x,y)
                val rightButton = (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                val alreadySelected = hit != null && selectedViews.any { it === hit || isDescendant(hit,it) }
                if (hit == null) {
                    marqueeSelecting = true; marqueeActive = false
                    marqueeStartX=x; marqueeStartY=y; marqueeX=x; marqueeY=y
                    moving=null; resizing=false; rightButtonMoving=false; moveOrigins.clear()
                } else if (rightButton) {
                    // Right mouse button is reserved for moving the selected element.
                    if (!alreadySelected) {
                        selectedViews.clear(); selectedViews.add(hit); selected=hit
                    }
                    pushUndo()
                    moving = if (selectedViews.size == 1) selectedViews.first() else null
                    resizing = false; rightButtonMoving = moving != null
                    moveOrigins.clear()
                    selectedViews.forEach { moveOrigins[it]=it.translationX to it.translationY }
                    val bounds = selectionBounds(selectedViews)
                    moveBoundsLeft=bounds?.left ?: x; moveBoundsTop=bounds?.top ?: y
                    gestureStartX=x; gestureStartY=y
                } else if (alreadySelected) {
                    pushUndo()
                    moving = if (selectedViews.size == 1) selectedViews.first() else null
                    rightButtonMoving = false
                    resizing = false
                    val current = moving
                    if (current != null) {
                        val p = IntArray(2); current.getLocationOnScreen(p)
                        val left=p[0].toFloat(); val top=p[1].toFloat()
                        val right=left+current.width*current.scaleX
                        val bottom=top+current.height*current.scaleY
                        val edgeX=minOf(26f*density,current.width*current.scaleX*0.30f)
                        val edgeY=minOf(26f*density,current.height*current.scaleY*0.30f)
                        val isDivider=(current.tag as? String)?.startsWith("designer_divider_")==true
                        resizeLeft=!isDivider && x<=left+edgeX
                        resizeRight=!isDivider && x>=right-edgeX
                        resizeTop=!isDivider && y<=top+edgeY
                        resizeBottom=!isDivider && y>=bottom-edgeY
                        resizing=resizeLeft||resizeRight||resizeTop||resizeBottom
                    }
                    // Left-button dragging on the body no longer moves the element.
                    if (!resizing) moving = null
                    moveOrigins.clear()
                    selectedViews.forEach { moveOrigins[it]=it.translationX to it.translationY }
                    val bounds = selectionBounds(selectedViews)
                    moveBoundsLeft=bounds?.left ?: x; moveBoundsTop=bounds?.top ?: y
                    gestureStartX=x; gestureStartY=y
                } else {
                    selectedViews.clear(); selectedViews.add(hit); selected=hit
                    moving=null; resizing=false; rightButtonMoving=false
                    val title = (hit as? TextView)?.text?.toString()?.take(22)
                        ?: hit.contentDescription?.toString()?.take(22) ?: hit.javaClass.simpleName
                    Toast.makeText(context,"Selezionato: "+title,Toast.LENGTH_SHORT).show()
                }
                lastX=x; lastY=y; invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (marqueeSelecting) {
                    marqueeX=x; marqueeY=y
                    if (!marqueeActive && (kotlin.math.abs(x-marqueeStartX)>6f*density || kotlin.math.abs(y-marqueeStartY)>6f*density)) marqueeActive=true
                    invalidate(); return true
                }
                if (selectedViews.isEmpty()) return true
                if (resizing && moving != null) {
                    val v=moving!!
                    resizeSnapped(v,x-lastX,y-lastY)
                    lastX=x; lastY=y
                } else if (rightButtonMoving && moveOrigins.isNotEmpty()) {
                    val dx=x-gestureStartX; val dy=y-gestureStartY
                    var snappedDx=dx; var snappedDy=dy
                    if (snapEnabled) {
                        snappedDx=(kotlin.math.round((moveBoundsLeft+dx)/gridStep)*gridStep)-moveBoundsLeft
                        snappedDy=(kotlin.math.round((moveBoundsTop+dy)/gridStep)*gridStep)-moveBoundsTop
                    }
                    selectedViews.forEach { v ->
                        val origin=moveOrigins[v] ?: return@forEach
                        v.translationX=origin.first+snappedDx
                        v.translationY=origin.second+snappedDy
                    }
                }
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (marqueeSelecting) {
                    if (marqueeActive && event.actionMasked==MotionEvent.ACTION_UP) selectInRectangle()
                    marqueeSelecting=false; marqueeActive=false
                } else if (selectedViews.isNotEmpty()) {
                    saveSelected()
                }
                moving=null; resizing=false; rightButtonMoving=false; moveOrigins.clear()
                performClick(); invalidate(); return true
            }
        }
        return true
    }

    private fun pushUndo() {
        val snapshot = LinkedHashMap<View, ViewState>()
        walk(targetRoot, "") { v, _ ->
            if (v !== targetRoot) snapshot[v] = ViewState(v.translationX, v.translationY, v.scaleX, v.scaleY, v.visibility)
        }
        undoHistory.addLast(snapshot)
        while (undoHistory.size > 30) undoHistory.removeFirst()
    }

    private fun undoLast() {
        if (undoHistory.isEmpty()) {
            Toast.makeText(context, "Nessuna modifica da annullare", Toast.LENGTH_SHORT).show()
            return
        }
        undoHistory.removeLast().forEach { (v, state) ->
            v.translationX = state.x
            v.translationY = state.y
            v.scaleX = state.sx
            v.scaleY = state.sy
            v.visibility = state.visibility
        }
        saveAll()
        selectedViews.clear()
        selected = null
        moving = null
        resizing = false
        invalidate()
        Toast.makeText(context, "Ultima modifica annullata", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelected() {
        val targets = selectedViews.toList().ifEmpty { listOfNotNull(selected) }
        if (targets.isEmpty()) {
            Toast.makeText(context, "Seleziona prima un elemento", Toast.LENGTH_SHORT).show()
            return
        }
        pushUndo()
        targets.forEach { it.visibility = View.INVISIBLE }
        saveAll()
        selectedViews.clear()
        selected = null
        moving = null
        resizing = false
        invalidate()
        Toast.makeText(context, if (targets.size == 1) "Elemento rimosso" else "${targets.size} elementi rimossi", Toast.LENGTH_SHORT).show()
    }

    /** Resize all selected items as one visual group, preserving relative spacing and shape. */
    private fun resizeSelectedByStep(factor: Float, recordUndo: Boolean = true) {
        val targets = selectedViews.toList()
        if (targets.isEmpty()) {
            if (recordUndo) Toast.makeText(context, "Seleziona uno o più elementi", Toast.LENGTH_SHORT).show()
            return
        }
        if (targets.any { it.width <= 0 || it.height <= 0 }) return
        if (recordUndo) pushUndo()
        val bounds = selectionBounds(targets) ?: return
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        var changed = false
        targets.forEach { v ->
            val oldScaleX = v.scaleX
            val oldScaleY = v.scaleY
            val newScaleX = (oldScaleX * factor).coerceIn(0.45f, 2.5f)
            val newScaleY = (oldScaleY * factor).coerceIn(0.45f, 2.5f)
            if (newScaleX == oldScaleX && newScaleY == oldScaleY) return@forEach
            val p = IntArray(2); v.getLocationOnScreen(p)
            val itemCenterX = p[0] + v.width * oldScaleX / 2f
            val itemCenterY = p[1] + v.height * oldScaleY / 2f
            val newCenterX = centerX + (itemCenterX - centerX) * factor
            val newCenterY = centerY + (itemCenterY - centerY) * factor
            v.scaleX = newScaleX
            v.scaleY = newScaleY
            v.translationX += newCenterX - itemCenterX
            v.translationY += newCenterY - itemCenterY
            changed = true
        }
        if (!changed) {
            if (recordUndo && undoHistory.isNotEmpty()) undoHistory.removeLast()
            if (recordUndo) Toast.makeText(context, "Dimensione massima o minima raggiunta", Toast.LENGTH_SHORT).show()
            return
        }
        saveSelected()
        invalidate()
    }
    private fun resizeSnapped(v: View, dx: Float, dy: Float) {
        if (resizeLeft || resizeRight) {
            val oldSize=v.width*v.scaleX
            val growth=if(resizeLeft) -dx else dx
            val wanted=(oldSize+growth).coerceIn(v.width*0.45f,v.width*2.5f)
            val newSize=if(snapEnabled) (kotlin.math.round(wanted/gridStep)*gridStep).coerceIn(v.width*0.45f,v.width*2.5f) else wanted
            val actual=newSize-oldSize
            v.scaleX=(newSize/v.width).coerceIn(0.45f,2.5f)
            v.translationX += (if(resizeLeft) -0.5f else 0.5f)*actual
        }
        if (resizeTop || resizeBottom) {
            val oldSize=v.height*v.scaleY
            val growth=if(resizeTop) -dy else dy
            val wanted=(oldSize+growth).coerceIn(v.height*0.45f,v.height*2.5f)
            val newSize=if(snapEnabled) (kotlin.math.round(wanted/gridStep)*gridStep).coerceIn(v.height*0.45f,v.height*2.5f) else wanted
            val actual=newSize-oldSize
            v.scaleY=(newSize/v.height).coerceIn(0.45f,2.5f)
            v.translationY += (if(resizeTop) -0.5f else 0.5f)*actual
        }
    }

    private fun selectionBounds(views: List<View>): RectF? {
        if (views.isEmpty()) return null
        var left=Float.POSITIVE_INFINITY; var top=Float.POSITIVE_INFINITY
        var right=Float.NEGATIVE_INFINITY; var bottom=Float.NEGATIVE_INFINITY
        views.forEach { v ->
            val p=IntArray(2); v.getLocationOnScreen(p)
            left=minOf(left,p[0].toFloat()); top=minOf(top,p[1].toFloat())
            right=maxOf(right,p[0]+v.width*v.scaleX); bottom=maxOf(bottom,p[1]+v.height*v.scaleY)
        }
        return RectF(left,top,right,bottom)
    }

    private fun isDescendant(view: View, ancestor: View): Boolean {
        var current: android.view.ViewParent? = view.parent
        while (current is View) {
            if (current===ancestor) return true
            current=current.parent
        }
        return false
    }

    private fun selectInRectangle() {
        val rect=RectF(minOf(marqueeStartX,marqueeX),minOf(marqueeStartY,marqueeY),
            maxOf(marqueeStartX,marqueeX),maxOf(marqueeStartY,marqueeY))
        selectedViews.clear()
        collectInRectangle(targetRoot,rect,selectedViews)
        selected=selectedViews.firstOrNull()
        Toast.makeText(context, if(selectedViews.isEmpty()) "Nessun elemento nel riquadro" else "${selectedViews.size} elementi selezionati",Toast.LENGTH_SHORT).show()
    }

    private fun collectInRectangle(group: ViewGroup, rect: RectF, out: MutableList<View>) {
        for(i in 0 until group.childCount) {
            val child=group.getChildAt(i)
            if(child.visibility!=View.VISIBLE || child.alpha<0.05f || child.width<=0 || child.height<=0) continue
            val p=IntArray(2); child.getLocationOnScreen(p)
            val bounds=RectF(p[0].toFloat(),p[1].toFloat(),p[0]+child.width*child.scaleX,p[1]+child.height*child.scaleY)
            if(!RectF.intersects(rect,bounds)) continue
            val tag=child.tag as? String
            val tagged=tag?.startsWith("designer_group_")==true || tag?.startsWith("designer_divider_")==true || tag?.startsWith("designer_selectable_")==true
            if(tagged || child !is ViewGroup) out.add(child)
            else collectInRectangle(child,rect,out)
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun inside(v: View,x: Float,y: Float): Boolean {
        val p=IntArray(2); v.getLocationOnScreen(p)
        val isDivider = (v.tag as? String)?.startsWith("designer_divider_") == true
        val pad = if (isDivider) 18f*density else 0f
        return x>=p[0]-pad && x<=p[0]+v.width*v.scaleX+pad &&
            y>=p[1]-pad && y<=p[1]+v.height*v.scaleY+pad
    }

    /** Promote the currently selected control to its nearest semantic group. */
    private fun selectParentGroup() {
        var current = selected ?: return
        var parent = current.parent as? View
        while (parent != null && parent !== targetRoot) {
            val tag = parent.tag as? String
            if (tag?.startsWith("designer_group_") == true) {
                selected = parent
                selectedViews.clear(); selectedViews.add(parent)
                val label = tag.removePrefix("designer_group_").replace('_',' ').uppercase()
                Toast.makeText(context, "Gruppo selezionato: $label", Toast.LENGTH_SHORT).show()
                invalidate()
                return
            }
            parent = parent.parent as? View
        }
        Toast.makeText(context, "Nessun gruppo disponibile per questo elemento", Toast.LENGTH_SHORT).show()
    }

    private fun findHit(group: ViewGroup,x: Float,y: Float): View? {
        // Dividers are only 1dp wide, so give their expanded touch zones priority.
        for (i in group.childCount-1 downTo 0) {
            val child=group.getChildAt(i)
            val tag=child.tag as? String
            if ((tag?.startsWith("designer_divider_")==true || tag?.startsWith("designer_selectable_")==true) &&
                child.visibility==View.VISIBLE && child.alpha>=0.05f &&
                child.width>0 && child.height>0 && inside(child,x,y)) return child
        }
        for (i in group.childCount-1 downTo 0) {
            val child=group.getChildAt(i)
            if (child.visibility!=View.VISIBLE || child.alpha<0.05f || child.width<=0 || child.height<=0 || !inside(child,x,y)) continue
            val nested=child as? ViewGroup
            val deeper=nested?.let { findHit(it,x,y) }
            if (deeper!=null) return deeper
            // Untagged containers with no child under the pointer are empty canvas,
            // allowing a drag there to begin a marquee instead of selecting the whole panel.
            if (nested==null || (child.tag as? String)?.startsWith("designer_group_")==true) return child
        }
        return null
    }

    private fun path(v: View): String? {
        val parts=ArrayList<Int>(); var current=v
        while (current!==targetRoot) {
            val parent=current.parent as? ViewGroup ?: return null
            parts.add(parent.indexOfChild(current)); current=parent
        }
        return parts.asReversed().joinToString(".")
    }

    private fun walk(v: View,key: String,action: (View,String)->Unit) {
        action(v,key); val group=v as? ViewGroup ?: return
        for (i in 0 until group.childCount) walk(group.getChildAt(i),if(key.isEmpty()) "$i" else "$key.$i",action)
    }

    private fun saveSelected() {
        val targets=if(selectedViews.isNotEmpty()) selectedViews.toList() else listOfNotNull(selected)
        val editor=preferences.edit()
        targets.forEach { v ->
            val key=path(v) ?: return@forEach
            editor.putString(prefix+key,listOf(v.translationX,v.translationY,v.scaleX,v.scaleY).joinToString(","))
        }
        editor.apply()
    }

    private fun saveAll() {
        val editor=preferences.edit()
        walk(targetRoot,"") { v,key ->
            if (v!==targetRoot) {
                val k=prefix+key
                if (v.translationX!=0f || v.translationY!=0f || v.scaleX!=1f || v.scaleY!=1f)
                    editor.putString(k,listOf(v.translationX,v.translationY,v.scaleX,v.scaleY).joinToString(","))
                else editor.remove(k)
                val baseline = initialVisibility[v] ?: View.VISIBLE
                if (v.visibility != baseline) editor.putBoolean(deletedPrefix+key, v.visibility == View.INVISIBLE || v.visibility == View.GONE)
                else editor.remove(deletedPrefix+key)
            }
        }
        editor.apply()
    }

    private fun applySaved() {
        walk(targetRoot,"") { v,key ->
            if (v !== targetRoot) {
                val baseline = initialVisibility[v] ?: v.visibility
                v.visibility = if (preferences.getBoolean(deletedPrefix+key, false)) View.INVISIBLE else baseline
            }
            val raw=preferences.getString(prefix+key,null) ?: return@walk
            val a=raw.split(",").mapNotNull { it.toFloatOrNull() }
            if (a.size==4) { v.translationX=a[0]; v.translationY=a[1]; v.scaleX=a[2].coerceIn(0.45f,2.5f); v.scaleY=a[3].coerceIn(0.45f,2.5f) }
        }
    }

    private fun resetAll() {
        pushUndo()
        val editor=preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) || it.startsWith(deletedPrefix) }.forEach { editor.remove(it) }
        editor.apply()
        walk(targetRoot,"") { v,_ ->
            if (v !== targetRoot) {
                v.translationX=0f; v.translationY=0f; v.scaleX=1f; v.scaleY=1f
                v.visibility=initialVisibility[v] ?: View.VISIBLE
            }
        }
        selected=null; selectedViews.clear(); moving=null; marqueeSelecting=false; marqueeActive=false; invalidate()
    }
}
