package com.example.rotaryisolator

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.content.res.ColorStateList
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
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
    private var uiScale = 1.0f
    private var skin = 0
    private var limiterOn = true
    private var eqOn = true
    private var isoOn = true
    private var filtersOn = false
    private var gain = 0f
    private var resonance = 0f
    private var verticalLayout = false
    private var phonePortrait = false
    private var masterMode = 0
    private var bassBoost = 0f
    private var loudness = 0f
    private var bassBoostOn = false
    private var loudnessOn = false
    private var previousBassBoost = 0f
    private var previousLoudness = 0f
    private val sectionPreviousEq = FloatArray(3)
    private val sectionPreviousIso = FloatArray(3)
    private val sectionPreviousFilters = FloatArray(3)
    private val previousEq = FloatArray(3)
    private val previousIso = FloatArray(3)
    private val previousFilters = FloatArray(3)
    private val eq = FloatArray(3)
    private val iso = floatArrayOf(0f, 0f, 0f)
    // HPF, LPF intensity (0..100%)
    private val filters = floatArrayOf(0f, 0f)
    private val eqControlOn = BooleanArray(3) { true }
    private val isoControlOn = BooleanArray(3) { true }
    private val filterControlOn = BooleanArray(2) { true }
    private val handler = Handler(Looper.getMainLooper())
    private val dspUpdate = Runnable { applyDspNow() }
    private val saveUpdate = Runnable { save() }
    private val modes = mutableMapOf("EQ" to false, "ISOLATOR" to false, "FILTERS" to false, "GAIN" to false)
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var container: FrameLayout
    private lateinit var status: TextView
    private val sections = mutableMapOf<String, LinearLayout>()
    private val gold get() = when(skin) { 1 -> Color.rgb(100,190,210); 2 -> Color.rgb(220,130,80); else -> Color.rgb(214,179,106) }
    private val bg get() = when(skin) { 1 -> Color.rgb(12,24,31); 2 -> Color.rgb(28,18,15); else -> Color.rgb(17,19,23) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        compact = prefs.getBoolean("mini", false)
        verticalLayout = prefs.getBoolean("vertical_layout", false)
        masterMode = prefs.getInt("master_mode", 0).coerceIn(0,1)
        bassBoost = prefs.getFloat("bass_boost", 0f).coerceIn(0f,100f)
        loudness = prefs.getFloat("loudness", 0f).coerceIn(0f,100f)
        bassBoostOn = prefs.getBoolean("bass_boost_on", false)
        loudnessOn = prefs.getBoolean("loudness_on", false)
        previousBassBoost = prefs.getFloat("previous_bass_boost", bassBoost)
        previousLoudness = prefs.getFloat("previous_loudness", loudness)
        uiScale = prefs.getFloat("ui_scale",1.0f).coerceIn(0.70f,1.30f)
        val dm = resources.displayMetrics
        phonePortrait = (dm.widthPixels / dm.density) < 600f && dm.heightPixels > dm.widthPixels
        skin = prefs.getInt("skin", 0).coerceIn(0,2)
        val limiterDefaultApplied = prefs.getBoolean("limiter_default_v1", false)
        limiterOn = if (limiterDefaultApplied) prefs.getBoolean("limiter", true) else true
        if (!limiterDefaultApplied) prefs.edit().putBoolean("limiter_default_v1", true).putBoolean("limiter", true).apply()
        eqOn = prefs.getBoolean("eq_on", true); isoOn = prefs.getBoolean("iso_on", true); filtersOn = prefs.getBoolean("filters_on", false)
        gain = prefs.getFloat("gain", 0f).coerceIn(-12f,12f)
        for (i in 0..2) { eq[i] = prefs.getFloat("eq_$i",0f); iso[i] = prefs.getFloat("iso_$i",0f) }
        filters[0] = prefs.getFloat("hpf",0f); filters[1] = prefs.getFloat("lpf",0f)
        for(i in 0..2){previousEq[i]=eq[i];previousIso[i]=iso[i];sectionPreviousEq[i]=eq[i];sectionPreviousIso[i]=iso[i]}
        previousFilters[0]=filters[0];previousFilters[1]=filters[1];previousFilters[2]=resonance
        sectionPreviousFilters[0]=filters[0];sectionPreviousFilters[1]=filters[1];sectionPreviousFilters[2]=resonance
        resonance = prefs.getFloat("resonance",0f).coerceIn(0f,1f)
        for (i in 0..2) {
            eqControlOn[i] = prefs.getBoolean("eq_control_$i", true)
            isoControlOn[i] = prefs.getBoolean("iso_control_$i", true)
        }
        for (i in 0..1) filterControlOn[i] = prefs.getBoolean("filter_control_$i", true)
        for (key in modes.keys) modes[key] = prefs.getBoolean("mode_$key", false)
        val filterRotaryDefaultApplied = prefs.getBoolean("filter_rotary_default_v1", false)
        if (!filterRotaryDefaultApplied) {
            modes["FILTERS"] = false
            prefs.edit().putBoolean("mode_FILTERS", false).putBoolean("filter_rotary_default_v1", true).apply()
        }
        buildUi()
        connectAudio()
    }

    private fun dp(v:Int)= (v*resources.displayMetrics.density).roundToInt()
    private fun dialHeight():Int { val h=resources.displayMetrics.heightPixels/resources.displayMetrics.density; return dp(when { h<700f -> 66; h<820f -> 74; else -> 82 }) }

    // Recalculate after both the window and the complete mixer have been measured.
    // Do not scale the whole mixer: it clips text and controls. Scroll the center instead.
    private fun fitUiToWindow() {
        if (!::root.isInitialized) return
        root.scaleX = 1f
        root.scaleY = 1f
        root.translationX = 0f
        root.translationY = 0f
    }

    private fun label(t:String, size:Float=11f, color:Int=Color.LTGRAY)=TextView(this).apply {
        text=t; textSize=size; setTextColor(color); gravity=Gravity.CENTER; letterSpacing=.08f
    }
    private fun button(t:String, action:()->Unit)=Button(this).apply { text=t; setOnClickListener { action() } }
    private fun redButton(t:String, active:Boolean=true, action:()->Unit)=Button(this).apply { text=t; textSize=10f; setTextColor(Color.WHITE); backgroundTintList=ColorStateList.valueOf(if(active) Color.rgb(190,35,42) else Color.rgb(105,35,40)); setOnClickListener { action() } }
    private fun phoneActionButton(textValue:String, action:()->Unit):View = TextView(this).apply{
        text=textValue;textSize=9f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;isAllCaps=true
        background=android.graphics.drawable.GradientDrawable().apply{cornerRadius=dp(6).toFloat();setColor(Color.rgb(190,28,38));setStroke(dp(1),Color.rgb(245,90,90))}
        setOnClickListener{action()}
    }

    private fun roundAction(symbol:String, caption:String, active:Boolean=true, action:()->Unit):View {
        return LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            val dot=TextView(this@MainActivity).apply {
                text=symbol;gravity=Gravity.CENTER;textSize=if(symbol=="F")10f else 13f
                setTextColor(Color.WHITE)
                background=android.graphics.drawable.GradientDrawable().apply {
                    shape=android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if(active)Color.rgb(205,35,43) else Color.rgb(75,43,46))
                    setStroke(dp(1),if(active)Color.rgb(245,95,95) else Color.rgb(130,70,70))
                }
                setOnClickListener { action() }
            }
            addView(dot,LinearLayout.LayoutParams(dp(26),dp(24)))
            val txt=TextView(this@MainActivity).apply {
                text=caption;textSize=9f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(5),0,dp(3),0);setOnClickListener { action() }
            }
            addView(txt,LinearLayout.LayoutParams(-2,dp(24)))
        }
    }
    private fun save() {
        prefs.edit().putBoolean("mini",compact).putBoolean("vertical_layout",verticalLayout).putInt("master_mode",masterMode).putFloat("bass_boost",bassBoost).putFloat("loudness",loudness).putBoolean("bass_boost_on",bassBoostOn).putBoolean("loudness_on",loudnessOn).putFloat("previous_bass_boost",previousBassBoost).putFloat("previous_loudness",previousLoudness).putInt("skin",skin).putBoolean("limiter",limiterOn)
            .putBoolean("eq_on",eqOn).putBoolean("iso_on",isoOn).putBoolean("filters_on",filtersOn).putFloat("gain",gain).putFloat("ui_scale",uiScale)
            .apply {
                for(i in 0..2){putFloat("eq_$i",eq[i]);putFloat("iso_$i",iso[i])}
                putFloat("hpf",filters[0]);putFloat("lpf",filters[1]);putFloat("resonance",resonance)
                for(i in 0..2){putBoolean("eq_control_$i",eqControlOn[i]);putBoolean("iso_control_$i",isoControlOn[i])}
                for(i in 0..1)putBoolean("filter_control_$i",filterControlOn[i])
                modes.forEach{(k,v)->putBoolean("mode_$k",v)}
            }.apply()
    }

    private fun buildUi() {
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(if(phonePortrait)3 else 8),dp(if(phonePortrait)1 else 4),dp(if(phonePortrait)3 else 8),dp(if(phonePortrait)1 else 4));setBackgroundColor(bg);clipChildren=false;clipToPadding=false}
        val titleBar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        titleBar.addView(label("ROTARY ISOLATOR", if(phonePortrait)15f else if(compact) 16f else 20f,Color.WHITE),LinearLayout.LayoutParams(0,dp(if(phonePortrait)28 else 32),1f))
        titleBar.addView(TextView(this).apply { text="⚙"; textSize=23f; gravity=Gravity.CENTER; setTextColor(gold); contentDescription="Impostazioni"; setOnClickListener { showOptions() } },LinearLayout.LayoutParams(dp(46),dp(if(phonePortrait)30 else 36)))
        root.addView(titleBar)
        root.addView(label("MASTER PROCESSING  •  V10 / XONE CONCEPT",if(phonePortrait)7f else 8f,gold),LinearLayout.LayoutParams(-1,dp(if(phonePortrait)12 else 17)))
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false}
        // Tutte le sezioni restano nella stessa schermata: nessuno scrolling.
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        sections.clear()
        addSection("EQ","EQUALIZER  •  LOW / MID / HIGH",eq, -12f..12f, {eqOn}) { scheduleUpdate() }
        addSection("ISOLATOR","ISOLATOR  •  LOW / MID / HIGH",iso, -24f..24f, {isoOn}) { scheduleUpdate() }
        addFilterSection()
        addGainSection()
        status=label("Connecting audio engine…",if(phonePortrait)7f else 9f)
        root.addView(status,LinearLayout.LayoutParams(-1,dp(if(phonePortrait)13 else 18)))
        container=FrameLayout(this).apply{clipChildren=true;clipToPadding=true}
        container.addView(root,FrameLayout.LayoutParams(-1,-1,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(container)
        container.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> fitUiToWindow() }
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> fitUiToWindow() }
        container.post { root.post { fitUiToWindow() } }
    }

    private fun scheduleUpdate() {
        // Update DSP at display cadence; persist settings off the hot interaction path.
        handler.removeCallbacks(dspUpdate)
        handler.post(dspUpdate)
        handler.removeCallbacks(saveUpdate)
        handler.postDelayed(saveUpdate, 220L)
    }

    private fun addSection(key:String,title:String,values:FloatArray,range:ClosedFloatingPointRange<Float>,enabled:()->Boolean,onChange:()->Unit) {
        val active = if(key=="EQ") eqControlOn else isoControlOn
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(if(phonePortrait)1 else 4),dp(if(phonePortrait)1 else 4),dp(if(phonePortrait)1 else 4),dp(1))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        header.addView(label(title,if(phonePortrait)9f else 10f,gold),LinearLayout.LayoutParams(0,dp(20),1f))
        box.addView(header)
        val useVerticalRows=false
        val row=LinearLayout(this).apply{orientation=if(useVerticalRows)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=if(useVerticalRows)0f else 3f}
        val names=listOf("LOW","MID","HIGH")
        for(i in 0..2){
            val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val vLabel=label("${names[i]}  ${fmt(values[i])} dB", if(compact)10f else 12f,Color.WHITE)
            val controlHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            controlHeader.addView(vLabel,LinearLayout.LayoutParams(0,dp(if(phonePortrait)19 else 28),1f))
            col.addView(controlHeader,LinearLayout.LayoutParams(-1,dp(24)))
            if(modes[key]==true) {
                val seek=SeekBar(this).apply{
                    max=(range.endInclusive-range.start).roundToInt()*10
                    progress=((values[i]-range.start)*10).roundToInt().coerceIn(0,max)
                    isEnabled=active[i]
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user){values[i]=range.start+p/10f;vLabel.text="${names[i]}  ${fmt(values[i])} dB";scheduleUpdate()}}
                        override fun onStartTrackingTouch(s:SeekBar?){if(key=="EQ")previousEq[i]=values[i] else previousIso[i]=values[i]}
                        override fun onStopTrackingTouch(s:SeekBar?){scheduleUpdate()}
                    })
                }
                col.addView(seek,LinearLayout.LayoutParams(-1,dp(48)))
            } else {
                val knob=RotaryControl(this,names[i],values[i],range.start,range.endInclusive,gold,{v->values[i]=v;vLabel.text="${names[i]}  ${fmt(v)} dB";scheduleUpdate()},{fmt(values[i])+" dB"},onStart={if(key=="EQ")previousEq[i]=values[i] else previousIso[i]=values[i]})
                knob.isEnabled=active[i]
                col.addView(knob,LinearLayout.LayoutParams(-1,dialHeight()))
            }
            val bandActions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(dp(2),0,dp(2),0) }
            bandActions.addView(phoneActionButton("RETURN") { val old=if(key=="EQ")previousEq[i] else previousIso[i]; if(key=="EQ")previousEq[i]=values[i] else previousIso[i]=values[i]; values[i]=old; save(); refreshUi(); scheduleUpdate() },LinearLayout.LayoutParams(0,dp(32),1f).apply{rightMargin=dp(3)})
            bandActions.addView(phoneActionButton("FLAT") { if(key=="EQ")previousEq[i]=values[i] else previousIso[i]=values[i]; values[i]=0f; save(); refreshUi(); scheduleUpdate() },LinearLayout.LayoutParams(0,dp(32),1f))
            col.addView(bandActions,LinearLayout.LayoutParams(-1,dp(34)))
            row.addView(col,if(useVerticalRows)LinearLayout.LayoutParams(-1,dp(if(compact)150 else 178)) else LinearLayout.LayoutParams(0,-2,1f))
        }
        box.addView(row)
        val sectionActions=LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER
                setPadding(dp(3),dp(3),dp(3),dp(2))
            }
            sectionActions.addView(phoneActionButton("RETURN"){
                val previous=if(key=="EQ")sectionPreviousEq else sectionPreviousIso
                for(i in 0..2)values[i]=previous[i]
                save();refreshUi();scheduleUpdate()
            },LinearLayout.LayoutParams(0,dp(32),1f).apply{rightMargin=dp(6)})
            sectionActions.addView(phoneActionButton("FLAT"){
                val previous=if(key=="EQ")sectionPreviousEq else sectionPreviousIso
                for(i in 0..2){previous[i]=values[i];values[i]=0f}
                save();refreshUi();scheduleUpdate()
            },LinearLayout.LayoutParams(0,dp(32),1f))
        box.addView(sectionActions,LinearLayout.LayoutParams(-1,dp(34)))
        content.addView(box);sections[key]=box
    }

    private fun addFilterSection(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(if(phonePortrait)1 else 4),dp(1),dp(if(phonePortrait)1 else 4),dp(1))}
        val filterTitle=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        filterTitle.addView(label("FILTERS  •  HPF / LPF",if(phonePortrait)9f else 10f,gold),LinearLayout.LayoutParams(0,dp(20),1f))
        box.addView(filterTitle)
        val useVerticalRows=false
        val row=LinearLayout(this).apply{orientation=if(useVerticalRows)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=if(useVerticalRows)0f else 3f}
        val names=listOf("HPF","LPF","RESONANCE")
        for(i in 0..2){
            val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val current=if(i==2) resonance else filters[i]
            val l=label(names[i]+"  "+(current*100).roundToInt()+"%",10f,Color.WHITE)
            val controlEnabled=if(i==2) true else filterControlOn[i]
            val filterHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            filterHeader.addView(l,LinearLayout.LayoutParams(0,dp(24),1f))
            if(i<2) filterHeader.addView(roundAction("",if(controlEnabled)"ON" else "OFF",controlEnabled){
                filterControlOn[i]=!filterControlOn[i];save();refreshUi();scheduleUpdate()
            })
            col.addView(filterHeader,LinearLayout.LayoutParams(-1,dp(24)))
            if(modes["FILTERS"]==true){
                val seek=SeekBar(this).apply{
                    max=100;progress=(current*100).roundToInt();isEnabled=controlEnabled
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){if(user){if(i==2)resonance=p/100f else filters[i]=p/100f;l.text=names[i]+"  "+p+"%";scheduleUpdate()}}
                        override fun onStartTrackingTouch(b:SeekBar?){previousFilters[i]=if(i==2)resonance else filters[i]}
                        override fun onStopTrackingTouch(b:SeekBar?){scheduleUpdate()}
                    })
                }
                col.addView(seek,LinearLayout.LayoutParams(-1,dp(48)))
            }else{
                val knob=RotaryControl(this,names[i],current,0f,1f,gold,{v->
                    if(i==2)resonance=v else filters[i]=v
                    l.text=names[i]+"  "+(v*100).roundToInt()+"%"
                    scheduleUpdate()
                },{((if(i==2)resonance else filters[i])*100).roundToInt().toString()+"%"},onStart={previousFilters[i]=if(i==2)resonance else filters[i]})
                knob.isEnabled=controlEnabled
                col.addView(knob,LinearLayout.LayoutParams(-1,dialHeight()))
            }
            val filterActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            filterActions.addView(phoneActionButton("RETURN"){ val old=previousFilters[i]; previousFilters[i]=if(i==2)resonance else filters[i]; if(i==2)resonance=old else filters[i]=old; save();refreshUi();scheduleUpdate() },LinearLayout.LayoutParams(0,dp(32),1f).apply{rightMargin=dp(3)})
            filterActions.addView(phoneActionButton("FLAT"){ previousFilters[i]=if(i==2)resonance else filters[i]; if(i==2)resonance=0f else filters[i]=0f; save();refreshUi();scheduleUpdate() },LinearLayout.LayoutParams(0,dp(32),1f))
            col.addView(filterActions,LinearLayout.LayoutParams(-1,dp(34)))
            row.addView(col,if(useVerticalRows)LinearLayout.LayoutParams(-1,dp(if(compact)150 else 178)) else LinearLayout.LayoutParams(0,-2,1f))
        }
        box.addView(row);content.addView(box);sections["FILTERS"]=box
    }

    private fun addGainSection(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(if(phonePortrait)1 else 4),dp(if(phonePortrait)1 else 3),dp(if(phonePortrait)1 else 4),dp(2))}
        box.addView(label("MASTER  •  GAIN / BASS BOOST / LOUDNESS",if(phonePortrait)9f else 10f,gold),LinearLayout.LayoutParams(-1,dp(20)))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=3f}
        val gainCol=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
        gainCol.addView(label("GAIN",if(phonePortrait)9f else 11f,Color.WHITE),LinearLayout.LayoutParams(-1,dp(18)))
        if(modes["GAIN"]==true){
            val s=SeekBar(this).apply{max=240;progress=((gain+12)*10).roundToInt();setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){if(user){gain=p/10f-12f;scheduleUpdate()}}
                override fun onStartTrackingTouch(b:SeekBar?){}
                override fun onStopTrackingTouch(b:SeekBar?){}
            })}
            gainCol.addView(s,LinearLayout.LayoutParams(-1,dp(48)))
        }else{
            gainCol.addView(RotaryControl(this,"GAIN",gain,-12f,12f,gold,{v->gain=v;scheduleUpdate()},{"${fmt(gain)} dB"}),LinearLayout.LayoutParams(-1,dialHeight()))
        }
        row.addView(gainCol,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(masterToneControl("BASS BOOST",bassBoost,bassBoostOn,{bassBoost=it},{bassBoostOn=it},{previousBassBoost=bassBoost},{bassBoost=previousBassBoost}),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(masterToneControl("LOUDNESS",loudness,loudnessOn,{loudness=it},{loudnessOn=it},{previousLoudness=loudness},{loudness=previousLoudness}),LinearLayout.LayoutParams(0,-2,1f))
        box.addView(row)
        content.addView(box);sections["GAIN"]=box
    }

    private fun masterToneControl(
        title:String,value:Float,enabled:Boolean,setValue:(Float)->Unit,setEnabled:(Boolean)->Unit,
        remember:()->Unit,restore:()->Unit
    ):View{
        val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
        col.addView(label("${value.roundToInt()}%",if(phonePortrait)9f else 10f,Color.WHITE),LinearLayout.LayoutParams(-1,dp(16)))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        actions.addView(roundAction("",if(enabled)"ON" else "OFF",enabled){
            setEnabled(!enabled);save();refreshUi();scheduleUpdate()
        })
        actions.addView(roundAction("↶","RETURN",true){
            restore();save();refreshUi();scheduleUpdate()
        })
        col.addView(actions,LinearLayout.LayoutParams(-1,dp(24)))
        col.addView(RotaryControl(this,title,value,0f,100f,gold,{v->setValue(v);scheduleUpdate()},{if(title=="BASS BOOST")"${bassBoost.roundToInt()}%" else "${loudness.roundToInt()}%"},onStart=remember),
            LinearLayout.LayoutParams(-1,dialHeight()))
        return col
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
                if(filtersOn && filterControlOn[0] && i==0) eqGain -= filters[0]*36f
                if(filtersOn && filterControlOn[1] && i==2) eqGain -= filters[1]*36f
                if(filtersOn && resonance>0f){
                    if(filterControlOn[0] && filters[0]>.01f && i==0) eqGain += resonance*12f
                    if(filterControlOn[1] && filters[1]>.01f && i==2) eqGain += resonance*6f
                }
                val eqBand=DynamicsProcessing.EqBand(true,freqs[i],eqGain.coerceIn(-36f,12f))
                fx.setPreEqBandByChannelIndex(ch,i,eqBand)
                val isoGain=if(isoOn && isoControlOn[i])iso[i].coerceIn(-24f,24f) else 0f
                fx.setPostEqBandByChannelIndex(ch,i,DynamicsProcessing.EqBand(true,freqs[i],isoGain))
            }
            // More pronounced, rounded tone curves. Bass boost adds weight and punch;
            // loudness adds bass body plus a restrained upper presence lift.
            val masterLow=(if(bassBoostOn)bassBoost/100f*15f else 0f)+(if(loudnessOn)loudness/100f*12f else 0f)
            val masterHigh=if(loudnessOn)loudness/100f*8f else 0f
            for(ch in 0..1){
                val low=if(isoOn && isoControlOn[0])iso[0].coerceIn(-24f,24f) else 0f
                val mid=if(isoOn && isoControlOn[1])iso[1].coerceIn(-24f,24f) else 0f
                val high=if(isoOn && isoControlOn[2])iso[2].coerceIn(-24f,24f) else 0f
                fx.setPostEqBandByChannelIndex(ch,0,DynamicsProcessing.EqBand(true,250f,low+masterLow))
                fx.setPostEqBandByChannelIndex(ch,1,DynamicsProcessing.EqBand(true,4000f,mid))
                fx.setPostEqBandByChannelIndex(ch,2,DynamicsProcessing.EqBand(true,20000f,high+masterHigh))
            }
            fx.setInputGainAllChannelsTo(gain.coerceIn(-12f,12f))
            if(limiterOn){
                val lim=DynamicsProcessing.Limiter(true,true,0,5f,80f,20f,-1f,0f)
                fx.setLimiterAllChannelsTo(lim)
            }
            fx.enabled=eqOn||isoOn||filtersOn||abs(gain)>.01f||limiterOn||bassBoostOn||loudnessOn
            status.text=if(fx.enabled)"Processing ON • EQ / ISO / Gain" else "BYPASS"
        }catch(t:Throwable){status.text="Audio DSP non supportato dal driver del dispositivo"}
    }

    private fun showOptions(){
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(4))}
        val names=listOf("Controlli EQ","Controlli Isolator","Controlli Filtri","Controlli Gain","Interfaccia MINI / EXTENDED","Tema Pioneer V10","Tema Xone","Tema Rotativo classico")
        names.forEachIndexed{index,name->
            val b=Button(this).apply{text=name;textSize=13f;setOnClickListener{
                when(index){
                    in 0..3->{
                        val key=listOf("EQ","ISOLATOR","FILTERS","GAIN")[index]
                        val opts=arrayOf("Manopole rotative","Slider")
                        AlertDialog.Builder(this@MainActivity).setTitle(key+" • tipo controllo").setSingleChoiceItems(opts,if(modes[key]==true)1 else 0){a,c->modes[key]=c==1;save();refreshUi();applyDsp();a.dismiss()}.setNegativeButton("Annulla",null).show()
                    }
                    4->{compact=!compact;save();refreshUi();applyDsp()}
                    in 5..7->{skin=index-5;save();refreshUi();applyDsp()}
                }
            }}
            panel.addView(b,LinearLayout.LayoutParams(-1,dp(44)))
        }
        val scaleLabel=label("SCALA INTERFACCIA  ${(uiScale*100).roundToInt()}%",12f,Color.WHITE)
        panel.addView(scaleLabel,LinearLayout.LayoutParams(-1,dp(24)))
        val scaleSeek=SeekBar(this).apply{
            max=60;progress=((uiScale-.70f)*100).roundToInt().coerceIn(0,60)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){if(user){uiScale=.70f+p/100f;fitUiToWindow();scaleLabel.text="SCALA INTERFACCIA  ${(uiScale*100).roundToInt()}%"}}
                override fun onStartTrackingTouch(b:SeekBar?){}
                override fun onStopTrackingTouch(b:SeekBar?){save()}
            })
        }
        panel.addView(scaleSeek,LinearLayout.LayoutParams(-1,dp(44)))
        val limiterRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(4))}
        limiterRow.addView(label("LIMITER",14f,Color.WHITE),LinearLayout.LayoutParams(0,dp(48),1f))
        val led=View(this).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(if(limiterOn)Color.rgb(40,220,90) else Color.rgb(75,75,75))}}
        limiterRow.addView(led,LinearLayout.LayoutParams(dp(14),dp(14)).apply{rightMargin=dp(12)})
        val toggle=Switch(this).apply{
            text=if(limiterOn)"ON" else "OFF";isChecked=limiterOn
            setOnCheckedChangeListener{_,checked->
                limiterOn=checked;text=if(checked)"ON" else "OFF"
                (led.background as? android.graphics.drawable.GradientDrawable)?.setColor(if(checked)Color.rgb(40,220,90) else Color.rgb(75,75,75))
                save();rebuildEffect()
            }
        }
        limiterRow.addView(toggle);panel.addView(limiterRow)
        AlertDialog.Builder(this).setTitle("OPTIONS").setView(ScrollView(this).apply{addView(panel)}).setNegativeButton("CHIUDI",null).show()
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
    private val valueText:()->String,
    private val onStart:()->Unit = {},
    private val onEnd:()->Unit = {}
):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var v=initial
    private var downY=0f
    private var downV=initial
    private var dragged=false
    private val density get()=resources.displayMetrics.density
    private fun dp(x:Float)=x*density
    private val rect get()={
        val side=min(width.toFloat(),(height.toFloat()-dp(22f)).coerceAtLeast(1f))* .82f
        val cy=(height-dp(26f))/2f
        RectF(width/2f-side/2,cy-side/2,width/2f+side/2,cy+side/2)
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
        c.drawText(title,cx,height-dp(17f),p)
        p.color=accent;p.textSize=dp(10f);c.drawText(valueText(),cx,height-dp(4f),p)
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
                onStart()
                downY=e.y;downV=v;dragged=false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE->{
                val delta=downY-e.y
                if(abs(delta)>dp(4f)) dragged=true
                if(dragged){val sensitivity=(maxV-minV)*2.2f/height.coerceAtLeast(1);v=(downV+delta*sensitivity).coerceIn(minV,maxV);changed(v);invalidate()}
                return true
            }
            MotionEvent.ACTION_UP->{if(!dragged)updateFromPoint(e.x,e.y);onEnd();performClick();return true}
            MotionEvent.ACTION_CANCEL->{onEnd();return true}
        }
        return true
    }
    override fun onGenericMotionEvent(e:MotionEvent):Boolean{
        if(e.action==MotionEvent.ACTION_SCROLL){
            onStart()
            v=(v+e.getAxisValue(MotionEvent.AXIS_VSCROLL)*(maxV-minV)*.012f).coerceIn(minV,maxV)
            changed(v);invalidate();onEnd();return true
        }
        return super.onGenericMotionEvent(e)
    }
    override fun performClick():Boolean{super.performClick();return true}
}
