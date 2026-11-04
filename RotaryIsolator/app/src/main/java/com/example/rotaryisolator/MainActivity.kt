package com.example.rotaryisolator

import android.app.Activity
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import android.os.IBinder
import android.content.ServiceConnection
import android.content.ComponentName
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.EnvironmentalReverb
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.view.MotionEvent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("rotary_options_v3", MODE_PRIVATE) }
    private var dpEffect: DynamicsProcessing? = null
    private var reverbEffect: EnvironmentalReverb? = null
    private var reverbOn = false
    private var reverbMix = 28
    private var reverbDecay = 1800
    private var reverbSize = 650
    private var reverbDamping = 550
    private var insertReverbTest: android.media.audiofx.AudioEffect? = null
    private var captureProjection: android.media.projection.MediaProjection? = null
    private var captureRecord: android.media.AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var captureRunning = false
    private var captureDialog: AlertDialog? = null
    private var captureStatusView: TextView? = null
    private val captureRequestCode = 7420
    private val capturePermissionRequestCode = 7421
    private var shizukuReverbService: IShizukuReverbService? = null
    private val shizukuRequestCode = 7419
    private val shizukuUserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ShizukuReverbUserService::class.java.name))
            .daemon(false).processNameSuffix("frequalizer_reverb").version(1)
    }
    private val shizukuServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuReverbService = IShizukuReverbService.Stub.asInterface(service)
            try {
                val result = shizukuReverbService?.startReverb(reverbMix, reverbDecay, reverbSize, reverbDamping)
                    ?: "ERRORE: servizio Shizuku senza binder"
                showShizukuReverbResult(result)
            } catch (t: Throwable) {
                showShizukuReverbResult("ERRORE CHIAMATA SERVIZIO SHIZUKU\\n" + android.util.Log.getStackTraceString(t))
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuReverbService = null
        }
    }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == shizukuRequestCode) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) bindShizukuReverbService()
            else showShizukuReverbResult("Autorizzazione Shizuku negata. Concedi FREQUALIZER TEST nell'app Shizuku e riprova.")
        }
    }
    private var audioReady = false
    private var appEnabled = true
    private var defaultSettingsOnStart = true
    private var compact = false
    private var uiScale = 1.0f
    private var colorEq = Color.rgb(70,190,120)
    private var colorIso = Color.rgb(70,190,120)
    private var colorFilters = Color.rgb(70,190,120)
    private var colorMaster = Color.rgb(70,190,120)
    private var colorButtons = Color.rgb(255,205,0)
    private var colorBackground = Color.rgb(17,19,23)
    private var colorTitles = Color.rgb(220,70,75)
    private var colorOptions = Color.rgb(255,205,0)
    private var limiterOn = true
    private var mouseWheelAdjustOn = false
    private var mouseWheelSensitivity = 4f
    private var compressorOn = false
    private var compThreshold = -18f
    private var compRatio = 3f
    private var compAttack = 10f
    private var compRelease = 150f
    private var compMakeup = 0f
    private var eqOn = true
    private var isoOn = true
    private var filtersOn = false
    private var gain = 0f
    private var resonance = 0f
    private var phonePortrait = false
    private var wideLayout = false
    private var layoutRebuildPending = false
    private var lastWindowWidthPx = 0
    private var lastWindowHeightPx = 0
    private var masterMode = 0
    private var bassBoost = 0f
    private var loudness = 0f
    private var bassBoostOn = false
    private var loudnessOn = false
    private var previousBassBoost = 0f
    private var previousLoudness = 0f
    private val sectionPreviousEq = FloatArray(4)
    private val sectionPreviousIso = FloatArray(3)
    private val sectionPreviousFilters = FloatArray(3)
    private val previousEq = FloatArray(4)
    private val previousIso = FloatArray(3)
    private val previousFilters = FloatArray(3)
    private val eq = FloatArray(4)
    private val iso = floatArrayOf(0f, 0f, 0f)
    // HPF intensity (0..100%)
    private val filters = floatArrayOf(0f, 0f)
    private val eqControlOn = BooleanArray(4) { true }
    private var eqDoubleMid = false
    private val isoControlOn = BooleanArray(3) { true }
    private val filterControlOn = BooleanArray(2) { true }
    private val handler = Handler(Looper.getMainLooper())
    private val dspThread = HandlerThread("Frequalizer-DSP", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val dspHandler = Handler(dspThread.looper)
    private val dspUpdate = Runnable { applyDspNow() }
    private val saveUpdate = Runnable { save() }
    private val modes = mutableMapOf("EQ" to false, "ISOLATOR" to false, "FILTERS" to false, "GAIN" to false)
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var container: FrameLayout
    private lateinit var status: TextView
    private val sections = mutableMapOf<String, LinearLayout>()
    private val gold get() = colorEq
    private val bg get() = colorBackground
    private fun accentFor(key:String) = when(key) { "EQ" -> colorEq; "ISOLATOR" -> colorIso; "FILTERS" -> colorFilters; else -> colorMaster }
    private fun titleTint(default:Int) = if(colorTitles == Color.TRANSPARENT) default else colorTitles

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        // Explicitly follow the physical sensor in both portrait and landscape.
        // UNSPECIFIED can remain in the user's preferred orientation on some tablets.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        appEnabled = prefs.getBoolean("app_enabled", true)
        reverbOn = prefs.getBoolean("reverb_on", false)
        reverbMix = prefs.getInt("reverb_mix", 28).coerceIn(0,100)
        reverbDecay = prefs.getInt("reverb_decay", 1800).coerceIn(100,8000)
        reverbSize = prefs.getInt("reverb_size", 650).coerceIn(0,1000)
        reverbDamping = prefs.getInt("reverb_damping", 550).coerceIn(100,1000)
        defaultSettingsOnStart = prefs.getBoolean("default_settings_on_start", true)
        compact = false
        masterMode = prefs.getInt("master_mode", 0).coerceIn(0,1)
        bassBoost = prefs.getFloat("bass_boost", 0f).coerceIn(0f,100f)
        loudness = prefs.getFloat("loudness", 0f).coerceIn(0f,100f)
        bassBoostOn = prefs.getBoolean("bass_boost_on", false)
        loudnessOn = prefs.getBoolean("loudness_on", false)
        previousBassBoost = prefs.getFloat("previous_bass_boost", 0f).coerceIn(0f,100f)
        previousLoudness = prefs.getFloat("previous_loudness", 0f).coerceIn(0f,100f)
        uiScale = prefs.getFloat("ui_scale",1.0f).coerceIn(0.70f,1.30f)
        val dm = resources.displayMetrics
        val cfg = resources.configuration
        val windowWidthDp = if (cfg.screenWidthDp > 0) cfg.screenWidthDp else (dm.widthPixels / dm.density).roundToInt()
        val windowHeightDp = if (cfg.screenHeightDp > 0) cfg.screenHeightDp else (dm.heightPixels / dm.density).roundToInt()
        phonePortrait = min(windowWidthDp, windowHeightDp) < 600
        wideLayout = windowWidthDp > windowHeightDp
        colorEq = prefs.getInt("color_eq", Color.rgb(70,190,120))
        colorIso = prefs.getInt("color_iso", Color.rgb(70,190,120))
        colorFilters = prefs.getInt("color_filters", Color.rgb(70,190,120))
        colorMaster = prefs.getInt("color_master", Color.rgb(70,190,120))
        colorButtons = prefs.getInt("color_buttons", Color.rgb(255,205,0))
        colorBackground = prefs.getInt("color_background", Color.rgb(17,19,23))
        colorTitles = prefs.getInt("color_titles", Color.rgb(220,70,75))
        colorOptions = prefs.getInt("color_options", Color.rgb(255,205,0))
        val colorDefaultsApplied = prefs.getBoolean("color_defaults_v3", false)
        if (!colorDefaultsApplied) {
            colorEq = Color.rgb(70,190,120)
            colorIso = Color.rgb(70,190,120)
            colorFilters = Color.rgb(70,190,120)
            colorMaster = Color.rgb(70,190,120)
            colorTitles = Color.rgb(220,70,75)
            colorOptions = Color.rgb(255,205,0)
            prefs.edit().putInt("color_eq", colorEq).putInt("color_iso", colorIso)
                .putInt("color_filters", colorFilters).putInt("color_master", colorMaster)
                .putInt("color_titles", colorTitles).putInt("color_options", colorOptions)
                .putBoolean("color_defaults_v3", true).apply()
        }
        val mouseDefaultsApplied = prefs.getBoolean("mouse_defaults_v1", false)
        mouseWheelAdjustOn = if (mouseDefaultsApplied) prefs.getBoolean("mouse_wheel_adjust_on", true) else true
        mouseWheelSensitivity = if (mouseDefaultsApplied) prefs.getFloat("mouse_wheel_sensitivity", 5f).coerceIn(1f,10f) else 5f
        if (!mouseDefaultsApplied) prefs.edit().putBoolean("mouse_defaults_v1", true).putBoolean("mouse_wheel_adjust_on", true).putFloat("mouse_wheel_sensitivity", 5f).putBoolean("mini", false).apply()
        val limiterDefaultApplied = prefs.getBoolean("limiter_default_v1", false)
        limiterOn = if (limiterDefaultApplied) prefs.getBoolean("limiter", true) else true
        compressorOn = prefs.getBoolean("compressor_on", false)
        compThreshold = prefs.getFloat("comp_threshold", -18f).coerceIn(-36f, 0f)
        compRatio = prefs.getFloat("comp_ratio", 3f).coerceIn(1f, 12f)
        compAttack = prefs.getFloat("comp_attack", 10f).coerceIn(1f, 100f)
        compRelease = prefs.getFloat("comp_release", 150f).coerceIn(20f, 500f)
        compMakeup = prefs.getFloat("comp_makeup", 0f).coerceIn(0f,100f)
        if (!limiterDefaultApplied) prefs.edit().putBoolean("limiter_default_v1", true).putBoolean("limiter", true).apply()
        eqOn = prefs.getBoolean("eq_on", true); isoOn = prefs.getBoolean("iso_on", true)
        val doubleMidDefaultApplied = prefs.getBoolean("double_mid_default_v1", false)
        eqDoubleMid = if (doubleMidDefaultApplied) prefs.getBoolean("eq_double_mid", true) else true
        if (!doubleMidDefaultApplied) prefs.edit().putBoolean("double_mid_default_v1", true).putBoolean("eq_double_mid", true).apply()
        // Keep the filter section enabled at every app start, regardless of
        // an OFF value saved by an older build/session.
        filtersOn = prefs.getBoolean("filters_on", true)
        filterControlOn[0] = prefs.getBoolean("filter_control_0", true)
        filterControlOn[1] = prefs.getBoolean("filter_control_1", true)
        gain = prefs.getFloat("gain", 0f).coerceIn(-12f,12f)
        for (i in 0..2) {
            eq[i] = prefs.getFloat("eq_$i", 0f).coerceIn(-14f,14f)
            iso[i] = prefs.getFloat("iso_$i", 0f).coerceIn(-24f,24f)
        }
        eq[3] = prefs.getFloat("eq_3", 0f).coerceIn(-14f,14f)
        filters[0] = prefs.getFloat("hpf", 0f).coerceIn(0f,1f); filters[1] = 0f
        resonance = prefs.getFloat("resonance", 0f).coerceIn(0f,1f)
        for(i in 0..3){previousEq[i]=eq[i];sectionPreviousEq[i]=eq[i]}
        for(i in 0..2){previousIso[i]=iso[i];sectionPreviousIso[i]=iso[i]}
        previousFilters[0]=filters[0];previousFilters[1]=filters[1];previousFilters[2]=resonance
        sectionPreviousFilters[0]=filters[0];sectionPreviousFilters[1]=filters[1];sectionPreviousFilters[2]=resonance
        for (i in 0..3) eqControlOn[i] = prefs.getBoolean("eq_control_$i", true)
        for (i in 0..2) isoControlOn[i] = prefs.getBoolean("iso_control_$i", true)
        for (i in 0..1) filterControlOn[i] = prefs.getBoolean("filter_control_$i", true)
        for (key in modes.keys) modes[key] = prefs.getBoolean("mode_$key", false)
        for(i in 0..3){
            previousEq[i]=prefs.getFloat("previous_eq_$i",eq[i])
            sectionPreviousEq[i]=prefs.getFloat("section_previous_eq_$i",eq[i])
        }
        for(i in 0..2){
            previousIso[i]=prefs.getFloat("previous_iso_$i",iso[i])
            sectionPreviousIso[i]=prefs.getFloat("section_previous_iso_$i",iso[i])
        }
        previousFilters[0]=prefs.getFloat("previous_hpf",filters[0])
        previousFilters[1]=0f
        previousFilters[2]=prefs.getFloat("previous_resonance",resonance)
        sectionPreviousFilters[0]=prefs.getFloat("section_previous_hpf",filters[0])
        sectionPreviousFilters[1]=0f
        sectionPreviousFilters[2]=prefs.getFloat("section_previous_resonance",resonance)
        val filterRotaryDefaultApplied = prefs.getBoolean("filter_rotary_default_v1", false)
        if (!filterRotaryDefaultApplied) {
            modes["FILTERS"] = false
            prefs.edit().putBoolean("mode_FILTERS", false).putBoolean("filter_rotary_default_v1", true).apply()
        }
        // Startup defaults apply only to a genuinely new app session. If Android
        // recreates the process after suspending/killing the app in background,
        // restore the last mixer state instead of resetting it to defaults.
        val returningFromBackground = prefs.getBoolean("session_in_background", false)
        if (defaultSettingsOnStart && savedInstanceState == null && !returningFromBackground) applyStartupDefaults()
        prefs.edit().putBoolean("session_in_background", false).apply()
        buildUi()
        connectAudio()
    }

    override fun onPause() {
        // Persist the live mixer and mark the session as backgrounded. If Android
        // later reclaims the process for battery/memory management, the next
        // Activity instance knows to restore the saved controls, not startup defaults.
        save()
        prefs.edit().putBoolean("session_in_background", true).apply()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Android recreates this Activity on rotation. Persist the live mixer
        // values before teardown so the next orientation restores the exact state.
        save()
        super.onSaveInstanceState(outState)
    }

    private fun applyStartupDefaults(){
        for(i in 0..3){eq[i]=0f;previousEq[i]=0f;sectionPreviousEq[i]=0f}
        for(i in 0..2){iso[i]=0f;previousIso[i]=0f;sectionPreviousIso[i]=0f}
        filters[0]=0f;filters[1]=0f;resonance=0f
        for(i in 0..2){previousFilters[i]=0f;sectionPreviousFilters[i]=0f}
        gain=0f;compMakeup=0f;compressorOn=false
        bassBoost=0f;loudness=0f;bassBoostOn=false;loudnessOn=false
    }

    private fun dp(v:Int)= (v*resources.displayMetrics.density).roundToInt()
    private fun windowHeightDp():Float {
        if (::container.isInitialized && container.height > 0) return container.height / resources.displayMetrics.density
        val h=resources.configuration.screenHeightDp
        return if(h>0)h.toFloat() else resources.displayMetrics.heightPixels/resources.displayMetrics.density
    }
    private fun dialHeight():Int {
        val h=windowHeightDp()
        // Tablet landscape keeps the established 168dp dial. On phones the dial
        // height is calculated from the actual window so both orientations fit.
        val value=when {
            phonePortrait && wideLayout -> (((h-64f)/2f)-102f).roundToInt().coerceIn(28,112)
            wideLayout -> 168
            phonePortrait -> (((h-64f)/2f)-104f).roundToInt().coerceIn(40,148)
            !wideLayout -> 116
            h<700f -> 148
            else -> 160
        }
        return dp(value)
    }

    // Measure nested vertical stacks as well as their parent rows. This catches
    // overflow inside MASTER's compact 2x2 controls, not just the outer quadrant.
    private fun requiredContentHeight(view: View): Int {
        val group = view as? ViewGroup ?: return view.measuredHeight
        val vertical = group is LinearLayout && group.orientation == LinearLayout.VERTICAL
        var childrenExtent = 0
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            val extent = requiredContentHeight(child) + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            childrenExtent = if (vertical) childrenExtent + extent else max(childrenExtent, extent)
        }
        val nested = view.paddingTop + view.paddingBottom + childrenExtent
        return max(view.measuredHeight, nested)
    }

    // Uniformly fit all four sections to the measured window. The scale is only
    // applied when a nested section would otherwise overflow; normal tablet
    // landscape therefore keeps its original geometry. Also handles pop-up resizing.
    private fun fitUiToWindow() {
        if (!::root.isInitialized || !::container.isInitialized || container.width <= 0 || container.height <= 0) return
        var scale = 1f
        for (section in sections.values) {
            if (section.height <= 0) continue
            val required = requiredContentHeight(section)
            if (required > 0) scale = min(scale, section.height.toFloat() / required.toFloat())
        }
        scale = scale.coerceAtMost(1f).coerceAtLeast(0.1f)
        root.pivotX = 0f
        root.pivotY = 0f
        root.scaleX = scale
        root.scaleY = scale
        root.translationX = (container.width - root.width * scale) / 2f
        root.translationY = 0f
    }

    private fun label(t:String, size:Float=11f, color:Int=Color.LTGRAY)=TextView(this).apply {
        text=t; textSize=size; setTextColor(color); gravity=Gravity.CENTER; letterSpacing=.08f
    }
    private fun sectionLabel(t:String, size:Float, color:Int)=label(t,size,color).apply {
        setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(t:String, action:()->Unit)=Button(this).apply { text=t; setOnClickListener { action() } }
    private fun redButton(t:String, active:Boolean=true, action:()->Unit)=Button(this).apply { text=t; textSize=10f; setTextColor(Color.WHITE); backgroundTintList=ColorStateList.valueOf(if(active) Color.rgb(190,35,42) else Color.rgb(105,35,40)); setOnClickListener { action() } }
    private fun circleAction(symbol:String, diameter:Int, action:()->Unit):View = TextView(this).apply {
        text=symbol; textSize=if(diameter>=48)16f else 11f; setTextColor(Color.WHITE)
        gravity=Gravity.CENTER; contentDescription=when(symbol){"F"->"Flat";"0"->"Reset";else->"Return"}
        background=android.graphics.drawable.GradientDrawable().apply {
            shape=android.graphics.drawable.GradientDrawable.OVAL
            setColor(colorButtons); setStroke(dp(1),Color.rgb(255,235,100))
        }
        setTextColor(Color.BLACK)
        setOnClickListener{action()}
    }

    private fun roundAction(symbol:String, caption:String, active:Boolean=true, action:()->Unit):View {
        val narrowPhone = phonePortrait && !wideLayout
        return LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            val dot=TextView(this@MainActivity).apply {
                text=symbol;gravity=Gravity.CENTER;textSize=if(symbol=="F")9f else 11f
                setTextColor(Color.WHITE)
                background=android.graphics.drawable.GradientDrawable().apply {
                    shape=android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if(active)Color.rgb(25,150,65) else Color.rgb(190,35,42))
                    setStroke(dp(1),if(active)Color.rgb(100,230,130) else Color.rgb(245,100,105))
                }
                setOnClickListener { action() }
            }
            addView(dot,LinearLayout.LayoutParams(dp(if(narrowPhone)18 else 26),dp(if(narrowPhone)20 else 24)))
            val txt=TextView(this@MainActivity).apply {
                text=caption;textSize=if(narrowPhone)8f else 9f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(if(narrowPhone)3 else 5),0,dp(2),0);setOnClickListener { action() }
            }
            addView(txt,LinearLayout.LayoutParams(-2,dp(24)))
        }
    }
    private fun gearButton():View = object : View(this) {
        private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorOptions; style = Paint.Style.FILL }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val outer = min(width, height) * 0.43f
            val root = outer * 0.76f
            val path = android.graphics.Path()
            for (i in 0 until 32) {
                val radius = if (i % 4 == 0 || i % 4 == 1) outer else root
                val angle = Math.toRadians((-90.0 + i * 360.0 / 32.0))
                val x = cx + cos(angle).toFloat() * radius
                val y = cy + sin(angle).toFloat() * radius
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, gearPaint)
            gearPaint.color = bg
            canvas.drawCircle(cx, cy, outer * 0.37f, gearPaint)
            gearPaint.color = colorOptions
            canvas.drawCircle(cx, cy, outer * 0.17f, gearPaint)
        }
        init {
            contentDescription = "Impostazioni"
            setOnClickListener { showOptions() }
        }
    }

    private fun appPowerControl():Button = Button(this).apply {
        fun paintState(){
            text=if(appEnabled)"● ON" else "● OFF"
            backgroundTintList=ColorStateList.valueOf(if(appEnabled)Color.rgb(25,150,65) else Color.rgb(190,35,42))
        }
        textSize=11f;isAllCaps=false;setTextColor(Color.WHITE);minHeight=dp(30);minimumHeight=dp(30)
        setPadding(dp(3),0,dp(3),0);paintState();contentDescription="Interruttore generale audio"
        setOnClickListener { appEnabled=!appEnabled;paintState();save();scheduleUpdate() }
    }

    private fun save() {
        prefs.edit().putBoolean("app_enabled",appEnabled).putBoolean("mini",compact).putInt("master_mode",masterMode).putFloat("bass_boost",bassBoost).putFloat("loudness",loudness).putBoolean("bass_boost_on",bassBoostOn).putBoolean("loudness_on",loudnessOn).putFloat("previous_bass_boost",previousBassBoost).putFloat("previous_loudness",previousLoudness).putBoolean("limiter",limiterOn).putBoolean("mouse_wheel_adjust_on",mouseWheelAdjustOn).putFloat("mouse_wheel_sensitivity",mouseWheelSensitivity)
            .putBoolean("compressor_on",compressorOn).putFloat("comp_threshold",compThreshold).putFloat("comp_ratio",compRatio).putFloat("comp_attack",compAttack).putFloat("comp_release",compRelease).putFloat("comp_makeup",compMakeup)
            .putInt("color_eq",colorEq).putInt("color_iso",colorIso).putInt("color_filters",colorFilters).putInt("color_master",colorMaster).putInt("color_buttons",colorButtons).putInt("color_background",colorBackground).putInt("color_titles",colorTitles).putInt("color_options",colorOptions)
            .putBoolean("eq_on",eqOn).putBoolean("iso_on",isoOn).putBoolean("filters_on",filtersOn).putFloat("gain",gain).putFloat("ui_scale",uiScale)
            .putBoolean("default_settings_on_start",defaultSettingsOnStart)
            .putBoolean("reverb_on",reverbOn).putInt("reverb_mix",reverbMix).putInt("reverb_decay",reverbDecay).putInt("reverb_size",reverbSize).putInt("reverb_damping",reverbDamping)
            .apply {
                for(i in 0..2){putFloat("eq_$i",eq[i]);putFloat("iso_$i",iso[i])}
                putFloat("eq_3",eq[3]).putBoolean("eq_double_mid",eqDoubleMid)
                putFloat("hpf",filters[0]);putFloat("resonance",resonance)
                                for(i in 0..2){putBoolean("eq_control_$i",eqControlOn[i]);putBoolean("iso_control_$i",isoControlOn[i])}
                for(i in 0..1)putBoolean("filter_control_$i",filterControlOn[i])
                for(i in 0..3){putFloat("previous_eq_$i",previousEq[i]);putFloat("section_previous_eq_$i",sectionPreviousEq[i])}
                for(i in 0..2){putFloat("previous_iso_$i",previousIso[i]);putFloat("section_previous_iso_$i",sectionPreviousIso[i])}
                putFloat("previous_hpf",previousFilters[0]).putFloat("previous_resonance",previousFilters[2])
                putFloat("section_previous_hpf",sectionPreviousFilters[0]).putFloat("section_previous_resonance",sectionPreviousFilters[2])
                modes.forEach{(k,v)->putBoolean("mode_$k",v)}
            }.apply()
    }

    private fun buildUi() {
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(if(phonePortrait)3 else 8),dp(if(phonePortrait)1 else 4),dp(if(phonePortrait)3 else 8),dp(if(phonePortrait)1 else 4));setBackgroundColor(bg);clipChildren=false;clipToPadding=false}
        val titleBar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        titleBar.addView(appPowerControl(),LinearLayout.LayoutParams(dp(if(phonePortrait)72 else 82),dp(if(phonePortrait)30 else 36)).apply{rightMargin=dp(5)})
        titleBar.addView(gearButton(),LinearLayout.LayoutParams(dp(54),dp(if(phonePortrait)36 else 42)).apply{leftMargin=dp(5)})
        root.addView(titleBar)
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false}
        // Tutte le sezioni restano nella stessa schermata: nessuno scrolling.
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        sections.clear()
        // Fixed four-quadrant mixer in every orientation: only the available
        // dimensions change, never the order or the set of controls.
        val upper=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;clipChildren=false;clipToPadding=false}
        val lower=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;clipChildren=false;clipToPadding=false}
        content.addView(upper,LinearLayout.LayoutParams(-1,0,1f))
        content.addView(lower,LinearLayout.LayoutParams(-1,0,1f))
        addSection("EQ","EQ",eq,-14f..14f,{eqOn},upper)
        addSection("ISOLATOR","ISOLATOR",iso,-24f..24f,{isoOn},upper)
        addFilterSection(lower)
        addGainSection(lower)
        status=label("Connecting audio engine…",if(phonePortrait)7f else 9f)
        root.addView(status,LinearLayout.LayoutParams(-1,dp(if(phonePortrait)13 else 18)))
        container=FrameLayout(this).apply{clipChildren=true;clipToPadding=true}
        container.addView(root,FrameLayout.LayoutParams(-1,-1,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(container)
        container.addOnLayoutChangeListener { view,_,_,_,_,_,_,_,_ ->
            if (view.width > 0 && view.height > 0) {
                val newWide = view.width > view.height
                val newPhone = min(view.width,view.height) / resources.displayMetrics.density < 600f
                val resized = lastWindowWidthPx > 0 &&
                    (abs(view.width-lastWindowWidthPx) > dp(12) || abs(view.height-lastWindowHeightPx) > dp(12))
                lastWindowWidthPx=view.width
                lastWindowHeightPx=view.height
                if (newWide != wideLayout || newPhone != phonePortrait || resized) {
                    wideLayout = newWide
                    phonePortrait = newPhone
                    if (!layoutRebuildPending) {
                        layoutRebuildPending = true
                        handler.post {
                            layoutRebuildPending = false
                            if (::container.isInitialized) buildUi()
                        }
                    }
                } else fitUiToWindow()
            }
        }
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> fitUiToWindow() }
        container.post { root.post { fitUiToWindow() } }
    }

    private fun scheduleUpdate() {
        // Update DSP at display cadence; persist settings off the hot interaction path.
        // Give audio parameter changes priority over UI work and persistence.
        dspHandler.removeCallbacks(dspUpdate)
        dspHandler.postAtFrontOfQueue(dspUpdate)
        handler.removeCallbacks(saveUpdate)
        handler.postDelayed(saveUpdate, 220L)
    }

    private fun addSection(key:String,title:String,values:FloatArray,range:ClosedFloatingPointRange<Float>,enabled:()->Boolean,parent:LinearLayout=content) {
        val active = if(key=="EQ") eqControlOn else isoControlOn
        val sectionAccent = accentFor(key)
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.TOP;setPadding(dp(if(phonePortrait)1 else 4),dp(if(phonePortrait)1 else 4),dp(if(phonePortrait)1 else 4),dp(1))}
        val compactHeader = phonePortrait && !wideLayout
        val actionReturnSize=if(compactHeader)18 else if(phonePortrait)24 else 42
        val actionFlatSize=if(compactHeader)22 else if(phonePortrait)32 else 56
        val actionGap=if(compactHeader)1 else if(phonePortrait)3 else 8
        val actionHeight=if(compactHeader)24 else if(phonePortrait)36 else 58
        box.addView(sectionLabel(title,if(phonePortrait)11f else 13f,titleTint(sectionAccent)),LinearLayout.LayoutParams(-1,dp(if(compactHeader)24 else 32)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        val header=LinearLayout(this).apply{orientation=if(compactHeader)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val headerControls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        box.addView(header)
        // In tablet portrait, stack LOW / MID / HIGH as full-width mixer strips.
        // Phone layouts keep their compact three-column arrangement.
        val useVerticalRows=!wideLayout && !phonePortrait
        // Size portrait strips from their actual count: EQ has four rows in
        // double-mid mode, while the Isolator has three. This prevents the last
        // strip from being clipped by the quadrant boundary.
        val indices = if(key=="EQ" && eqDoubleMid) listOf(2,3,1,0) else listOf(2,1,0)
        // Tablet portrait uses measured, weighted rows instead of guessed dp heights.
        // Every band receives exactly the same share of the real section height.
        val verticalRowHeight = ((windowHeightDp()/2f-96f)/indices.size).roundToInt().coerceIn(52,140)
        val verticalDialHeight = if(useVerticalRows) dp(verticalRowHeight) else dialHeight()
        val row=LinearLayout(this).apply{orientation=if(useVerticalRows)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=if(useVerticalRows)0f else if(key=="EQ" && eqDoubleMid)4f else 3f}
        val names = if(key=="EQ" && eqDoubleMid) listOf("HIGH","MID HIGH","MID LOW","LOW") else listOf("HIGH","MID","LOW")
        for(pos in indices.indices){
            val i=indices[pos]
            val col=LinearLayout(this).apply{orientation=if(useVerticalRows)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val vLabel=label("${names[pos]}  ${fmt(values[i])} dB", if(compactHeader && key=="EQ" && eqDoubleMid)7f else if(compactHeader)8.5f else if(compact)10f else 12f,Color.WHITE)
            val controlHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            if(useVerticalRows) vLabel.gravity=Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            controlHeader.addView(vLabel,if(useVerticalRows)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(0,dp(if(phonePortrait)19 else 28),1f))
            val controlRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            if(modes[key]==true) {
                val seek=SeekBar(this).apply{
                    max=(range.endInclusive-range.start).roundToInt()*10
                    progress=((values[i]-range.start)*10).roundToInt().coerceIn(0,max)
                    isEnabled=active[i]
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user){values[i]=range.start+p/10f;vLabel.text="${names[pos]}  ${fmt(values[i])} dB";scheduleUpdate()}}
                        override fun onStartTrackingTouch(s:SeekBar?){if(key=="EQ")previousEq[i]=values[i] else previousIso[i]=values[i]}
                        override fun onStopTrackingTouch(s:SeekBar?){scheduleUpdate()}
                    })
                }
                controlRow.addView(seek,LinearLayout.LayoutParams(0,dp(48),1f))
            } else {
                val knob=RotaryControl(this,names[pos],values[i],range.start,range.endInclusive,sectionAccent,{v->values[i]=v;vLabel.text="${names[i]}  ${fmt(v)} dB";scheduleUpdate()},{fmt(values[i])+" dB"},onStart={if(key=="EQ")previousEq[i]=values[i] else previousIso[i]=values[i]},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f},portraitDialFit=useVerticalRows)
                knob.isEnabled=active[i]
                // In portrait each dial occupies the same flexible column; the
                // control measures itself from its row, never from a guessed width.
                val knobParams = if(useVerticalRows) LinearLayout.LayoutParams(-1,-1) else LinearLayout.LayoutParams(-1,verticalDialHeight)
                controlRow.addView(knob,knobParams)
            }
            col.addView(controlRow,if(useVerticalRows)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,dialHeight()))
            val bandActions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
            bandActions.addView(circleAction("↶",actionReturnSize) {
                val old=if(key=="EQ")previousEq[i] else previousIso[i]
                if(key=="EQ")previousEq[i]=values[i] else previousIso[i]=values[i]
                values[i]=old; save(); refreshUi(); scheduleUpdate()
            },LinearLayout.LayoutParams(dp(actionReturnSize),dp(actionReturnSize)).apply{rightMargin=dp(actionGap)})
            bandActions.addView(circleAction("F",actionFlatSize) {
                if(key=="EQ")previousEq[i]=values[i] else previousIso[i]=values[i]
                values[i]=0f; scheduleUpdate(); refreshUi()
            },LinearLayout.LayoutParams(dp(actionFlatSize),dp(actionFlatSize)))
            col.addView(bandActions,if(useVerticalRows)LinearLayout.LayoutParams(dp(actionReturnSize+actionFlatSize+actionGap),-1) else LinearLayout.LayoutParams(-1,dp(actionHeight)))
            row.addView(col,if(useVerticalRows)LinearLayout.LayoutParams(-1,0,1f) else LinearLayout.LayoutParams(0,-2,1f))
        }
        box.addView(row,if(useVerticalRows)LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(4)} else LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(if(compactHeader)4 else 7)})
        val sectionPower = Button(this).apply {
            fun paintState() {
                val on = if(key=="EQ") eqOn else isoOn
                text = if(on) "ON" else "OFF"
                backgroundTintList = ColorStateList.valueOf(
                    if(on) Color.rgb(25,150,65) else Color.rgb(190,35,42)
                )
            }
            textSize=if(compactHeader)8f else 9f; isAllCaps=false; setTextColor(Color.WHITE)
            minHeight=dp(if(compactHeader)24 else 27); minimumHeight=dp(if(compactHeader)24 else 27)
            setPadding(dp(3),0,dp(3),0)
            paintState()
            contentDescription = "$key on/off"
            setOnClickListener {
                if(key=="EQ") eqOn=!eqOn else isoOn=!isoOn
                paintState()
                save(); scheduleUpdate()
                val activeNow = if(key=="EQ") eqOn else isoOn
                status.text = if(activeNow) "$key ON" else "$key OFF"
                // Refresh band controls to reflect the section state.
                refreshUi()
            }
        }
        headerControls.addView(sectionPower,LinearLayout.LayoutParams(dp(if(compactHeader)38 else 48),dp(if(compactHeader)24 else 28)).apply{rightMargin=dp(5)})
        headerControls.addView(circleAction("F",if(compactHeader)30 else 38) {
            val previous=if(key=="EQ")sectionPreviousEq else sectionPreviousIso
            for(i in 0..(if(key=="EQ")3 else 2)){previous[i]=values[i];values[i]=0f}
            scheduleUpdate();refreshUi()
        },LinearLayout.LayoutParams(dp(if(compactHeader)30 else 38),dp(if(compactHeader)30 else 38)))
        header.addView(headerControls,if(compactHeader)LinearLayout.LayoutParams(-1,dp(36)) else if(useVerticalRows)LinearLayout.LayoutParams(-2,dp(44)).apply { gravity=Gravity.CENTER_HORIZONTAL } else LinearLayout.LayoutParams(-1,dp(44)))
        parent.addView(box,if(parent===content) LinearLayout.LayoutParams(-1,-2) else LinearLayout.LayoutParams(0,-1,1f));sections[key]=box
    }

    private fun addFilterSection(parent:LinearLayout=content){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.TOP;setPadding(dp(if(phonePortrait)1 else 4),dp(4),dp(if(phonePortrait)1 else 4),dp(1))}
        val filterAccent = accentFor("FILTERS")
        val useVerticalRows=!wideLayout && !phonePortrait
        val filterPower = roundAction("",if(filtersOn)"ON" else "OFF",filtersOn){
            filtersOn=!filtersOn;save();refreshUi();scheduleUpdate()
        }
        if(useVerticalRows) {
            // Tablet portrait: center the section title and reserve the same
            // 44dp control lane used by EQ and ISOLATOR.
            box.addView(sectionLabel("HPF",13f,titleTint(filterAccent)),LinearLayout.LayoutParams(-1,dp(32)).apply { gravity=Gravity.CENTER_HORIZONTAL })
            val filterTitle=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            filterTitle.addView(filterPower)
            box.addView(filterTitle,LinearLayout.LayoutParams(-1,dp(44)))
        } else {
            // Preserve the established phone and landscape presentation.
            box.addView(sectionLabel("HPF",if(phonePortrait)11f else 13f,titleTint(filterAccent)),LinearLayout.LayoutParams(-1,dp(if(phonePortrait)24 else 32)))
            val filterTitle=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            filterTitle.addView(filterPower)
            box.addView(filterTitle,LinearLayout.LayoutParams(-1,dp(44)))
        }
        // Match EQ: use the available quadrant height rather than a fixed 138dp strip.
        val verticalRowHeight = ((windowHeightDp()/2f-96f)/2f).roundToInt().coerceIn(52,140)
        val verticalDialHeight = if(useVerticalRows) dp((verticalRowHeight-6).coerceAtLeast(44)) else dialHeight()
        // Enlarge only the two FILTER dials on tablet landscape; leave every other control unchanged.
        val filterLandscapeDialHeight = if(wideLayout && !phonePortrait) dp(210) else dialHeight()
        val row=LinearLayout(this).apply{orientation=if(useVerticalRows)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=if(useVerticalRows)0f else 2f}
        val names=listOf("HPF","RESONANCE")
        for(i in 0..1){
            val col=LinearLayout(this).apply{orientation=if(useVerticalRows)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val current=if(i==1)resonance else filters[0]
            val compactFilter = phonePortrait && !wideLayout
            val filterReturnSize=if(compactFilter)18 else if(phonePortrait)24 else 42
            val filterFlatSize=if(compactFilter)22 else if(phonePortrait)32 else 56
            val filterActionGap=if(compactFilter)1 else if(phonePortrait)3 else 8
            val filterActionHeight=if(compactFilter)24 else if(phonePortrait)36 else 58
            val controlEnabled=filtersOn
            // Tablet portrait: EQ dials use the full row width and are centered
            // on the section's vertical axis. Do not add a blank label lane here:
            // it would shift FILTER dials to the right and break vertical alignment.
            if(!useVerticalRows) {
                col.addView(View(this),LinearLayout.LayoutParams(-1,dp(if(compactFilter)38 else if(wideLayout)10 else 24)))
            }
            val controlRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            if(modes["FILTERS"]==true){
                val seek=SeekBar(this).apply{
                    max=100;progress=(current*100).roundToInt();isEnabled=controlEnabled
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){if(user){if(i==1)resonance=p/100f else filters[0]=p/100f;scheduleUpdate()}}
                        override fun onStartTrackingTouch(b:SeekBar?){previousFilters[i]=if(i==1)resonance else filters[0]}
                        override fun onStopTrackingTouch(b:SeekBar?){scheduleUpdate()}
                    })
                }
                controlRow.addView(seek,LinearLayout.LayoutParams(0,dp(48),1f))
            }else{
                val knob=RotaryControl(this,names[i],current,0f,1f,filterAccent,{v->
                    if(i==1)resonance=v else filters[0]=v
                    
                    scheduleUpdate()
                },{((if(i==1)resonance else filters[0])*100).roundToInt().toString()+"%"},onStart={previousFilters[i]=if(i==1)resonance else filters[0]},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f},portraitDialFit=useVerticalRows,bottomTextLift=if(useVerticalRows)8f else 0f)
                knob.isEnabled=controlEnabled
                controlRow.addView(knob,if(useVerticalRows) LinearLayout.LayoutParams(-1,-1) else LinearLayout.LayoutParams(0,if(wideLayout && !phonePortrait) filterLandscapeDialHeight else verticalDialHeight,1f))
            }
            col.addView(controlRow,if(useVerticalRows)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,if(wideLayout && !phonePortrait) filterLandscapeDialHeight else dialHeight()))
            val filterActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            filterActions.addView(circleAction("↶",filterReturnSize){
                val old=previousFilters[i]; previousFilters[i]=if(i==1)resonance else filters[0]
                if(i==1)resonance=old else filters[0]=old
                save();refreshUi();scheduleUpdate()
            },LinearLayout.LayoutParams(dp(filterReturnSize),dp(filterReturnSize)).apply{rightMargin=dp(filterActionGap)})
            filterActions.addView(circleAction("0",filterFlatSize){
                previousFilters[i]=if(i==1)resonance else filters[0]
                if(i==1)resonance=0f else filters[0]=0f
                scheduleUpdate();refreshUi()
            },LinearLayout.LayoutParams(dp(filterFlatSize),dp(filterFlatSize)))
            col.addView(filterActions,if(useVerticalRows)LinearLayout.LayoutParams(dp(filterReturnSize+filterFlatSize+filterActionGap),-1) else LinearLayout.LayoutParams(-1,dp(filterActionHeight)))
            row.addView(col,if(useVerticalRows)LinearLayout.LayoutParams(-1,0,1f) else LinearLayout.LayoutParams(0,-2,1f))
        }
        box.addView(row,if(useVerticalRows) LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(4)} else LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(if(phonePortrait)4 else 7)});parent.addView(box,if(parent===content) LinearLayout.LayoutParams(-1,-2) else LinearLayout.LayoutParams(0,-1,1f));sections["FILTERS"]=box
    }

    private fun addGainSection(parent:LinearLayout=content){
        val masterAccent = accentFor("MASTER")
        val tabletPortrait = !phonePortrait && !wideLayout
        val compactMaster = phonePortrait && !wideLayout
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.TOP;setPadding(dp(if(phonePortrait)1 else 4),dp(4),dp(if(phonePortrait)1 else 4),dp(1))}
        box.addView(sectionLabel("MASTER",if(phonePortrait)11f else 13f,titleTint(masterAccent)),LinearLayout.LayoutParams(-1,dp(if(compactMaster)24 else 32)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        if(tabletPortrait){
            // Portrait tablet: one full-width MASTER strip per control.
            // Keep the existing rotary controls and DSP callbacks; only the layout changes.
            val rowHeight = ((windowHeightDp()/2f-96f)/4f).roundToInt().coerceIn(52,140)
            fun masterRow(title:String, value:String, dial:View, actionViews:List<View> = emptyList()):View {
                return LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                    setPadding(dp(2),dp(1),dp(2),dp(1))
                    // No left-side text: keep every MASTER dial on the same
                    // centered axis and give the four controls equal space.
                    addView(dial,LinearLayout.LayoutParams(0,dp(rowHeight-2),1f))
                    if(actionViews.isNotEmpty()){
                        val actions=LinearLayout(this@MainActivity).apply {
                            orientation=LinearLayout.VERTICAL
                            gravity=Gravity.CENTER
                            actionViews.forEachIndexed { index, action ->
                                addView(action,LinearLayout.LayoutParams(-1,dp(28)).apply {
                                    topMargin=if(index==0)0 else dp(4)
                                    bottomMargin=dp(2)
                                })
                            }
                        }
                        addView(actions,LinearLayout.LayoutParams(dp(112),-1))
                    }
                }.also { it.minimumHeight=dp(rowHeight) }
            }
            val gainDial:View = if(modes["GAIN"]==true){
                SeekBar(this).apply {
                    max=240;progress=((gain+12)*10).roundToInt()
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){if(user){gain=p/10f-12f;scheduleUpdate()}}
                        override fun onStartTrackingTouch(b:SeekBar?){}
                        override fun onStopTrackingTouch(b:SeekBar?){save()}
                    })
                }
            } else RotaryControl(this,"GAIN",gain,-12f,12f,masterAccent,{v->gain=v;scheduleUpdate()},{fmt(gain)+" dB"},titleInside=true,wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            // Match the common section header height: all four sections start
            // their rotary rows on the same baseline.
            box.addView(View(this),LinearLayout.LayoutParams(-1,dp(44)))
            val gainSpacer=View(this).apply{isClickable=false}
            box.addView(masterRow("GAIN",fmt(gain)+" dB",gainDial,listOf(gainSpacer)),LinearLayout.LayoutParams(-1,dp(rowHeight)).apply{topMargin=dp(7)})
            val compDial=RotaryControl(this,"COMP",compMakeup,0f,100f,accentFor("MASTER"),{v->compMakeup=v.coerceIn(0f,100f);scheduleUpdate()},{compMakeup.roundToInt().toString()+"%"},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val compToggle=roundAction("",if(compressorOn)"ON" else "OFF",compressorOn){
                compressorOn=!compressorOn;save();refreshUi();scheduleUpdate()
            }
            box.addView(masterRow("COMP",compMakeup.roundToInt().toString()+"%",compDial,listOf(compToggle)),LinearLayout.LayoutParams(-1,dp(rowHeight)))
            val bassDial=RotaryControl(this,"BASS BOOST",bassBoost,0f,100f,masterAccent,{v->bassBoost=v;scheduleUpdate()},{bassBoost.roundToInt().toString()+"%"},onStart={previousBassBoost=bassBoost},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val bassToggle=roundAction("",if(bassBoostOn)"ON" else "OFF",bassBoostOn){bassBoostOn=!bassBoostOn;save();refreshUi();scheduleUpdate()}
            val bassReturn=roundAction("↶","RETURN",true){bassBoost=previousBassBoost;save();refreshUi();scheduleUpdate()}
            box.addView(masterRow("BASS BOOST",bassBoost.roundToInt().toString()+"%",bassDial,listOf(bassToggle,bassReturn)),LinearLayout.LayoutParams(-1,dp(rowHeight)))
            val loudDial=RotaryControl(this,"LOUDNESS",loudness,0f,100f,masterAccent,{v->loudness=v;scheduleUpdate()},{loudness.roundToInt().toString()+"%"},onStart={previousLoudness=loudness},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val loudToggle=roundAction("",if(loudnessOn)"ON" else "OFF",loudnessOn){loudnessOn=!loudnessOn;save();refreshUi();scheduleUpdate()}
            val loudReturn=roundAction("↶","RETURN",true){loudness=previousLoudness;save();refreshUi();scheduleUpdate()}
            box.addView(masterRow("LOUDNESS",loudness.roundToInt().toString()+"%",loudDial,listOf(loudToggle,loudReturn)),LinearLayout.LayoutParams(-1,dp(rowHeight)))
        }else{
            val gainCol=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val gainValueLabel=label(fmt(gain)+" dB",if(phonePortrait)9f else 10f,Color.WHITE)
            gainCol.addView(gainValueLabel,LinearLayout.LayoutParams(-1,dp(16)))
            gainCol.addView(LinearLayout(this),LinearLayout.LayoutParams(-1,dp(24)))
            if(modes["GAIN"]==true){
                val s=SeekBar(this).apply{max=240;progress=((gain+12)*10).roundToInt();setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){if(user){gain=p/10f-12f;gainValueLabel.text=fmt(gain)+" dB";scheduleUpdate()}}
                    override fun onStartTrackingTouch(b:SeekBar?){}
                    override fun onStopTrackingTouch(b:SeekBar?){}
                })}
                gainCol.addView(s,LinearLayout.LayoutParams(-1,dp(48)))
            }else{
                gainCol.addView(RotaryControl(this,"GAIN",gain,-12f,12f,masterAccent,{v->gain=v;gainValueLabel.text=fmt(gain)+" dB";scheduleUpdate()},{fmt(gain)+" dB"},titleInside=true,wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f}),LinearLayout.LayoutParams(-1,masterDialHeight()))
            }
            if(compactMaster){
                val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
                top.addView(gainCol,LinearLayout.LayoutParams(0,-2,1f))
                top.addView(masterCompControl(),LinearLayout.LayoutParams(0,-2,1f))
                val bottom=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
                bottom.addView(masterToneControl("BASS BOOST",bassBoost,bassBoostOn,{bassBoost=it},{bassBoostOn=it},{previousBassBoost=bassBoost},{bassBoost=previousBassBoost},true),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(4);rightMargin=dp(4)})
                bottom.addView(masterToneControl("LOUDNESS",loudness,loudnessOn,{loudness=it},{loudnessOn=it},{previousLoudness=loudness},{loudness=previousLoudness},true),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(4);rightMargin=dp(4)})
                box.addView(top,LinearLayout.LayoutParams(-1,-2))
                box.addView(bottom,LinearLayout.LayoutParams(-1,-2))
            }else{
                val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=5f}
                row.addView(gainCol,LinearLayout.LayoutParams(0,-2,1f))
                row.addView(masterCompControl(),LinearLayout.LayoutParams(0,-2,1f))
                row.addView(masterToneControl("BASS BOOST",bassBoost,bassBoostOn,{bassBoost=it},{bassBoostOn=it},{previousBassBoost=bassBoost},{bassBoost=previousBassBoost}),LinearLayout.LayoutParams(0,-2,1f))
                row.addView(masterToneControl("LOUDNESS",loudness,loudnessOn,{loudness=it},{loudnessOn=it},{previousLoudness=loudness},{loudness=previousLoudness}),LinearLayout.LayoutParams(0,-2,1f))
                box.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7)})
            }
        }
        val reverbButton = Button(this).apply {
            text = "REVERB"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.BLACK)
            backgroundTintList = ColorStateList.valueOf(colorButtons)
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { showReverbPopup() }
        }
        box.addView(reverbButton, LinearLayout.LayoutParams(-2, dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(5)
            bottomMargin = dp(2)
        })
        parent.addView(box,if(parent===content) LinearLayout.LayoutParams(-1,-2) else LinearLayout.LayoutParams(0,-1,1f));sections["GAIN"]=box
    }

    private fun masterToneControl(
        title:String,value:Float,enabled:Boolean,setValue:(Float)->Unit,setEnabled:(Boolean)->Unit,
        remember:()->Unit,restore:()->Unit,compactActions:Boolean=false
    ):View{
        val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
        // Percentage label removed; master values remain adjustable.
        val actions=LinearLayout(this).apply{orientation=if(compactActions)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val onAction=roundAction("",if(enabled)"ON" else "OFF",enabled){
            setEnabled(!enabled);save();refreshUi();scheduleUpdate()
        }
        val returnAction=roundAction("↶","RETURN",true){
            restore();save();refreshUi();scheduleUpdate()
        }
        if(compactActions){
            actions.addView(onAction,LinearLayout.LayoutParams(-1,dp(24)).apply{bottomMargin=dp(5)})
            actions.addView(returnAction,LinearLayout.LayoutParams(-1,dp(24)))
        }else{
            actions.addView(onAction,LinearLayout.LayoutParams(-2,dp(24)).apply{rightMargin=dp(8)})
            actions.addView(returnAction,LinearLayout.LayoutParams(-2,dp(24)).apply{leftMargin=dp(8)})
        }
        col.addView(actions,LinearLayout.LayoutParams(-1,dp(if(compactActions)56 else 28)))
        col.addView(RotaryControl(this,title,value,0f,100f,accentFor("MASTER"),{v->setValue(v);scheduleUpdate()},{if(title=="BASS BOOST")"${bassBoost.roundToInt()}%" else "${loudness.roundToInt()}%"},onStart=remember,wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f}),
            LinearLayout.LayoutParams(-1,masterDialHeight()).apply{topMargin=dp(6)})
        return col
    }

    private fun masterDialHeight():Int {
        if(phonePortrait && wideLayout) return dialHeight()
        if(phonePortrait && !wideLayout){
            val section=(windowHeightDp()-64f)/2f
            val row=(section-18f)/2f
            return dp((row-64f).roundToInt().coerceIn(24,88))
        }
        // Give MASTER dials a little more vertical room so their faces sit
        // centrally in each control column without changing the four-column layout.
        return (dialHeight()*0.94f).roundToInt()
    }

    private fun masterCompControl():View{
        val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
        // Percentage label removed; COMP remains adjustable.
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        actions.addView(roundAction("",if(compressorOn)"ON" else "OFF",compressorOn){
            compressorOn=!compressorOn
            save()
            refreshUi()
            scheduleUpdate()
        })
        col.addView(actions,LinearLayout.LayoutParams(-1,dp(24)))
        col.addView(RotaryControl(this,"COMP",compMakeup,0f,100f,accentFor("MASTER"),{v->
            compMakeup=v.coerceIn(0f,100f)
            scheduleUpdate()
        },{"${compMakeup.roundToInt()}%"},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f}),
            LinearLayout.LayoutParams(-1,masterDialHeight()).apply{topMargin=dp(6)})
        return col
    }

    private fun refreshUi(){ buildUi() }

    private fun resetAllControls(){
        for(i in 0..3){eq[i]=0f;previousEq[i]=0f;sectionPreviousEq[i]=0f}
        for(i in 0..2){iso[i]=0f;previousIso[i]=0f;sectionPreviousIso[i]=0f}
        filters[0]=0f;filters[1]=0f;resonance=0f
        for(i in 0..2){previousFilters[i]=0f;sectionPreviousFilters[i]=0f}
        gain=0f;compMakeup=0f;compressorOn=false
        bassBoost=0f;loudness=0f;bassBoostOn=false;loudnessOn=false
        eqOn=true;isoOn=true;filtersOn=true
        for(i in 0..3)eqControlOn[i]=true
        for(i in 0..2)isoControlOn[i]=true
        for(i in 0..1)filterControlOn[i]=true
        save();refreshUi();scheduleUpdate()
    }

    private fun showColorOptions(){
        val categories=arrayOf("EQ","ISOLATOR","FILTRI","MASTER / MANOPOLE","PULSANTI","SFONDO","TITOLI","OPZIONI")
        AlertDialog.Builder(this).setTitle("PERSONALIZZA COLORI")
            .setItems(categories){_,which->
                val key=arrayOf("EQ","ISOLATOR","FILTERS","MASTER","BUTTONS","BACKGROUND","TITLES","OPTIONS")[which]
                val names=arrayOf("Giallo oro","Azzurro","Arancione","Verde","Rosso","Viola","Bianco","Grigio","Nero","Antracite","Blu notte","Blu elettrico","Turchese","Rosa","Magenta","Verde lime","Ambra")
                val colors=intArrayOf(Color.rgb(214,179,106),Color.rgb(80,180,220),Color.rgb(230,135,70),Color.rgb(70,190,120),Color.rgb(220,70,75),Color.rgb(165,115,220),Color.WHITE,Color.rgb(105,115,130),Color.BLACK,Color.rgb(17,19,23),Color.rgb(20,30,55),Color.rgb(35,90,210),Color.rgb(35,190,180),Color.rgb(235,125,170),Color.rgb(190,55,175),Color.rgb(145,205,55),Color.rgb(235,175,45))
                val current=when(key){"EQ"->colorEq;"ISOLATOR"->colorIso;"FILTERS"->colorFilters;"MASTER"->colorMaster;"BUTTONS"->colorButtons;"TITLES"->colorTitles;"OPTIONS"->colorOptions;else->colorBackground}
                val selected=colors.indexOf(current).coerceAtLeast(0)
                AlertDialog.Builder(this).setTitle("COLORE • ${categories[which]}")
                    .setSingleChoiceItems(names,selected){dialog,index->
                        when(key){"EQ"->colorEq=colors[index];"ISOLATOR"->colorIso=colors[index];"FILTERS"->colorFilters=colors[index];"MASTER"->colorMaster=colors[index];"BUTTONS"->colorButtons=colors[index];"BACKGROUND"->colorBackground=colors[index];"TITLES"->colorTitles=colors[index];"OPTIONS"->colorOptions=colors[index]}
                        save();refreshUi();dialog.dismiss()
                    }.setNegativeButton("ANNULLA",null).show()
            }.setNegativeButton("CHIUDI",null).show()
    }


    private fun showReverbPopup(){
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val enabledSwitch = Switch(this).apply {
            text = "REVERB ON"
            textSize = 14f
            isChecked = reverbOn
        }
        panel.addView(enabledSwitch, LinearLayout.LayoutParams(-1, dp(48)))
        fun addReverbSlider(title:String, min:Int, max:Int, initial:Int, suffix:String, changed:(Int)->Unit) {
            val valueLabel = label("$title  $initial$suffix", 13f, colorMaster)
            valueLabel.gravity = Gravity.CENTER_VERTICAL
            panel.addView(valueLabel, LinearLayout.LayoutParams(-1, dp(30)))
            val seek = SeekBar(this).apply {
                this.max = max-min
                progress = (initial-min).coerceIn(0,max-min)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar:SeekBar?, progress:Int, fromUser:Boolean) {
                        if(fromUser) {
                            val value = min+progress
                            valueLabel.text = "$title  $value$suffix"
                            changed(value)
                            scheduleUpdate()
                        }
                    }
                    override fun onStartTrackingTouch(bar:SeekBar?){}
                    override fun onStopTrackingTouch(bar:SeekBar?){ save() }
                })
            }
            panel.addView(seek, LinearLayout.LayoutParams(-1, dp(42)))
        }
        addReverbSlider("MIX",0,100,reverbMix,"%"){reverbMix=it}
        addReverbSlider("DECAY",100,8000,reverbDecay," ms"){reverbDecay=it}
        addReverbSlider("ROOM SIZE",0,1000,reverbSize,"%"){reverbSize=it}
        addReverbSlider("DAMPING",100,1000,reverbDamping,"%"){reverbDamping=it}
        val diagnosticButton = Button(this).apply {
            text = "VERIFICA COMPATIBILITÀ AUDIO"
            textSize = 12f
            setOnClickListener { showAudioDiagnostics() }
        }
        panel.addView(diagnosticButton, LinearLayout.LayoutParams(-1, dp(44)).apply {
            topMargin = dp(8)
        })
        val captureButton = Button(this).apply {
            text = "TEST CATTURA AUDIO (YOUTUBE MORPHE)"
            textSize = 12f
            setOnClickListener { startAudioCaptureTest() }
        }
        panel.addView(captureButton, LinearLayout.LayoutParams(-1, dp(44)).apply {
            topMargin = dp(6)
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("REVERB • EXPERIMENTAL")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("CHIUDI") { _, _ -> save(); scheduleUpdate() }
            .create()
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            reverbOn = checked
            save()
            scheduleUpdate()
        }
        dialog.show()
    }
    private fun startAudioCaptureTest() {
        if (Build.VERSION.SDK_INT < 29) {
            AlertDialog.Builder(this).setTitle("TEST NON SUPPORTATO")
                .setMessage("La cattura audio interna richiede Android 10 o superiore.")
                .setPositiveButton("OK", null).show()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), capturePermissionRequestCode)
            return
        }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
        } catch (t: Throwable) {
            showCaptureError("IMPOSSIBILE AVVIARE LA RICHIESTA DI CATTURA", t)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == capturePermissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudioCaptureTest()
            } else {
                AlertDialog.Builder(this).setTitle("PERMESSO NEGATO")
                    .setMessage("Senza il permesso microfono Android non consente di avviare il test di cattura audio.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    @Deprecated("Deprecated in Android API  Activity Result migration is not required for this diagnostic")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequestCode) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Cattura audio non autorizzata", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            if (projection == null) {
                showCaptureError("ANDROID NON HA RESTITUITO LA SESSIONE DI CATTURA", null)
                return
            }
            captureProjection = projection
            projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    runOnUiThread { stopAudioCaptureTest() }
                }
            }, handler)
            beginAudioCapture(projection)
        } catch (t: Throwable) {
            showCaptureError("ERRORE CREAZIONE SESSIONE DI CATTURA", t)
            stopAudioCaptureTest()
        }
    }

    private fun beginAudioCapture(projection: android.media.projection.MediaProjection) {
        try {
            val sampleRate = 48000
            val format = android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuffer = android.media.AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("AudioRecord buffer non valido: $minBuffer")
            val config = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .build()
            val record = android.media.AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("AudioRecord non inizializzato sul dispositivo")
            }
            captureRecord = record
            showCaptureMeter()
            record.startRecording()
            captureRunning = true
            captureThread = Thread({
                val buffer = ShortArray(2048)
                var lastUi = 0L
                try {
                    while (captureRunning) {
                        val count = record.read(buffer, 0, buffer.size)
                        if (count > 0) {
                            var peak = 0
                            var sum = 0.0
                            for (i in 0 until count) {
                                val sample = buffer[i].toInt()
                                val absolute = kotlin.math.abs(sample)
                                if (absolute > peak) peak = absolute
                                sum += sample.toDouble() * sample.toDouble()
                            }
                            val rms = kotlin.math.sqrt(sum / count)
                            val rmsDb = if (rms > 0.0) 20.0 * kotlin.math.log10(rms / 32768.0) else -120.0
                            val peakDb = if (peak > 0) 20.0 * kotlin.math.log10(peak / 32768.0) else -120.0
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastUi >= 400) {
                                lastUi = now
                                handler.post {
                                    captureStatusView?.text =
                                        "CATTURA ATTIVA • 48 kHz mono\n" +
                                        "Livello RMS: \${"%.1f".format(rmsDb)} dBFS\n" +
                                        "Picco: \${"%.1f".format(peakDb)} dBFS\n\n" +
                                        if (peak > 0) "AUDIO RILEVATO: il flusso di YouTube è catturabile." else "In attesa di audio…"
                                }
                            }
                        } else if (count < 0) {
                            handler.post { captureStatusView?.text = "ERRORE LETTURA AUDIO: codice $count" }
                            break
                        }
                    }
                } catch (t: Throwable) {
                    handler.post { captureStatusView?.text = "ERRORE DURANTE LA CATTURA\n\${t.javaClass.simpleName}: \${t.message}" }
                }
            }, "Frequalizer-AudioCapture").apply { start() }
        } catch (t: Throwable) {
            showCaptureError("CATTURA AUDIO NON AVVIATA", t)
            stopAudioCaptureTest()
        }
    }

    private fun showCaptureMeter() {
        val view = TextView(this).apply {
            text = "Richiesta di avvio cattura…\n\nApri YouTube Morphe e fai partire un brano.\nQuesto test misura soltanto il livello catturato: non applica effetti e non modifica l'audio che ascolti."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        captureStatusView = view
        val dialog = AlertDialog.Builder(this)
            .setTitle("TEST CATTURA AUDIO")
            .setView(view)
            .setPositiveButton("FERMA TEST", null)
            .setNegativeButton("CHIUDI", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss() }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        }
        dialog.setOnDismissListener { stopAudioCaptureTest() }
        captureDialog = dialog
        dialog.show()
    }

    private fun showCaptureError(title: String, error: Throwable?) {
        val detail = if (error == null) title else "$title\n\n\${error.javaClass.simpleName}: \${error.message}"
        AlertDialog.Builder(this).setTitle("TEST CATTURA AUDIO")
            .setMessage(detail)
            .setPositiveButton("OK", null).show()
    }

    private fun stopAudioCaptureTest() {
        captureRunning = false
        val record = captureRecord
        captureRecord = null
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        captureThread = null
        val projection = captureProjection
        captureProjection = null
        try { projection?.stop() } catch (_: Throwable) {}
        captureStatusView = null
        captureDialog = null
    }

    private fun showAudioDiagnostics(){
        val lines = mutableListOf<String>()
        lines += "FREQUALIZER • AUDIO DIAGNOSTICS"
        lines += "DEVICE: ${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "ANDROID: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        lines += ""
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            lines += "OUTPUT SAMPLE RATE: ${audioManager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "n/d"} Hz"
            lines += "OUTPUT FRAMES/BUFFER: ${audioManager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "n/d"}"
            if (Build.VERSION.SDK_INT >= 23) {
                val outputs = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                lines += ""
                lines += "OUTPUT DEVICES (${outputs.size}):"
                outputs.forEach { device ->
                    lines += "• ${device.productName} | type=${device.type} | id=${device.id}"
                }
            }
        } catch (t:Throwable) {
            lines += "OUTPUT INFO ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
        lines += ""
        lines += "AUDIO EFFECT DESCRIPTORS:"
        try {
            val effects = android.media.audiofx.AudioEffect.queryEffects()
            if (effects.isNullOrEmpty()) {
                lines += "No descriptors returned by Android."
            } else {
                lines += "COUNT: ${effects.size}"
                effects.sortedBy { "${it.connectMode} ${it.name}" }.forEach { effect ->
                    lines += "• ${effect.name} | ${effect.implementor}"
                    lines += "  MODE=${effect.connectMode}"
                    lines += "  TYPE=${effect.type}"
                    lines += "  UUID=${effect.uuid}"
                }
            }
        } catch (t:Throwable) {
            lines += "QUERY EFFECTS ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
        lines += ""
        val rev = reverbEffect
        if (rev == null) {
            lines += "ENVIRONMENTAL REVERB INSTANCE: NOT CREATED"
        } else {
            try {
                lines += "ENVIRONMENTAL REVERB INSTANCE: CREATED"
                lines += "ID=${rev.id} | ENABLED=${rev.enabled} | HAS CONTROL=${rev.hasControl()}"
            } catch (t:Throwable) {
                lines += "ENVIRONMENTAL REVERB STATE ERROR: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        lines += ""
        lines += "NOTE: this report only reads Android audio capabilities; it does not change DSP or mixer settings."
        val report = lines.joinToString("\n")
        val reportView = TextView(this).apply {
            text = report
            textSize = 12f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("DIAGNOSTICA AUDIO")
            .setView(ScrollView(this).apply { addView(reportView) })
            .setPositiveButton("COPIA REPORT") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FREQUALIZER audio diagnostics", report))
                Toast.makeText(this, "Report copiato", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("PROVA REVERB CON SHIZUKU") { _, _ -> startInsertReverbTest() }
            .setNegativeButton("CHIUDI", null)
            .show()
    }

    private fun startInsertReverbTest() {
        stopInsertReverbTest()
        try {
            if (Shizuku.isPreV11()) {
                showShizukuReverbResult("Versione Shizuku non supportata. Aggiorna Shizuku alla versione 11 o successiva.")
                return
            }
            if (!Shizuku.pingBinder()) {
                showShizukuReverbResult("SHIZUKU NON ATTIVO\\n\\nApri Shizuku, avvia il servizio tramite Debug wireless e poi riprova.")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(shizukuRequestCode)
                return
            }
            bindShizukuReverbService()
        } catch (t: Throwable) {
            showShizukuReverbResult("ERRORE SHIZUKU\\n" + android.util.Log.getStackTraceString(t))
        }
    }

    private fun bindShizukuReverbService() {
        try {
            Shizuku.bindUserService(shizukuUserServiceArgs, shizukuServiceConnection)
            showShizukuReverbResult("Richiesta inviata a Shizuku. Attendo il servizio privilegiato…")
        } catch (t: Throwable) {
            showShizukuReverbResult("ERRORE AVVIO USER SERVICE\\n" + android.util.Log.getStackTraceString(t))
        }
    }

    private fun showShizukuReverbResult(message: String) {
        val view = TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle(if (message.startsWith("INSERT REVERB ATTIVO")) "TEST SHIZUKU • REVERB" else "SHIZUKU • DIAGNOSTICA")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(if (message.startsWith("INSERT REVERB ATTIVO")) "STOP / DISATTIVA" else "CHIUDI") { _, _ ->
                if (message.startsWith("INSERT REVERB ATTIVO")) stopInsertReverbTest()
            }
            .setNeutralButton("COPIA DETTAGLI") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FREQUALIZER Shizuku Reverb", message))
                Toast.makeText(this, "Dettagli copiati", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("LASCIA ATTIVO", null)
            .show()
    }

    private fun stopInsertReverbTest() {
        try { shizukuReverbService?.stopReverb() } catch (_: Throwable) {}
        try { Shizuku.unbindUserService(shizukuUserServiceArgs, shizukuServiceConnection, true) } catch (_: Throwable) {}
        shizukuReverbService = null
        try { insertReverbTest?.enabled = false } catch (_: Throwable) {}
        try { insertReverbTest?.release() } catch (_: Throwable) {}
        insertReverbTest = null
    }

    private fun connectAudio(){
        if(Build.VERSION.SDK_INT<28){status.text="Dynamics Processing richiede Android 9 o superiore";return}
        try{
            val cfg=DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,2,true,4,true,1,true,3,limiterOn).build()
            dpEffect=DynamicsProcessing(0,0,cfg).also{it.enabled=false}
            try { reverbEffect=EnvironmentalReverb(0,0).also{it.enabled=false} } catch(_:Throwable) { reverbEffect=null }
            audioReady=true;status.text="Audio engine disponibile • verifica uscita globale sul dispositivo";applyDsp()
        }catch(t:Throwable){audioReady=false;status.text="Effetto globale non disponibile su questo dispositivo"}
    }

    private fun applyDsp(){ scheduleUpdate() }

    private fun applyDspNow(){
        val fx=dpEffect?:return
        if(Build.VERSION.SDK_INT<28)return
        try{
            val eqFreqs = if(eqDoubleMid) floatArrayOf(100f,250f,2500f,10000f) else floatArrayOf(250f,4000f,20000f)
            val eqCount = if(eqDoubleMid) 4 else 3
            for(ch in 0..1){
                for(i in 0 until eqCount){
                    val valueIndex = if(eqDoubleMid) listOf(0,1,3,2)[i] else i
                    var eqGain=if(eqOn && eqControlOn[valueIndex])eq[valueIndex].coerceIn(-14f,14f) else 0f
                    val hpfActive = filtersOn && filters[0]>.01f
                    if(hpfActive && valueIndex==0) {
                        // Keep the original EQ layout, but turn its LOW band into
                        // a cutoff-tracking resonant peak while HPF resonance is raised.
                        // The boost offsets the HPF attenuation so the kick remains audible.
                        eqGain -= filters[0]*36f
                        eqGain += 3.2f * resonance.coerceIn(0f,1f).pow(1.8f)
                    }
                    val bandFrequency = if(hpfActive && resonance>.001f && valueIndex==0)
                        20f * Math.pow(50.0, filters[0].toDouble()).toFloat()
                    else eqFreqs[i]
                    fx.setPreEqBandByChannelIndex(ch,i,DynamicsProcessing.EqBand(true,bandFrequency,eqGain.coerceIn(-36f,12f)))
                }
                val isoFreqs=floatArrayOf(250f,4000f,20000f)
                for(i in 0..2){
                    val isoGain=if(isoOn && isoControlOn[i])iso[i].coerceIn(-24f,24f) else 0f
                    fx.setPostEqBandByChannelIndex(ch,i,DynamicsProcessing.EqBand(true,isoFreqs[i],isoGain))
                }
            }
            // More pronounced, rounded tone curves. Bass boost adds weight and punch;
            // loudness adds bass body plus a restrained upper presence lift.
            val bassBoostDb = 8f * (bassBoost.coerceIn(0f,100f)/100f).pow(1.4f)
            val masterLow=(if(bassBoostOn)bassBoostDb else 0f)+(if(loudnessOn)loudness/100f*12f else 0f)
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
            // Smooth single-knob compressor curve: at 0% the band is neutral;
            // as COMP rises, compression ratio and make-up gain both increase
            // continuously, reaching the tuned 3:1 / +12 dB setting at 100%.
            // Attack, release and threshold remain fixed for consistent behavior.
            val compLevel = (compMakeup.coerceIn(0f,100f) / 100f)
            val compActive = compressorOn && compLevel > 0f
            val compRatioNow = 1f + 2f * compLevel
            val makeupDb = 12f * compLevel
            val compBand = DynamicsProcessing.MbcBand(
                true,20000f,10f,150f,
                if(compActive) compRatioNow else 1f,
                -18f,
                6f,-90f,1f,0f,
                if(compActive) makeupDb else 0f
            )
            for(ch in 0..1) fx.setMbcBandByChannelIndex(ch,0,compBand)
            if(limiterOn){
                val lim=DynamicsProcessing.Limiter(true,true,0,5f,80f,20f,-1f,0f)
                fx.setLimiterAllChannelsTo(lim)
            }
            fx.enabled=appEnabled && (eqOn||isoOn||filtersOn||abs(gain)>.01f||limiterOn||bassBoostOn||loudnessOn||compressorOn)
            reverbEffect?.let { rev ->
                try {
                    rev.setRoomLevel((-1000).toShort())
                    rev.setRoomHFLevel((-1500).toShort())
                    rev.setDecayTime(reverbDecay)
                    rev.setDecayHFRatio(reverbDamping.toShort())
                    rev.setDiffusion(reverbSize.toShort())
                    rev.setDensity(reverbSize.toShort())
                    rev.setReverbLevel((-9000 + reverbMix * 85).coerceIn(-9000,0).toShort())
                    rev.enabled = appEnabled && reverbOn && reverbMix > 0
                } catch(_:Throwable) { runOnUiThread { if(::status.isInitialized) status.text="REVERB non supportato dal driver audio" } }
            }

            val message=if(!appEnabled)"APP OFF • BYPASS" else if(fx.enabled)"Processing ON • EQ / ISO / COMP" else "BYPASS"
            runOnUiThread { if(::status.isInitialized) status.text=message }
        }catch(t:Throwable){runOnUiThread { if(::status.isInitialized) status.text="Audio DSP non supportato dal driver del dispositivo" }}
    }

    private fun showOptions(){
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(4))}
        val names=listOf("Controlli EQ","Controlli Isolator","Controlli Filtri","Controlli Gain","Personalizza colori")
        names.forEachIndexed{index,name->
            val b=Button(this).apply{text=name;textSize=13f;setOnClickListener{
                when(index){
                    in 0..3->{
                        val key=listOf("EQ","ISOLATOR","FILTERS","GAIN")[index]
                        val opts=arrayOf("Manopole rotative","Slider")
                        AlertDialog.Builder(this@MainActivity).setTitle(key+" • tipo controllo").setSingleChoiceItems(opts,if(modes[key]==true)1 else 0){a,c->modes[key]=c==1;save();refreshUi();applyDsp();a.dismiss()}.setNegativeButton("Annulla",null).show()
                    }
                    4->showColorOptions()
                }
            }}
            panel.addView(b,LinearLayout.LayoutParams(-1,dp(44)))
        }
        val defaultsRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(6),dp(8),dp(4))}
        defaultsRow.addView(label("DEFAULT SETTINGS ALL'AVVIO",13f,Color.WHITE),LinearLayout.LayoutParams(0,dp(46),1f))
        val defaultsSwitch=Switch(this).apply{
            isChecked=defaultSettingsOnStart
            setOnCheckedChangeListener{_,checked->defaultSettingsOnStart=checked;save()}
        }
        defaultsRow.addView(defaultsSwitch)
        panel.addView(defaultsRow)
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
        val wheelTitle=label("ROTELLINA MOUSE • VELOCITÀ POTENZIOMETRI",13f,Color.WHITE)
        panel.addView(wheelTitle,LinearLayout.LayoutParams(-1,dp(34)))
        val wheelRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(2),dp(8),dp(2))}
        wheelRow.addView(label("Attiva regolazione con rotellina",13f,Color.WHITE),LinearLayout.LayoutParams(0,dp(48),1f))
        val wheelSwitch=Switch(this).apply{
            isChecked=mouseWheelAdjustOn
            setOnCheckedChangeListener{_,checked->mouseWheelAdjustOn=checked;save()}
        }
        wheelRow.addView(wheelSwitch);panel.addView(wheelRow)
        val wheelValue=label("Velocità: ${mouseWheelSensitivity.roundToInt()}x",12f,gold)
        panel.addView(wheelValue,LinearLayout.LayoutParams(-1,dp(28)))
        val wheelSeek=SeekBar(this).apply{
            max=9;progress=(mouseWheelSensitivity.roundToInt()-1).coerceIn(0,9);isEnabled=mouseWheelAdjustOn
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seek:SeekBar?,progress:Int,fromUser:Boolean){
                    if(fromUser){mouseWheelSensitivity=(progress+1).toFloat();wheelValue.text="Velocità: ${mouseWheelSensitivity.roundToInt()}x";save()}
                }
                override fun onStartTrackingTouch(seek:SeekBar?){}
                override fun onStopTrackingTouch(seek:SeekBar?){}
            })
        }
        panel.addView(wheelSeek,LinearLayout.LayoutParams(-1,dp(42)))
        wheelSwitch.setOnCheckedChangeListener{_,checked->
            mouseWheelAdjustOn=checked;wheelSeek.isEnabled=checked;save()
        }
        val doubleMidRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(4))}
        doubleMidRow.addView(label("EQ • DOPPIO MEDIO",13f,Color.WHITE),LinearLayout.LayoutParams(0,dp(48),1f))
        val doubleMidSwitch=Switch(this).apply{isChecked=eqDoubleMid;setOnCheckedChangeListener{_,checked->eqDoubleMid=checked;save();refreshUi();applyDsp()}}
        doubleMidRow.addView(doubleMidSwitch);panel.addView(doubleMidRow)
        AlertDialog.Builder(this).setTitle("OPTIONS").setView(ScrollView(this).apply{addView(panel)}).setNegativeButton("CHIUDI",null).show()
    }
    private fun rebuildEffect(){
        try{dpEffect?.release()}catch(_:Throwable){}
        dpEffect=null;connectAudio()
    }
    private fun fmt(v:Float)="%.1f".format(v)
    override fun onDestroy(){
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) } catch (_: Throwable) {}
        // Clear the background marker only for an intentional Activity finish.
        // Do not clear it when Android destroys a background process or rotates the device.
        if (isFinishing) prefs.edit().putBoolean("session_in_background", false).apply()
        stopInsertReverbTest()
        try{dspHandler.removeCallbacksAndMessages(null);dspThread.quitSafely();dpEffect?.enabled=false;dpEffect?.release();reverbEffect?.enabled=false;reverbEffect?.release()}catch(_:Throwable){}
        super.onDestroy()
    }
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
    private val onEnd:()->Unit = {},
    private val titleInside:Boolean = false,
    private val wheelMultiplier:()->Float = {1f},
    private val portraitDialFit:Boolean = false,
    private val bottomTextLift:Float = 0f
):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var v=initial
    private var downY=0f
    private var downV=initial
    private var dragged=false
    private val density get()=resources.displayMetrics.density
    private fun dp(x:Float)=x*density
    private val rect get()={
        // In tablet portrait double-mid EQ, reserve a dedicated lower text lane
        // for title and value. The dial itself is sized from the remaining square
        // area, so the LOW face cannot be squeezed into an oval or cut by its labels.
        val dialTextReserve=if(portraitDialFit) dp(27f) else dp(18f)
        val side=min(width.toFloat(),(height.toFloat()-dialTextReserve).coerceAtLeast(1f))* .94f
        val cy=if(portraitDialFit) (height-dp(27f))/2f else (height-dp(26f))/2f
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
        p.textAlign=Paint.Align.CENTER
        val titleSize=min(dp(11f),r.width()*.86f/(title.length.coerceAtLeast(1)*.58f))
        p.textSize=titleSize.coerceAtLeast(dp(5.5f));p.color=Color.WHITE
        if(titleInside){p.textAlign=Paint.Align.CENTER;c.drawText(title,cx,cy+rad*.48f,p)}else{c.drawText(title,cx,height-dp(17f+bottomTextLift),p)}
        val value=valueText()
        p.color=accent;p.textSize=min(dp(10f),r.width()*.86f/(value.length.coerceAtLeast(1)*.58f)).coerceAtLeast(dp(5.5f));c.drawText(value,cx,height-dp(4f+bottomTextLift),p)
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
            v=(v+e.getAxisValue(MotionEvent.AXIS_VSCROLL)*(maxV-minV)*.012f*wheelMultiplier().coerceIn(1f,10f)).coerceIn(minV,maxV)
            changed(v);invalidate();onEnd();return true
        }
        return super.onGenericMotionEvent(e)
    }
    override fun performClick():Boolean{super.performClick();return true}
}

// Experimental master compressor prototype: Android DynamicsProcessing MBC.

