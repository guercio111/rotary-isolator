package com.example.rotaryisolator
import android.content.Context
import android.content.SharedPreferences
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
    private val targetRoot: View,
    private val preferences: SharedPreferences,
    private val profile: String,
    private val onExit: () -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val prefix = "layout_designer_" + profile + "_"
    private var selected: View? = null
    private var moving: View? = null
    private var lastX = 0f
    private var lastY = 0f
    private var resizing = false
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC928.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f*density
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC928.toInt() }

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
        action("FINE") { saveAll(); onExit() }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, (50*density).toInt(), Gravity.BOTTOM))
        val hint = TextView(context).apply {
            text = "TOCCA per selezionare • TRASCINA per spostare • angolo giallo per ridimensionare"
            textSize = 10f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC171B20.toInt())
        }
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, (30*density).toInt(), Gravity.TOP))
        applySaved()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val v = selected ?: return
        if (v.width <= 0 || v.height <= 0) return
        val p = IntArray(2); val o = IntArray(2); v.getLocationOnScreen(p); getLocationOnScreen(o)
        val l = (p[0]-o[0]).toFloat(); val t = (p[1]-o[1]).toFloat()
        val r = l + v.width*v.scaleX; val b = t + v.height*v.scaleY
        canvas.drawRect(RectF(l,t,r,b), outline)
        val h = 11f*density; canvas.drawRect(r-h,b-h,r+h,b+h,handle)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.rawX; val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val current = selected
                if (current != null && inside(current,x,y)) {
                    moving = current
                    val p = IntArray(2); current.getLocationOnScreen(p)
                    resizing = x >= p[0]+current.width*current.scaleX-24f*density &&
                        y >= p[1]+current.height*current.scaleY-24f*density
                    lastX=x; lastY=y
                } else {
                    selected = findHit(targetRoot,x,y); moving=null
                    selected?.let { v ->
                        val title = (v as? TextView)?.text?.toString()?.take(22)
                            ?: v.contentDescription?.toString()?.take(22) ?: v.javaClass.simpleName
                        Toast.makeText(context,"Selezionato: "+title,Toast.LENGTH_SHORT).show()
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val v = moving ?: return true
                val dx=x-lastX; val dy=y-lastY
                if (resizing) {
                    v.scaleX=(v.scaleX+dx/max(1f,v.width.toFloat())).coerceIn(0.45f,2.5f)
                    v.scaleY=(v.scaleY+dy/max(1f,v.height.toFloat())).coerceIn(0.45f,2.5f)
                } else { v.translationX+=dx; v.translationY+=dy }
                lastX=x; lastY=y; invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moving != null) saveSelected()
                moving=null; performClick(); invalidate(); return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun inside(v: View,x: Float,y: Float): Boolean {
        val p=IntArray(2); v.getLocationOnScreen(p)
        return x>=p[0] && x<=p[0]+v.width*v.scaleX && y>=p[1] && y<=p[1]+v.height*v.scaleY
    }

    private fun findHit(group: ViewGroup,x: Float,y: Float): View? {
        for (i in group.childCount-1 downTo 0) {
            val child=group.getChildAt(i)
            if (child.visibility!=View.VISIBLE || child.alpha<0.05f || child.width<=0 || child.height<=0 || !inside(child,x,y)) continue
            val nested=child as? ViewGroup
            val deeper=nested?.let { findHit(it,x,y) }
            if (deeper!=null) return deeper
            return child
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
        val v=selected ?: return; val key=path(v) ?: return
        preferences.edit().putString(prefix+key,listOf(v.translationX,v.translationY,v.scaleX,v.scaleY).joinToString(",")).apply()
    }

    private fun saveAll() {
        val editor=preferences.edit()
        walk(targetRoot,"") { v,key ->
            if (v!==targetRoot) {
                val k=prefix+key
                if (v.translationX!=0f || v.translationY!=0f || v.scaleX!=1f || v.scaleY!=1f)
                    editor.putString(k,listOf(v.translationX,v.translationY,v.scaleX,v.scaleY).joinToString(","))
                else editor.remove(k)
            }
        }
        editor.apply()
    }

    private fun applySaved() {
        walk(targetRoot,"") { v,key ->
            val raw=preferences.getString(prefix+key,null) ?: return@walk
            val a=raw.split(",").mapNotNull { it.toFloatOrNull() }
            if (a.size==4) { v.translationX=a[0]; v.translationY=a[1]; v.scaleX=a[2].coerceIn(0.45f,2.5f); v.scaleY=a[3].coerceIn(0.45f,2.5f) }
        }
    }

    private fun resetAll() {
        val editor=preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
        walk(targetRoot,"") { v,_ -> if (v !== targetRoot) { v.translationX=0f; v.translationY=0f; v.scaleX=1f; v.scaleY=1f } }
        selected=null; invalidate()
    }
}
