package com.example.rotaryisolator

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import kotlin.math.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("rotary_options_v3", MODE_PRIVATE) }
    private var dpEffect: DynamicsProcessing? = null
    private var audioReady = false
    private var compact = false
    private var skin = 0
    private var limiterOn = false
    private var eqOn = true
    private var isoOn = true
    private var filtersOn = false
    private var gain = 0f
    private val eq = FloatArray(3)
    private val iso = floatArrayOf(0f, 0f, 0f)
    // HPF, LPF, MID PASS intensity (0..100%)
    private val filters = floatArrayOf(0f, 0f, 0f)
    private val eqControlOn = BooleanArray(3) { true }
    private val isoControlOn = BooleanArray(3) { true }
    private val filterControlOn = BooleanArray(3) { true }
    private val handler = Handler(Looper.getMainLooper())
    private val dspUpdate = Runnable { applyDspNow() }
    private val saveUpdate = Runnable { save() }
    private val modes = mutableMapOf("EQ" to false, "ISOLATOR" to false, "FILTERS" to true, "GAIN" to false)
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val sections = mutableMapOf<String, LinearLayout>()
    private val gold get() = when(skin) { 1 -> Color.rgb(100,190,210); 2 -> Color.rgb(220,130,80); else -> Color.rgb(214,179,106) }
    private val bg get() = when(skin) { 1 -> Color.rgb(12,24,31); 2 -> Color.rgb(28,18,15); else -> Color.rgb(17,19,23) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        compact = prefs.getBoolean("mini", false)
        skin = prefs.getInt("skin", 0).coerceIn(0,2)
        limiterOn = prefs.getBoolean("limiter", false)
        eqOn = prefs.getBoolean("eq_on", true); isoOn = prefs.getBoolean("iso_on", true); filtersOn = prefs.getBoolean("filters_on", false)
        gain = prefs.getFloat("gain", 0f).coerceIn(-12f,12f)
        for (i in 0..2) { eq[i] = prefs.getFloat("eq_$i",0f); iso[i] = prefs.getFloat("iso_$i",0f) }
        filters[0] = prefs.getFloat("hpf",0f); filters[1] = prefs.getFloat("lpf",0f); filters[2] = prefs.getFloat("midpass",0f)
        for (i in 0..2) {
            eqControlOn[i] = prefs.getBoolean("eq_control_$i", true)
            isoControlOn[i] = prefs.getBoolean("iso_control_$i", true)
            filterControlOn[i] = prefs.getBoolean("filter_control_$i", true)
        }
        for (key in modes.keys) modes[key] = prefs.getBoolean("mode_$key", key=="FILTERS")
        buildUi()
        connectAudio()
    }

    private fun dp(v:Int)= (v*resources.displayMetrics.density).roundToInt()
    private fun label(t:String, size:Float=11f, color:Int=Color.LTGRAY)=TextView(this).apply {
        text=t; textSize=size; setTextColor(color); gravity=Gravity.CENTER; letterSpacing=.08f
    }
    private fun button(t:String, action:()->Unit)=Button(this).apply { text=t; setOnClickListener { action() } }
    private fun save() {
        prefs.edit().putBoolean("mini",compact).putInt("skin",skin).putBoolean("limiter",limiterOn)
            .putBoolean("eq_on",eqOn).putBoolean("iso_on",isoOn).putBoolean("filters_on",filtersOn).putFloat("gain",gain)
            .apply {
                for(i in 0..2){putFloat("eq_$i",eq[i]);putFloat("iso_$i",iso[i])}
                putFloat("hpf",filters[0]);putFloat("lpf",filters[1]);putFloat("midpass",filters[2])
                for(i in 0..2){putBoolean("eq_control_$i",eqControlOn[i]);putBoolean("iso_control_$i",isoControlOn[i]);putBoolean("filter_control_$i",filterControlOn[i])}
                modes.forEach{(k,v)->putBoolean("mode_$k",v)}
            }.apply()
    }

    private fun buildUi() {
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(6));setBackgroundColor(bg)}
        root.addView(label("ROTARY ISOLATOR", if(compact) 17f else 22f,Color.WHITE),LinearLayout.LayoutParams(-1,dp(30)))
        root.addView(label("MASTER PROCESSING  •  V10 / XONE CONCEPT",9f,gold),LinearLayout.LayoutParams(-1,dp(20)))
        val scroll=ScrollView(this)
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        sections.clear()
        addSection("EQ","EQUALIZER  •  LOW / MID / HIGH",eq, -12f..12f, {eqOn}) { scheduleUpdate() }
        addSection("ISOLATOR","ISOLATOR  •  LOW / MID / HIGH",iso, -24f..24f, {isoOn}) { scheduleUpdate() }
        addFilterSection()
        addGainSection()
        val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        bar.addView(button(if(eqOn)"EQ ON" else "EQ OFF"){eqOn=!eqOn;save();refreshUi();applyDsp()},LinearLayout.LayoutParams(0,dp(44),1f))
        bar.addView(button(if(isoOn)"ISO ON" else "ISO OFF"){isoOn=!isoOn;save();refreshUi();applyDsp()},LinearLayout.LayoutParams(0,dp(44),1f))
        bar.addView(button(if(filtersOn)"FILTER ON" else "FILTER OFF"){filtersOn=!filtersOn;save();refreshUi();applyDsp()},LinearLayout.LayoutParams(0,dp(44),1f))
        bar.addView(button("OPTIONS"){showOptions()},LinearLayout.LayoutParams(0,dp(44),1f))
        root.addView(bar)
        status=label("Connecting audio engine…",10f)
        root.addView(status,LinearLayout.LayoutParams(-1,dp(22)))
        setContentView(root)
    }

    private fun scheduleUpdate() {
        // Update DSP at display cadence; persist settings off the hot interaction path.
        handler.removeCallbacks(dspUpdate)
        handler.postDelayed(dspUpdate, 12L)
        handler.removeCallbacks(saveUpdate)
        handler.postDelayed(saveUpdate, 220L)
    }

    private fun addSection(key:String,title:String,values:FloatArray,range:ClosedFloatingPointRange<Float>,enabled:()->Boolean,onChange:()->Unit) {
        val active = if(key=="EQ") eqControlOn else isoControlOn
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(2))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        header.addView(label(title,10f,gold),LinearLayout.LayoutParams(0,dp(26),1f))
        header.addView(button("FLAT"){ for(i in 0..2) values[i]=0f; save(); refreshUi(); scheduleUpdate() },LinearLayout.LayoutParams(dp(66),dp(30)))
        box.addView(header)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=3f}
        val names=listOf("LOW","MID","HIGH")
        for(i in 0..2){
            val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val vLabel=label("${names[i]}  ${fmt(values[i])} dB", if(compact)10f else 12f,Color.WHITE)
            if(modes[key]==true) {
                val seek=SeekBar(this).apply{
                    max=(range.endInclusive-range.start).roundToInt()*10
                    progress=((values[i]-range.start)*10).roundToInt().coerceIn(0,max)
                    isEnabled=active[i]
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user){values[i]=range.start+p/10f;vLabel.text="${names[i]}  ${fmt(values[i])} dB";scheduleUpdate()}}
                        override fun onStartTrackingTouch(s:SeekBar?){}
                        override fun onStopTrackingTouch(s:SeekBar?){scheduleUpdate()}
                    })
                }
                col.addView(vLabel);col.addView(seek,LinearLayout.LayoutParams(-1,dp(48)))
            } else {
                val knob=RotaryControl(this,names[i],values[i],range.start,range.endInclusive,gold,{v->values[i]=v;vLabel.text="${names[i]}  ${fmt(v)} dB";scheduleUpdate()},{fmt(values[i])+" dB"})
                knob.isEnabled=active[i]
                col.addView(knob,LinearLayout.LayoutParams(-1,dp(if(compact)104 else 132)))
            }
            val bypass=button(if(active[i])"BYP" else "BYP OFF"){active[i]=!active[i];save();refreshUi();scheduleUpdate()}
            bypass.textSize=9f
            col.addView(bypass,LinearLayout.LayoutParams(dp(62),dp(28)).apply{gravity=Gravity.END})
            row.addView(col,LinearLayout.LayoutParams(0,-1,1f))
        }
        box.addView(row);content.addView(box);sections[key]=box
    }

    private fun addFilterSection(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(3),dp(4),dp(2))}
        box.addView(label("FILTERS  •  HPF / MID PASS / LPF",10f,gold),LinearLayout.LayoutParams(-1,dp(24)))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=3f}
        val names=listOf("HPF","MID PASS","LPF")
        for(i in 0..2){
            val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val l=label("${names[i]}  ${(filters[i]*100).roundToInt()}%",10f,Color.WHITE)
            if(modes["FILTERS"]==true){
                val seek=SeekBar(this).apply{
                    max=100;progress=(filters[i]*100).roundToInt();isEnabled=filterControlOn[i]
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){if(user){filters[i]=p/100f;l.text="${names[i]}  $p%";scheduleUpdate()}}
                        override fun onStartTrackingTouch(b:SeekBar?){}
                        override fun onStopTrackingTouch(b:SeekBar?){scheduleUpdate()}
                    })
                }
                col.addView(l);col.addView(seek,LinearLayout.LayoutParams(-1,dp(48)))
            }else{
                val knob=RotaryControl(this,names[i],filters[i],0f,1f,gold,{v->filters[i]=v;l.text="${names[i]}  ${(v*100).roundToInt()}%";scheduleUpdate()},{"${(filters[i]*100).roundToInt()}%"})
                knob.isEnabled=filterControlOn[i]
                col.addView(knob,LinearLayout.LayoutParams(-1,dp(if(compact)104 else 132)))
            }
            val bypass=button(if(filterControlOn[i])"BYP" else "BYP OFF"){filterControlOn[i]=!filterControlOn[i];save();refreshUi();scheduleUpdate()}
            bypass.textSize=9f
            col.addView(bypass,LinearLayout.LayoutParams(dp(62),dp(28)).apply{gravity=Gravity.END})
            row.addView(col,LinearLayout.LayoutParams(0,-1,1f))
        }
        box.addView(row);content.addView(box);sections["FILTERS"]=box
    }

    private fun addGainSection(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(3),dp(4),dp(2))}
        box.addView(label("MASTER GAIN",10f,gold))
        if(modes["GAIN"]==true){
            val l=label("GAIN  ${fmt(gain)} dB",12f,Color.WHITE)
            val s=SeekBar(this).apply{max=240;progress=((gain+12)*10).roundToInt();setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){if(user){gain=p/10f-12f;l.text="GAIN  ${fmt(gain)} dB";save();applyDsp()}}
                override fun onStartTrackingTouch(b:SeekBar?){}
                override fun onStopTrackingTouch(b:SeekBar?){}
            })}
            box.addView(l);box.addView(s)
        }else{
            box.addView(RotaryControl(this,"GAIN",gain,-12f,12f,gold,{v->gain=v;save();applyDsp()}){"${fmt(gain)} dB"},LinearLayout.LayoutParams(-1,dp(92)))
        }
        content.addView(box);sections["GAIN"]=box
    }

    private fun refreshUi(){ buildUi() }

    private fun connectAudio(){
        if(Build.VERSION.SDK_INT<28){status.text="Dynamics Processing richiede Android 9 o superiore";return}
        try{
            val cfg=DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,2,true,3,false,0,true,3,limiterOn).build()
            dpEffect=DynamicsProcessing(0,0,cfg).also{it.enabled=false}
            audioReady=true;status.text="Audio engine disponibile • verifica uscita globale sul dispositivo";applyDsp()
        }catch(t:Throwable){audioReady=false;status.text="Effetto globale non disponibile su questo dispositivo"}
    }

    private fun applyDsp(){ scheduleUpdate() }

    private fun applyDspNow(){
        val fx=dpEffect?:return
        if(Build.VERSION.SDK_INT<28)return
        try{
            val freqs=floatArrayOf(250f,4000f,20000f)
            for(ch in 0..1)for(i in 0..2){
                var eqGain=if(eqOn && eqControlOn[i])eq[i].coerceIn(-12f,12f) else 0f
                if(filtersOn && filterControlOn[0] && i==0) eqGain -= filters[0]*24f
                if(filtersOn && filterControlOn[1] && i==2) eqGain -= filters[1]*24f
                if(filtersOn && filterControlOn[2] && i!=1) eqGain -= filters[2]*24f
                val eqBand=DynamicsProcessing.EqBand(true,freqs[i],eqGain.coerceIn(-36f,12f))
                fx.setPreEqBandByChannelIndex(ch,i,eqBand)
                val isoGain=if(isoOn && isoControlOn[i])iso[i].coerceIn(-24f,24f) else 0f
                fx.setPostEqBandByChannelIndex(ch,i,DynamicsProcessing.EqBand(true,freqs[i],isoGain))
            }
            fx.setInputGainAllChannelsTo(gain.coerceIn(-12f,12f))
            if(limiterOn){
                val lim=DynamicsProcessing.Limiter(true,true,0,5f,80f,20f,-1f,0f)
                fx.setLimiterAllChannelsTo(lim)
            }
            fx.enabled=eqOn||isoOn||filtersOn||abs(gain)>.01f||limiterOn
            status.text=if(fx.enabled)"Processing ON • EQ / ISO / Gain" else "BYPASS"
        }catch(t:Throwable){status.text="Audio DSP non supportato dal driver del dispositivo"}
    }

    private fun showOptions(){
        val names=arrayOf("Controlli EQ","Controlli Isolator","Controlli Filtri","Controlli Gain","Interfaccia MINI / EXTENDED","Tema Pioneer V10","Tema Xone","Tema Rotativo classico","Limiter ON / OFF")
        AlertDialog.Builder(this).setTitle("OPTIONS")
            .setItems(names){d,w->
                when(w){
                    in 0..3->{val key=listOf("EQ","ISOLATOR","FILTERS","GAIN")[w];val opts=arrayOf("Manopole rotative","Slider");AlertDialog.Builder(this).setTitle("$key • tipo controllo").setSingleChoiceItems(opts,if(modes[key]==true)1 else 0){a,c->modes[key]=c==1;save();refreshUi();applyDsp();a.dismiss()}.setNegativeButton("Annulla",null).show()}
                    4->{compact=!compact;save();refreshUi();applyDsp()}
                    in 5..7->{skin=w-5;save();refreshUi();applyDsp()}
                    8->{limiterOn=!limiterOn;save();rebuildEffect()}
                }
            }.setNegativeButton("CHIUDI",null).show()
    }
    private fun rebuildEffect(){
        try{dpEffect?.release()}catch(_:Throwable){}
        dpEffect=null;connectAudio()
    }
    private fun fmt(v:Float)="%.1f".format(v)
    override fun onDestroy(){try{dpEffect?.enabled=false;dpEffect?.release()}catch(_:Throwable){};super.onDestroy()}
}

private class RotaryControl(
    context:android.content.Context,
    private val title:String,
    initial:Float,
    private val minV:Float,
    private val maxV:Float,
    private val accent:Int,
    private val changed:(Float)->Unit,
    private val valueText:()->String
):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var v=initial
    private var downY=0f
    private var downV=initial
    private val density get()=resources.displayMetrics.density
    private fun dp(x:Float)=x*density
    private val rect get()={
        val side=min(width,height)*.80f
        RectF(width/2f-side/2,height/2f-side/2- dp(7f),width/2f+side/2,height/2f+side/2-dp(7f))
    }()
    init{isFocusable=true;isClickable=true;contentDescription="$title rotary control"}
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        val r=rect;val cx=r.centerX();val cy=r.centerY();val rad=r.width()/2f
        val arc=RectF(cx-rad+dp(5f),cy-rad+dp(5f),cx+rad-dp(5f),cy+rad-dp(5f))
        p.style=Paint.Style.STROKE;p.strokeWidth=dp(6f);p.strokeCap=Paint.Cap.ROUND;p.color=0xFF454B55.toInt()
        c.drawArc(arc,135f,270f,false,p)
        // Clearly visible click targets around the dial.
        for(i in 0..20){
            val a=Math.toRadians((135.0+i*270.0/20.0))
            val inner=rad*if(i%5==0) .78f else .84f
            val outer=rad*.98f
            p.color=if(i%5==0)accent else 0xFF9AA2AE.toInt()
            p.strokeWidth=dp(if(i%5==0)2.5f else 1.3f)
            c.drawLine(cx+cos(a).toFloat()*inner,cy+sin(a).toFloat()*inner,cx+cos(a).toFloat()*outer,cy+sin(a).toFloat()*outer,p)
            if(i%5==0){
                p.style=Paint.Style.FILL
                c.drawCircle(cx+cos(a).toFloat()*rad*.91f,cy+sin(a).toFloat()*rad*.91f,dp(3.2f),p)
                p.style=Paint.Style.STROKE
            }
        }
        val n=((v-minV)/(maxV-minV)).coerceIn(0f,1f)
        p.color=accent;p.strokeWidth=dp(6f);c.drawArc(arc,135f,270f*n,false,p)
        p.style=Paint.Style.FILL;p.color=0xFF292E36.toInt();c.drawCircle(cx,cy,rad*.70f,p)
        val a=Math.toRadians((135+270*n).toDouble())
        p.color=Color.WHITE;p.strokeWidth=dp(3f)
        c.drawLine(cx,cy,cx+cos(a).toFloat()*rad*.52f,cy+sin(a).toFloat()*rad*.52f,p)
        p.textAlign=Paint.Align.CENTER;p.textSize=dp(11f);p.color=Color.WHITE
        c.drawText(title,cx,r.bottom+dp(17f),p)
        p.color=accent;p.textSize=dp(10f);c.drawText(valueText(),cx,r.bottom+dp(31f),p)
    }
    private fun updateFromPoint(x:Float,y:Float){
        val r=rect;val cx=r.centerX();val cy=r.centerY()
        var degrees=Math.toDegrees(atan2((y-cy).toDouble(),(x-cx).toDouble())).toFloat()
        if(degrees<0f)degrees+=360f
        if(degrees<45f)degrees+=360f
        val fraction=((degrees.coerceIn(135f,405f)-135f)/270f).coerceIn(0f,1f)
        v=minV+fraction*(maxV-minV)
        changed(v);invalidate()
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.action){
            MotionEvent.ACTION_DOWN->{
                downY=e.y;downV=v
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromPoint(e.x,e.y)
                return true
            }
            MotionEvent.ACTION_MOVE->{
                // Direct angular positioning makes the dial usable with mouse and stylus.
                updateFromPoint(e.x,e.y)
                return true
            }
            MotionEvent.ACTION_UP->{performClick();return true}
            MotionEvent.ACTION_CANCEL->{return true}
        }
        return true
    }
    override fun onGenericMotionEvent(e:MotionEvent):Boolean{
        if(e.action==MotionEvent.ACTION_SCROLL){
            v=(v+e.getAxisValue(MotionEvent.AXIS_VSCROLL)*(maxV-minV)*.012f).coerceIn(minV,maxV)
            changed(v);invalidate();return true
        }
        return super.onGenericMotionEvent(e)
    }
    override fun performClick():Boolean{super.performClick();return true}
}
