package com.example.rotaryisolator

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.media.audiofx.DynamicsProcessing
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
    // TEST252 diffuse parallel reverb engine.
    @Volatile private var reverbOn = false
    @Volatile private var reverbMix = 0
    @Volatile private var reverbSize = 950
    @Volatile private var reverbHpHz = 420
    @Volatile private var reverbLpHz = 14000
    @Volatile private var reverbFadeMs = 1400
    @Volatile private var reverbFeedbackPercent = 50
    @Volatile private var reverbDecayPercent = 50
    @Volatile private var reverbColorPercent = 50
    @Volatile private var delayOn = false
    @Volatile private var captureConsentLocked = false
    @Volatile private var delayFadeMs = 1400
    @Volatile private var delayDivision = 3 // 1/4, 1/2, 3/4, 1, 2, 4, 8 beats
    @Volatile private var delayMode = 2 // 0 SHORT DELAY, 1 LONG DELAY, 2 DUB ECHO
    @Volatile private var selectedBeatFx = 2 // 0 SHORT, 1 LONG, 2 DUB ECHO, 3 REVERB
    @Volatile private var delayMix = 0
    @Volatile private var delayTimePercent = 100
    @Volatile private var delayFeedbackPercent = 52
    @Volatile private var delayTonePercent = 50
    @Volatile private var fxFrequency = 1 // LOW, MID, HIGH
    @Volatile private var delayBpm = 120f
    @Volatile private var delayTapBpm = 120f
    @Volatile private var delayBpmCorrection = 0f
    @Volatile private var autoBpmEnabled = false
    @Volatile private var delaySyncOffsetMs = 0f
    @Volatile private var autoBpmNextBeatMs = 0L
    private var autoBpmHasEstimate = false
    private var autoBpmHistory = ArrayDeque<Float>() // 10 ms bass-onset frames
    private var autoBpmEstimateHistory = ArrayDeque<Float>() // median filter for noisy frame estimates
    @Volatile private var bpmSyncLocked = false
    @Volatile private var bpmSyncLockedValue = 120f
    private var autoBpmPreviousBass = 0f
    private var autoBpmLowpassState = 0f
    private var autoBpmLastAnalysisMs = 0L
    private var bpmUiLabel: TextView? = null
    private var autoBpmButton: TextView? = null
    private var lastDelayTapMs = 0L
    private val delayTapIntervals = ArrayDeque<Long>()
    private var captureProjection: android.media.projection.MediaProjection? = null
    // Tracks an intentional stop so MediaProjection.Callback.onStop does not
    // mistake our own cleanup for an Android permission revocation.
    private var intentionallyStoppingProjection: android.media.projection.MediaProjection? = null
    private var captureRecord: android.media.AudioRecord? = null
    private var captureTrack: android.media.AudioTrack? = null
    private var parallelReverbProcessor: SchroederReverb? = null
    private var captureThread: Thread? = null
    @Volatile private var captureRunning = false
    @Volatile private var captureRestartAttempts = 0
    @Volatile private var captureRestartPending = false
    private var captureDialog: AlertDialog? = null
    private var captureStatusView: TextView? = null
    private val captureDiagLock = Any()
    private val captureDiagnostics = ArrayDeque<String>()
    private fun captureDiag(message: String) {
        val line = "t+${android.os.SystemClock.elapsedRealtime()}ms $message"
        synchronized(captureDiagLock) {
            captureDiagnostics.addLast(line)
            while (captureDiagnostics.size > 100) captureDiagnostics.removeFirst()
        }
        android.util.Log.i("FREQUALIZER_CAPTURE", line)
    }
    private fun captureDiagSnapshot(): String = synchronized(captureDiagLock) {
        captureDiagnostics.joinToString("\\n")
    }
    private val captureRequestCode = 7420
    private val capturePermissionRequestCode = 7421
    private var audioReady = false
    private var appEnabled = true
    private var defaultSettingsOnStart = true
    private var compact = false
    private var uiScale = 1.0f
    // Uniform proportional scaling for the phone landscape layout; tablet geometry stays untouched.
    private var phoneUiScale = 1.0f
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
    private var mouseWheelSensitivity = 1f
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
        // Explicitly follow the physical sensor in both portrait and landscape.
        // UNSPECIFIED can remain in the user's preferred orientation on some tablets.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        appEnabled = prefs.getBoolean("app_enabled", true)
        reverbOn = prefs.getBoolean("reverb_on", false)
        reverbMix = prefs.getInt("reverb_mix", 0).coerceIn(0,100)
        reverbSize = prefs.getInt("reverb_size_t2", 950).coerceIn(0,1000)
        reverbFeedbackPercent = prefs.getInt("reverb_feedback", 50).coerceIn(0,100)
        reverbDecayPercent = prefs.getInt("reverb_decay", 50).coerceIn(0,100)
        reverbColorPercent = prefs.getInt("reverb_color", 50).coerceIn(0,100)
        delayMix = 0 // Il livello Delay parte sempre chiuso all’avvio dell’app.
        delayTimePercent = prefs.getInt("beat_fx_delay_time", 100).coerceIn(50,200)
        delayFeedbackPercent = prefs.getInt("beat_fx_delay_feedback", 52).coerceIn(0,90)
        delayTonePercent = prefs.getInt("beat_fx_delay_tone", 50).coerceIn(0,100)
        fxFrequency = prefs.getInt("beat_fx_frequency", 1).coerceIn(0,2)
        delayOn = prefs.getBoolean("dub_delay_on", false)
        delayFadeMs = prefs.getInt("dub_delay_fade_ms", 1400).coerceIn(300,6000)
        delayDivision = prefs.getInt("dub_delay_division", 3).coerceIn(0,6)
        delayMode = prefs.getInt("dub_delay_mode", 2).coerceIn(0,2)
        delayBpmCorrection = prefs.getFloat("reverb_delay_bpm_correction", 0f).coerceIn(-10f,10f)
        autoBpmEnabled = prefs.getBoolean("auto_bpm_enabled", false)
        delayBpm = (prefs.all["reverb_delay_bpm"] as? Number)?.toFloat()?.coerceIn(60f,180f) ?: 120f
        delayTapBpm = prefs.getFloat("reverb_delay_tap_bpm", (delayBpm - delayBpmCorrection).coerceIn(60f,180f)).coerceIn(60f,180f)
        delayBpm = (delayTapBpm + delayBpmCorrection).coerceIn(60f,180f)
        selectedBeatFx = prefs.getInt("beat_fx_selected", delayMode).coerceIn(0,2)
        val fadePlusThreeSecondsApplied = prefs.getBoolean("reverb_fade_plus3sec_v1", false)
        reverbFadeMs = (prefs.getInt("reverb_fade_ms", 1400) + if (fadePlusThreeSecondsApplied) 0 else 3000).coerceIn(300, 4000)
        if (!fadePlusThreeSecondsApplied) prefs.edit().putInt("reverb_fade_ms", reverbFadeMs).putBoolean("reverb_fade_plus3sec_v1", true).apply()
        val fadePlusTwoSecondsApplied = prefs.getBoolean("reverb_fade_plus2sec_v2", false)
        reverbFadeMs = (reverbFadeMs + if (fadePlusTwoSecondsApplied) 0 else 2000).coerceIn(300, 6000)
        if (!fadePlusTwoSecondsApplied) prefs.edit().putInt("reverb_fade_ms", reverbFadeMs).putBoolean("reverb_fade_plus2sec_v2", true).apply()
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
        phoneUiScale = if (phonePortrait && wideLayout) min(windowWidthDp / 1280f, windowHeightDp / 800f).coerceIn(0.42f, 1f) else 1f
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
        // v3 sets the same one-point-per-detent response for every rotary dial.
        // Preserve the enable/disable choice, but reset the old 5x default once.
        val mouseDefaultsV3Applied = prefs.getBoolean("mouse_defaults_v3", false)
        mouseWheelAdjustOn = if (mouseDefaultsV3Applied) prefs.getBoolean("mouse_wheel_adjust_on", true) else true
        mouseWheelSensitivity = if (mouseDefaultsV3Applied) prefs.getFloat("mouse_wheel_sensitivity", 1f).coerceIn(0.7f,1.3f) else 1f
        if (!mouseDefaultsV3Applied) prefs.edit()
            .putBoolean("mouse_defaults_v3", true)
            .putBoolean("mouse_wheel_adjust_on", true)
            .putFloat("mouse_wheel_sensitivity", 1f)
            .putBoolean("mini", false)
            .apply()
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
        // Use the Android task ID to distinguish a genuinely new app launch
        // (the user closed/removed the previous task) from Android restoring
        // the same task after background process reclamation.
        val previousTaskId = prefs.getInt("session_task_id", -1)
        val isNewTask = previousTaskId != -1 && previousTaskId != taskId
        val returningFromBackground = prefs.getBoolean("session_in_background", false) && !isNewTask
        if (defaultSettingsOnStart && savedInstanceState == null && !returningFromBackground) applyStartupDefaults()
        prefs.edit().putInt("session_task_id", taskId).putBoolean("session_in_background", false).apply()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val w = newConfig.screenWidthDp
        val h = newConfig.screenHeightDp
        if (w > 0 && h > 0) {
            phonePortrait = min(w, h) < 600
            wideLayout = w > h
        }
        lastWindowWidthPx = 0
        buildUi()
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
        reverbMix=0
        for(i in 0..2){previousFilters[i]=0f;sectionPreviousFilters[i]=0f}
        gain=0f;compMakeup=0f;compressorOn=false
        bassBoost=0f;loudness=0f;bassBoostOn=false;loudnessOn=false
    }

    private fun dp(v:Int)= (v*resources.displayMetrics.density*phoneUiScale).roundToInt()
    private fun windowHeightDp():Float {
        if (::container.isInitialized && container.height > 0) return container.height / resources.displayMetrics.density
        val h=resources.configuration.screenHeightDp
        return if(h>0)h.toFloat() else resources.displayMetrics.heightPixels/resources.displayMetrics.density
    }
    private fun dialHeight():Int {
        val h=windowHeightDp()
        // Keep the tablet's original dial geometry on landscape phones too;
        // the complete interface is then uniformly scaled to the phone window.
        val value=when {
            wideLayout -> 168
            phonePortrait -> (((h-64f)/2f)-104f).roundToInt().coerceIn(40,148)
            else -> 116
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
        // Tablet portrait uses weighted section rows sized to the available
        // window. The recursive intrinsic-height estimate double-counts those
        // weighted children and used to shrink the entire interface, leaving
        // unused space at the bottom. Keep native scale in this orientation.
        val tabletPortraitLayout = !phonePortrait && !wideLayout
        if (!tabletPortraitLayout) {
            for (section in sections.values) {
                if (section.height <= 0) continue
                val required = requiredContentHeight(section)
                if (required > 0) scale = min(scale, section.height.toFloat() / required.toFloat())
            }
            scale = scale.coerceAtMost(1f).coerceAtLeast(0.1f)
        }
        root.pivotX = 0f
        root.pivotY = 0f
        root.scaleX = scale
        root.scaleY = scale
        root.translationX = (container.width - root.width * scale) / 2f
        root.translationY = 0f
    }

    private fun label(t:String, size:Float=11f, color:Int=Color.LTGRAY)=TextView(this).apply {
        text=t; textSize=size*phoneUiScale; setTextColor(color); gravity=Gravity.CENTER; letterSpacing=.08f
    }
    private fun sectionLabel(t:String, size:Float, color:Int)=label(t,size,color).apply {
        setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(t:String, action:()->Unit)=Button(this).apply { text=t; setOnClickListener { action() } }
    private fun redButton(t:String, active:Boolean=true, action:()->Unit)=Button(this).apply { text=t; textSize=10f; setTextColor(Color.WHITE); backgroundTintList=ColorStateList.valueOf(if(active) Color.rgb(190,35,42) else Color.rgb(105,35,40)); setOnClickListener { action() } }
    private fun circleAction(symbol:String, diameter:Int, action:()->Unit):View = TextView(this).apply {
        text=symbol; textSize=(if(diameter>=48)16f else 11f)*phoneUiScale; setTextColor(Color.WHITE)
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
                text=symbol;gravity=Gravity.CENTER;textSize=(if(symbol=="F")9f else 11f)*phoneUiScale
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
                text=caption;textSize=(if(narrowPhone)8f else 9f)*phoneUiScale;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL
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
        textSize=11f*phoneUiScale;isAllCaps=false;setTextColor(Color.WHITE);minHeight=dp(30);minimumHeight=dp(30)
        setPadding(dp(3),0,dp(3),0);paintState();contentDescription="Interruttore generale audio"
        setOnClickListener { appEnabled=!appEnabled;paintState();save();scheduleUpdate() }
    }

    private fun save() {
        prefs.edit().putBoolean("app_enabled",appEnabled).putBoolean("mini",compact).putInt("master_mode",masterMode).putFloat("bass_boost",bassBoost).putFloat("loudness",loudness).putBoolean("bass_boost_on",bassBoostOn).putBoolean("loudness_on",loudnessOn).putFloat("previous_bass_boost",previousBassBoost).putFloat("previous_loudness",previousLoudness).putBoolean("limiter",limiterOn).putBoolean("mouse_wheel_adjust_on",mouseWheelAdjustOn).putFloat("mouse_wheel_sensitivity",mouseWheelSensitivity)
            .putBoolean("compressor_on",compressorOn).putFloat("comp_threshold",compThreshold).putFloat("comp_ratio",compRatio).putFloat("comp_attack",compAttack).putFloat("comp_release",compRelease).putFloat("comp_makeup",compMakeup)
            .putInt("color_eq",colorEq).putInt("color_iso",colorIso).putInt("color_filters",colorFilters).putInt("color_master",colorMaster).putInt("color_buttons",colorButtons).putInt("color_background",colorBackground).putInt("color_titles",colorTitles).putInt("color_options",colorOptions)
            .putBoolean("eq_on",eqOn).putBoolean("iso_on",isoOn).putBoolean("filters_on",filtersOn).putFloat("gain",gain).putFloat("ui_scale",uiScale)
            .putBoolean("default_settings_on_start",defaultSettingsOnStart)
            .putBoolean("reverb_on",reverbOn).putInt("reverb_fade_ms",reverbFadeMs).putInt("reverb_mix",reverbMix).putInt("reverb_size_t2",reverbSize).putInt("reverb_feedback",reverbFeedbackPercent).putInt("reverb_decay",reverbDecayPercent).putInt("reverb_color",reverbColorPercent).putInt("reverb_hp_hz",reverbHpHz).putInt("reverb_lp_hz",reverbLpHz).putInt("reverb_delay_mix",delayMix).putInt("beat_fx_delay_time",delayTimePercent).putInt("beat_fx_delay_feedback",delayFeedbackPercent).putInt("beat_fx_delay_tone",delayTonePercent).putInt("beat_fx_frequency",fxFrequency).putFloat("reverb_delay_bpm",delayBpm).putFloat("reverb_delay_tap_bpm",delayTapBpm).putFloat("reverb_delay_bpm_correction",delayBpmCorrection).putBoolean("auto_bpm_enabled",autoBpmEnabled).putBoolean("dub_delay_on",delayOn).putInt("dub_delay_fade_ms",delayFadeMs).putInt("dub_delay_division",delayDivision).putInt("dub_delay_mode",delayMode).putInt("beat_fx_selected",selectedBeatFx)
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
        val compactPhone = phonePortrait && !wideLayout
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(if(compactPhone)3 else 8),dp(if(compactPhone)1 else 4),dp(if(compactPhone)3 else 8),dp(if(compactPhone)1 else 4));setBackgroundColor(bg);clipChildren=false;clipToPadding=false}
        val titleBar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        titleBar.addView(appPowerControl(),LinearLayout.LayoutParams(dp(if(compactPhone)72 else 82),dp(if(compactPhone)30 else 36)).apply{rightMargin=dp(5)})
        titleBar.addView(gearButton(),LinearLayout.LayoutParams(dp(54),dp(if(compactPhone)36 else 42)).apply{leftMargin=dp(5)})
        root.addView(titleBar)
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false}
        // Tutte le sezioni restano nella stessa schermata: nessuno scrolling.
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        sections.clear()
        // Tablet portrait gets a dedicated vertical composition. Landscape and phone
        // layouts retain the established four-quadrant structure unchanged.
        if (!phonePortrait && !wideLayout) {
            val upper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            val middle = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            val fxHost = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            // EQ + Isolator share the upper band; FILTER + MASTER form a compact
            // middle strip; REVERB + DELAY get the full width in the lower band.
            content.addView(upper, LinearLayout.LayoutParams(-1, 0, 0.34f))
            content.addView(middle, LinearLayout.LayoutParams(-1, 0, 0.36f))
            content.addView(fxHost, LinearLayout.LayoutParams(-1, 0, 0.30f))
            addSection("EQ", "EQ", eq, -14f..14f, { eqOn }, upper)
            addSection("ISOLATOR", "ISOLATOR", iso, -24f..24f, { isoOn }, upper)
            addFilterSection(middle)
            addGainSection(middle)
            addEffectsSection(fxHost)
        } else {
            // Fixed four-quadrant mixer in every orientation: only the available
            // dimensions change, never the order or the set of controls.
            val upper=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;clipChildren=false;clipToPadding=false}
            val lower=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;clipChildren=false;clipToPadding=false}
            content.addView(upper,LinearLayout.LayoutParams(-1,0,0.94f))
            content.addView(lower,LinearLayout.LayoutParams(-1,0,1.06f))
            addSection("EQ","EQ",eq,-14f..14f,{eqOn},upper)
            addSection("ISOLATOR","ISOLATOR",iso,-24f..24f,{isoOn},upper)
            val lowerLeft=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;clipChildren=false;clipToPadding=false}
            lower.addView(lowerLeft,LinearLayout.LayoutParams(0,-1,1f))
            addFilterSection(lowerLeft)
            addGainSection(lowerLeft)
            addEffectsSection(lower)
    
        }
        status=label("Connecting audio engine…",if(compactPhone)7f else 9f)
        root.addView(status,LinearLayout.LayoutParams(-1,dp(if(compactPhone)13 else 18)))
        container=FrameLayout(this).apply{clipChildren=true;clipToPadding=true}
        container.addView(root,FrameLayout.LayoutParams(-1,-1,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(container)
        container.addOnLayoutChangeListener { view,_,_,_,_,_,_,_,_ ->
            if (view.width > 0 && view.height > 0) {
                val newWide = view.width > view.height
                val density = resources.displayMetrics.density
                val newPhone = min(view.width,view.height) / density < 600f
                val newPhoneScale = if(newPhone && newWide) min((view.width/density)/1280f,(view.height/density)/800f).coerceIn(0.42f,1f) else 1f
                val scaleChanged = abs(newPhoneScale-phoneUiScale) > 0.01f
                val resized = lastWindowWidthPx > 0 &&
                    (abs(view.width-lastWindowWidthPx) > dp(12) || abs(view.height-lastWindowHeightPx) > dp(12))
                lastWindowWidthPx=view.width
                lastWindowHeightPx=view.height
                if (newWide != wideLayout || newPhone != phonePortrait || resized || scaleChanged) {
                    phoneUiScale = newPhoneScale
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
        val compactHeader = phonePortrait && !wideLayout
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.TOP;setPadding(dp(if(compactHeader)1 else 4),dp(if(compactHeader)1 else 4),dp(if(compactHeader)1 else 4),dp(1))}
        val actionReturnSize=if(compactHeader)18 else 42
        val actionFlatSize=if(compactHeader)22 else 56
        val actionGap=if(compactHeader)1 else 8
        val actionHeight=if(compactHeader)24 else 58
        box.addView(sectionLabel(title,if(compactHeader)11f else 13f,titleTint(sectionAccent)),LinearLayout.LayoutParams(-1,dp(if(compactHeader)24 else 32)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        val header=LinearLayout(this).apply{orientation=if(compactHeader)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val headerControls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        box.addView(header)
        // In tablet portrait, stack LOW / MID / HIGH as full-width mixer strips.
        // Phone layouts keep their compact three-column arrangement.
        val useVerticalRows=!wideLayout
        // Size portrait strips from their actual count: EQ has four rows in
        // double-mid mode, while the Isolator has three. This prevents the last
        // strip from being clipped by the quadrant boundary.
        val indices = if(key=="EQ" && eqDoubleMid) listOf(2,3,1,0) else listOf(2,1,0)
        // Tablet portrait uses measured, weighted rows instead of guessed dp heights.
        // Every band receives exactly the same share of the real section height.
        val verticalRowHeight = ((windowHeightDp()*0.35f-76f)/indices.size).roundToInt().coerceIn(48,112)
        val verticalDialHeight = if(useVerticalRows) dp(verticalRowHeight) else dialHeight()
        val row=LinearLayout(this).apply{orientation=if(useVerticalRows)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=if(useVerticalRows)0f else if(key=="EQ" && eqDoubleMid)4f else 3f}
        val names = if(key=="EQ" && eqDoubleMid) listOf("HIGH","MID HIGH","MID LOW","LOW") else listOf("HIGH","MID","LOW")
        for(pos in indices.indices){
            val i=indices[pos]
            val col=LinearLayout(this).apply{orientation=if(useVerticalRows)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val vLabel=label("${names[pos]}  ${fmt(values[i])} dB", if(compactHeader && key=="EQ" && eqDoubleMid)7f else if(compactHeader)8.5f else if(compact)10f else 12f,Color.WHITE)
            val controlHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            if(useVerticalRows) vLabel.gravity=Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            controlHeader.addView(vLabel,if(useVerticalRows)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(0,dp(if(compactHeader)19 else 28),1f))
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
            textSize=(if(compactHeader)8f else 9f)*phoneUiScale; isAllCaps=false; setTextColor(Color.WHITE)
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
        val compactFilter = phonePortrait && !wideLayout
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.TOP;setPadding(dp(if(compactFilter)1 else 4),dp(4),dp(if(compactFilter)1 else 4),dp(1))}
        val filterAccent = accentFor("FILTERS")
        val stackedTabletLandscape = !phonePortrait && wideLayout
        val useVerticalRows = !wideLayout || stackedTabletLandscape
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
            box.addView(sectionLabel("HPF",if(compactFilter)11f else 13f,titleTint(filterAccent)),LinearLayout.LayoutParams(-1,dp(if(compactFilter)24 else 32)))
            val filterTitle=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            filterTitle.addView(filterPower)
            box.addView(filterTitle,LinearLayout.LayoutParams(-1,dp(44)))
        }
        // Match EQ: use the available quadrant height rather than a fixed 138dp strip.
        val verticalRowHeight = ((windowHeightDp()*0.36f-76f)/2f).roundToInt().coerceIn(50,140)
        val verticalDialHeight = if(useVerticalRows) dp((verticalRowHeight-6).coerceAtLeast(44)) else dialHeight()
        // Enlarge only the two FILTER dials on tablet landscape; leave every other control unchanged.
        val filterLandscapeDialHeight = if(wideLayout) dp(210) else dialHeight()
        val row=LinearLayout(this).apply{orientation=if(useVerticalRows)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;weightSum=if(useVerticalRows)0f else 2f}
        val names=listOf("HPF","RESONANCE")
        for(i in 0..1){
            val col=LinearLayout(this).apply{orientation=if(stackedTabletLandscape)LinearLayout.VERTICAL else if(useVerticalRows)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val current=if(i==1)resonance else filters[0]
            val filterReturnSize=if(stackedTabletLandscape)22 else if(compactFilter)18 else 42
            val filterFlatSize=if(stackedTabletLandscape)26 else if(compactFilter)22 else 56
            val filterActionGap=if(stackedTabletLandscape)4 else if(compactFilter)1 else 8
            val filterActionHeight=if(stackedTabletLandscape)30 else if(compactFilter)24 else 58
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
                if(stackedTabletLandscape && i==1){knob.scaleX=0.78f;knob.scaleY=0.78f}
                // Make the HPF dial more prominent on tablet portrait;
                // resonance keeps its original size.
                if (useVerticalRows && i == 0) { knob.scaleX = 1.04f; knob.scaleY = 1.04f }
                if (useVerticalRows && i == 1) { knob.scaleX = 0.72f; knob.scaleY = 0.72f }
                controlRow.addView(knob,if(stackedTabletLandscape) LinearLayout.LayoutParams(-1,-1) else if(useVerticalRows) LinearLayout.LayoutParams(-1,-1) else LinearLayout.LayoutParams(0,if(wideLayout) filterLandscapeDialHeight else verticalDialHeight,1f))
            }
            col.addView(controlRow,if(stackedTabletLandscape)LinearLayout.LayoutParams(-1,0,1f) else if(useVerticalRows)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,if(wideLayout) filterLandscapeDialHeight else dialHeight()))
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
            col.addView(filterActions,if(stackedTabletLandscape)LinearLayout.LayoutParams(-1,dp(30)) else if(useVerticalRows)LinearLayout.LayoutParams(dp(filterReturnSize+filterFlatSize+filterActionGap),-1) else LinearLayout.LayoutParams(-1,dp(filterActionHeight)))
            row.addView(col,if(useVerticalRows)LinearLayout.LayoutParams(-1,0,1f) else LinearLayout.LayoutParams(0,-2,1f))
        }
        box.addView(row,if(useVerticalRows) LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(4)} else LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(if(compactFilter)4 else 7)});parent.addView(box,if(parent===content) LinearLayout.LayoutParams(-1,-2) else LinearLayout.LayoutParams(0,-1,1f));sections["FILTERS"]=box
    }

    private fun addGainSection(parent:LinearLayout=content){
        val masterAccent = accentFor("MASTER")
        val tabletPortrait = !phonePortrait && !wideLayout
        val compactMaster = phonePortrait && !wideLayout
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.TOP;setPadding(dp(if(compactMaster)1 else 4),dp(4),dp(if(compactMaster)1 else 4),dp(1))}
        box.addView(sectionLabel("MASTER",if(compactMaster)11f else 13f,titleTint(masterAccent)),LinearLayout.LayoutParams(-1,dp(if(compactMaster)24 else 32)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        val tabletLandscape = !phonePortrait && wideLayout
        if(tabletLandscape){
            // Tablet landscape: compact vertical MASTER controls beside the FILTER stack.
            val rowHeight = ((windowHeightDp()*0.53f-90f)/4f).roundToInt().coerceIn(48,90)
            fun landscapeMasterRow(dial:View, actions:List<View> = emptyList()):View {
                return LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER
                    setPadding(dp(2),dp(1),dp(2),dp(1))
                    addView(dial,LinearLayout.LayoutParams(0,dp(rowHeight-2),1f))
                    // Reserve the same right-hand action lane in all four rows,
                    // including GAIN (which has no ON/RETURN buttons). This keeps
                    // the four dial centers on one exact vertical axis.
                    val actionBox=LinearLayout(this@MainActivity).apply{
                        orientation=LinearLayout.HORIZONTAL
                        gravity=Gravity.CENTER
                        setPadding(0,0,0,0)
                        actions.forEachIndexed { index, action ->
                            addView(action,LinearLayout.LayoutParams(0,dp(26),1f).apply{if(index>0)leftMargin=dp(4)})
                        }
                    }
                    addView(actionBox,LinearLayout.LayoutParams(dp(144),-1))
                }.also{it.minimumHeight=dp(rowHeight)}
            }
            // Four equal-height MASTER rows: no extra spacer above GAIN.
            // All four dials share the same left axis and vertical sizing.
            val gainDial:View = if(modes["GAIN"]==true) SeekBar(this).apply {
                max=240;progress=((gain+12)*10).roundToInt()
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(b:SeekBar?,p:Int,user:Boolean){if(user){gain=p/10f-12f;scheduleUpdate()}}
                    override fun onStartTrackingTouch(b:SeekBar?){}
                    override fun onStopTrackingTouch(b:SeekBar?){save()}
                })
            } else RotaryControl(this,"GAIN",gain,-12f,12f,masterAccent,{v->gain=v;scheduleUpdate()},{fmt(gain)+" dB"},titleInside=false,wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            box.addView(landscapeMasterRow(gainDial),LinearLayout.LayoutParams(-1,dp(rowHeight)))
            val compDial=RotaryControl(this,"COMP",compMakeup,0f,100f,masterAccent,{v->compMakeup=v.coerceIn(0f,100f);scheduleUpdate()},{compMakeup.roundToInt().toString()+"%"},titleInside=false,wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val compToggle=roundAction("",if(compressorOn)"ON" else "OFF",compressorOn){compressorOn=!compressorOn;save();refreshUi();scheduleUpdate()}
            box.addView(landscapeMasterRow(compDial,listOf(compToggle)),LinearLayout.LayoutParams(-1,dp(rowHeight)))
            val bassDial=RotaryControl(this,"BASS BOOST",bassBoost,0f,100f,masterAccent,{v->bassBoost=v;scheduleUpdate()},{bassBoost.roundToInt().toString()+"%"},titleInside=false,onStart={previousBassBoost=bassBoost},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val bassToggle=roundAction("",if(bassBoostOn)"ON" else "OFF",bassBoostOn){bassBoostOn=!bassBoostOn;save();refreshUi();scheduleUpdate()}
            val bassReturn=roundAction("↶","RETURN",true){bassBoost=previousBassBoost;save();refreshUi();scheduleUpdate()}
            box.addView(landscapeMasterRow(bassDial,listOf(bassToggle,bassReturn)),LinearLayout.LayoutParams(-1,dp(rowHeight)))
            val loudDial=RotaryControl(this,"LOUDNESS",loudness,0f,100f,masterAccent,{v->loudness=v;scheduleUpdate()},{loudness.roundToInt().toString()+"%"},titleInside=false,onStart={previousLoudness=loudness},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val loudToggle=roundAction("",if(loudnessOn)"ON" else "OFF",loudnessOn){loudnessOn=!loudnessOn;save();refreshUi();scheduleUpdate()}
            val loudReturn=roundAction("↶","RETURN",true){loudness=previousLoudness;save();refreshUi();scheduleUpdate()}
            box.addView(landscapeMasterRow(loudDial,listOf(loudToggle,loudReturn)),LinearLayout.LayoutParams(-1,dp(rowHeight)))
        }else if(tabletPortrait){
            // Tablet portrait MASTER: compact 2x2 grid so all four controls
            // remain fully inside the middle band without clipping.
            val grid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            fun masterCell(dial:View, actions:List<View> = emptyList()):View {
                // Reduce all four MASTER rotary faces together so the 2x2
                // grid fits comfortably inside the middle strip.
                dial.scaleX = 0.76f
                dial.scaleY = 0.76f
                return LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(1), dp(2), dp(1))
                    addView(dial, LinearLayout.LayoutParams(0, -1, 1f))
                    if (actions.isNotEmpty()) {
                        val actionColumn = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            actions.forEachIndexed { index, action ->
                                addView(action, LinearLayout.LayoutParams(-1, dp(25)).apply {
                                    if (index > 0) topMargin = dp(3)
                                })
                            }
                        }
                        addView(actionColumn, LinearLayout.LayoutParams(dp(76), -1))
                    }
                }
            }
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val bottomRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
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
            } else RotaryControl(this,"GAIN",gain,-12f,12f,masterAccent,{v->gain=v;scheduleUpdate()},{fmt(gain)+" dB"},titleInside=false,wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val compDial=RotaryControl(this,"COMP",compMakeup,0f,100f,masterAccent,{v->compMakeup=v.coerceIn(0f,100f);scheduleUpdate()},{compMakeup.roundToInt().toString()+"%"},titleInside=false,wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val compToggle=roundAction("",if(compressorOn)"ON" else "OFF",compressorOn){
                compressorOn=!compressorOn;save();refreshUi();scheduleUpdate()
            }
            val bassDial=RotaryControl(this,"BASS BOOST",bassBoost,0f,100f,masterAccent,{v->bassBoost=v;scheduleUpdate()},{bassBoost.roundToInt().toString()+"%"},titleInside=false,onStart={previousBassBoost=bassBoost},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val bassToggle=roundAction("",if(bassBoostOn)"ON" else "OFF",bassBoostOn){bassBoostOn=!bassBoostOn;save();refreshUi();scheduleUpdate()}
            val bassReturn=roundAction("↶","RETURN",true){bassBoost=previousBassBoost;save();refreshUi();scheduleUpdate()}
            val loudDial=RotaryControl(this,"LOUDNESS",loudness,0f,100f,masterAccent,{v->loudness=v;scheduleUpdate()},{loudness.roundToInt().toString()+"%"},titleInside=false,onStart={previousLoudness=loudness},wheelMultiplier={if(mouseWheelAdjustOn)mouseWheelSensitivity else 1f})
            val loudToggle=roundAction("",if(loudnessOn)"ON" else "OFF",loudnessOn){loudnessOn=!loudnessOn;save();refreshUi();scheduleUpdate()}
            val loudReturn=roundAction("↶","RETURN",true){loudness=previousLoudness;save();refreshUi();scheduleUpdate()}
            topRow.addView(masterCell(gainDial), LinearLayout.LayoutParams(0, -1, 1f))
            topRow.addView(masterCell(compDial,listOf(compToggle)), LinearLayout.LayoutParams(0, -1, 1f))
            bottomRow.addView(masterCell(bassDial,listOf(bassToggle,bassReturn)), LinearLayout.LayoutParams(0, -1, 1f))
            bottomRow.addView(masterCell(loudDial,listOf(loudToggle,loudReturn)), LinearLayout.LayoutParams(0, -1, 1f))
            grid.addView(topRow, LinearLayout.LayoutParams(-1, 0, 1f))
            grid.addView(bottomRow, LinearLayout.LayoutParams(-1, 0, 1f))
            box.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        }else{
            val gainCol=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
            val gainValueLabel=label(fmt(gain)+" dB",if(compactMaster)9f else 10f,Color.WHITE)
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


    // Delay capture starts only when its mix is above zero; an ON switch alone must not seize audio output.
    private fun effectsActive() = reverbOn || (delayOn && delayMix > 0)
    // AUTO BPM also needs the system-audio capture, even when no FX is audible.
    private fun captureNeeded() = effectsActive() || autoBpmEnabled

    private fun paintAutoBpmButton() {
        autoBpmButton?.let { button ->
            button.text = if (autoBpmEnabled) "AUTO ON" else "AUTO"
            button.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (autoBpmEnabled) colorMaster else Color.rgb(55,62,72))
                setStroke(dp(1), colorMaster)
            }
        }
    }

    // Autonomous beat detector: derive a 10 ms low-frequency onset envelope
    // directly from captured PCM, then search its periodicity over a rolling window.
    // It does not depend on TAP and never modifies the audio being played.
    private fun analyzeAutoBpm(samples: ShortArray, count: Int, nowMs: Long, sampleRate: Int) {
        if (!autoBpmEnabled || count <= 0) return

        val frameSamples = (sampleRate / 100).coerceAtLeast(1) // 10 ms
        val alpha = (1.0 - kotlin.math.exp(-2.0 * Math.PI * 150.0 / sampleRate)).toFloat()
        var offset = 0
        while (offset < count) {
            val end = minOf(offset + frameSamples, count)
            var bassEnergy = 0.0
            var n = 0
            for (i in offset until end) {
                val input = samples[i].toFloat() / 32768f
                autoBpmLowpassState += alpha * (input - autoBpmLowpassState)
                bassEnergy += autoBpmLowpassState.toDouble() * autoBpmLowpassState.toDouble()
                n++
            }
            if (n > 0) {
                val bassRms = kotlin.math.sqrt(bassEnergy / n).toFloat()
                val onset = (bassRms - autoBpmPreviousBass * 0.90f).coerceAtLeast(0f)
                autoBpmPreviousBass = bassRms
                autoBpmHistory.addLast(onset)
                while (autoBpmHistory.size > 1000) autoBpmHistory.removeFirst()
            }
            offset = end
        }

        if (autoBpmHistory.size < 400 || nowMs - autoBpmLastAnalysisMs < 500L) return
        autoBpmLastAnalysisMs = nowMs

        val values = autoBpmHistory.toList()
        val mean = values.average().toFloat()
        val centered = FloatArray(values.size) { values[it] - mean }
        val minLag = 33 // 180 BPM at 10 ms/frame
        val maxLag = minOf(100, centered.size / 3) // 60 BPM; use >= 3 cycles
        var bestLag = -1
        var bestScore = 0f
        val scores = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var cross = 0.0
            var left = 0.0
            var right = 0.0
            for (i in lag until centered.size) {
                val a = centered[i].toDouble()
                val b = centered[i - lag].toDouble()
                cross += a * b
                left += a * a
                right += b * b
            }
            val score = if (left > 1e-12 && right > 1e-12)
                (cross / kotlin.math.sqrt(left * right)).toFloat() else 0f
            scores[lag] = score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        // Prefer the faster fundamental when a slower half-tempo candidate is
        // almost as correlated (a common ambiguity with kick/snare patterns).
        if (bestLag > 0) {
            for (lag in minLag until bestLag) {
                if (bestLag % lag == 0 && scores[lag] >= bestScore * 0.92f) {
                    bestLag = lag
                    break
                }
            }
        }

        if (bestLag > 0 && bestScore >= 0.12f) {
            val detected = (6000f / bestLag).coerceIn(60f, 180f)
            autoBpmEstimateHistory.addLast(detected)
            while (autoBpmEstimateHistory.size > 7) autoBpmEstimateHistory.removeFirst()
            val orderedEstimates = autoBpmEstimateHistory.toList().sorted()
            val stableDetected = orderedEstimates[orderedEstimates.size / 2]
            delayTapBpm = if (autoBpmHasEstimate)
                delayTapBpm * 0.88f + stableDetected * 0.12f else stableDetected
            autoBpmHasEstimate = true
            val liveEstimate = (delayTapBpm + delayBpmCorrection).coerceIn(60f, 180f)
            if (bpmSyncLocked) {
                // Once SYNC is pressed, keep the musical clock fixed.
                // The detector may keep analysing, but cannot pull the effects off beat.
                delayBpm = bpmSyncLockedValue
            } else {
                delayBpm = liveEstimate
            }

            // Anchor the phase to the strongest onset in the most recent beat window.
            var strongestIndex = values.lastIndex
            var strongest = Float.NEGATIVE_INFINITY
            val searchStart = (values.size - bestLag * 2).coerceAtLeast(0)
            for (i in searchStart..values.lastIndex) {
                if (values[i] > strongest) {
                    strongest = values[i]
                    strongestIndex = i
                }
            }
            val ageMs = ((values.lastIndex - strongestIndex) * 10L)
            autoBpmNextBeatMs = nowMs - ageMs

            handler.post {
                bpmUiLabel?.text = String.format(java.util.Locale.US, "%.1f", delayBpm)
            }
        }
    }

    private fun registerDelayTap(): Float {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastDelayTapMs != 0L) {
            val interval = now - lastDelayTapMs
            if (interval in 250L..1500L) {
                delayTapIntervals.addLast(interval)
                while (delayTapIntervals.size > 4) delayTapIntervals.removeFirst()
                val sorted = delayTapIntervals.toList().sorted()
                val median = if (sorted.size % 2 == 0)
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
                else sorted[sorted.size / 2]
                delayTapBpm = (60000.0 / median.toDouble()).toFloat().coerceIn(60f,180f)
                delayBpm = (delayTapBpm + delayBpmCorrection).coerceIn(60f,180f)
                prefs.edit().putFloat("reverb_delay_tap_bpm", delayTapBpm).putFloat("reverb_delay_bpm", delayBpm).apply()
            } else delayTapIntervals.clear()
        } else delayTapIntervals.clear()
        lastDelayTapMs = now
        return delayBpm
    }

    private fun updateEffectsCapture(allowNewConsent: Boolean = false) {
        // Only an explicit ON action can start a new Android consent flow.
        // Temporary stream errors reuse the current MediaProjection token.
        if (!captureNeeded() || captureRunning || captureRestartPending) return
        if (captureProjection != null) startAudioCaptureTest()
        else if (allowNewConsent) {
            captureConsentLocked = false
            startAudioCaptureTest()
        }
    }

    private fun buildEffectsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        fun styleToggle(view: TextView, enabled: Boolean) {
            view.text = if (enabled) "ON" else "OFF"
            view.setTextColor(Color.WHITE)
            view.textSize = 10f
            view.setTypeface(view.typeface, Typeface.BOLD)
            view.gravity = Gravity.CENTER
            view.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (enabled) Color.rgb(28,170,75) else Color.rgb(185,42,48))
                setStroke(dp(1), if (enabled) Color.rgb(125,245,155) else Color.rgb(245,115,120))
            }
        }
        fun fadeRow(title: String, initial: Int, update: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), 0, dp(2), 0)
            }
            val caption = label("$title  " + "%.1f".format(initial / 1000f) + " s", 10f, colorMaster)
            caption.gravity = Gravity.CENTER_VERTICAL
            val seek = SeekBar(this).apply {
                max = 5700
                progress = (initial - 300).coerceIn(0,5700)
                setPadding(0, 0, 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val value = 300 + progress
                            caption.text = "$title  " + "%.1f".format(value / 1000f) + " s"
                            update(value)
                            scheduleUpdate()
                        }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) { save() }
                })
            }
            row.addView(caption, LinearLayout.LayoutParams(-1, dp(17)))
            row.addView(seek, LinearLayout.LayoutParams(-1, dp(25)))
            return row
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.START
        }
        val reverbPower = TextView(this).apply {
            styleToggle(this, reverbOn)
            setOnClickListener {
                reverbOn = !reverbOn
                styleToggle(this, reverbOn)
                save(); scheduleUpdate()
                if (reverbOn) updateEffectsCapture(true)
            }
        }
        val reverbTitle = label("REVERB", 13f, colorMaster)
        reverbTitle.gravity = Gravity.CENTER_VERTICAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(reverbTitle, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(8) })
        header.addView(reverbPower, LinearLayout.LayoutParams(dp(42), dp(20)))
        panel.addView(header, LinearLayout.LayoutParams(-1, dp(24)))

        val depthDial = RotaryControl(
            this, "DEPTH", reverbMix.toFloat(), 0f, 100f, colorMaster,
            { value -> reverbMix = value.roundToInt().coerceIn(0,100); scheduleUpdate() },
            { "$reverbMix%" }, onEnd = { save() }, titleInside = false,
            wheelMultiplier = { if (mouseWheelAdjustOn) mouseWheelSensitivity else 1f }, labelScale = 1.15f
        )
        val timeDial = RotaryControl(
            this, "TIME", reverbSize / 10f, 0f, 100f, colorMaster,
            { value -> reverbSize = (value * 10f).roundToInt().coerceIn(0,1000); scheduleUpdate() },
            { "${(reverbSize / 10f).roundToInt()}%" }, onEnd = { save() }, titleInside = false,
            wheelMultiplier = { if (mouseWheelAdjustOn) mouseWheelSensitivity else 1f }, labelScale = 1.15f
        )
        val reverbRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        // In tablet portrait the Reverb main dials were visually larger
        // than the four Delay dials. Scale only their drawing, not their
        // touch/layout bounds, to match the Delay rotary size.
        if (!phonePortrait && !wideLayout) {
            listOf(depthDial, timeDial).forEach { it.scaleX = 0.72f; it.scaleY = 0.72f }
        }
        reverbRow.addView(depthDial, LinearLayout.LayoutParams(-1, 0, 1f))
        reverbRow.addView(timeDial, LinearLayout.LayoutParams(-1, 0, 1f))
        val reverbFeedbackDial = RotaryControl(
            this, "FEEDBACK", reverbFeedbackPercent.toFloat(), 0f, 100f, colorMaster,
            { value -> reverbFeedbackPercent = value.roundToInt().coerceIn(0,100); scheduleUpdate() },
            { "$reverbFeedbackPercent%" }, onEnd = { save() }, titleInside = false, spaciousText = true,
            wheelMultiplier = { if (mouseWheelAdjustOn) mouseWheelSensitivity else 1f }
        )
        if (!phonePortrait && !wideLayout) { reverbFeedbackDial.scaleX = 0.72f; reverbFeedbackDial.scaleY = 0.72f }
        reverbRow.addView(reverbFeedbackDial, LinearLayout.LayoutParams(-1, 0, 1f))
        val reverbDecayDial = RotaryControl(
            this, "DECAY", reverbDecayPercent.toFloat(), 0f, 100f, colorMaster,
            { value -> reverbDecayPercent = value.roundToInt().coerceIn(0,100); scheduleUpdate() },
            { "$reverbDecayPercent%" }, onEnd = { save() }, titleInside = false, spaciousText = true,
            wheelMultiplier = { if (mouseWheelAdjustOn) mouseWheelSensitivity else 1f }, labelScale = 1.2f, bottomTextLift = 4f
        )
        val reverbColorDial = RotaryControl(
            this, "COLOR", reverbColorPercent.toFloat(), 0f, 100f, colorMaster,
            { value -> reverbColorPercent = value.roundToInt().coerceIn(0,100); scheduleUpdate() },
            { "$reverbColorPercent%" }, onEnd = { save() }, titleInside = false, spaciousText = true,
            wheelMultiplier = { if (mouseWheelAdjustOn) mouseWheelSensitivity else 1f }, labelScale = 1.2f, bottomTextLift = 4f
        )
        // The Reverb fade strip is intentionally replaced by this third rotary control.
        // The existing smooth Reverb fade remains internal to the DSP.
        val delayDial = RotaryControl(
            this, "DELAY LEVEL", delayMix.toFloat(), 0f, 100f, colorMaster,
            { value -> delayMix = value.roundToInt().coerceIn(0,100); scheduleUpdate() },
            { "$delayMix%" }, onEnd = { save() }, titleInside = false, spaciousText = true,
            wheelMultiplier = { if (mouseWheelAdjustOn) mouseWheelSensitivity else 1f }
        )
        val delayControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val delayTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val bpmLabel = TextView(this).apply {
            text = String.format(java.util.Locale.US, "%.1f", delayBpm)
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        bpmUiLabel = bpmLabel
        val tapButton = TextView(this).apply {
            text = "TAP"
            textSize = 9f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.rgb(55,62,72))
                setStroke(dp(1), colorMaster)
            }
            setOnClickListener {
                autoBpmEnabled = false
                paintAutoBpmButton()
                bpmSyncLocked = false
                delayBpm = registerDelayTap()
                delayTapBpm = delayBpm
                bpmLabel.text = String.format(java.util.Locale.US, "%.1f", delayBpm)
                save(); scheduleUpdate()
            }
        }
        val autoButton = TextView(this).apply {
            text = if (autoBpmEnabled) "AUTO ON" else "AUTO"
            textSize = 8f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (autoBpmEnabled) colorMaster else Color.rgb(55,62,72))
                setStroke(dp(1), colorMaster)
            }
            setOnClickListener {
                autoBpmEnabled = !autoBpmEnabled
                if (autoBpmEnabled) {
                    autoBpmHistory.clear()
                    autoBpmEstimateHistory.clear()
                    bpmSyncLocked = false
                    autoBpmPreviousBass = 0f
                    autoBpmLowpassState = 0f
                    autoBpmLastAnalysisMs = 0L
                    autoBpmNextBeatMs = 0L
                    autoBpmHasEstimate = false
                }
                paintAutoBpmButton()
                save()
                if (autoBpmEnabled) updateEffectsCapture(true)
            }
        }
        autoBpmButton = autoButton
        val syncButton = TextView(this).apply {
            text = "SYNC"
            textSize = 8f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.rgb(55,62,72))
                setStroke(dp(1), colorMaster)
            }
            setOnClickListener {
                val now = android.os.SystemClock.elapsedRealtime()
                val beatMs = 60000f / delayBpm.coerceIn(60f,180f)
                val lastBeat = autoBpmNextBeatMs
                delaySyncOffsetMs = if (autoBpmEnabled && lastBeat > 0L) {
                    val elapsed = ((now - lastBeat).toFloat() % beatMs + beatMs) % beatMs
                    (beatMs - elapsed) % beatMs
                } else {
                    // In manual mode, press SYNC exactly on the beat to establish phase.
                    0f
                }
                bpmSyncLockedValue = delayBpm.coerceIn(60f, 180f)
                bpmSyncLocked = true
                scheduleUpdate()
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(colorMaster)
                    setStroke(dp(1), colorMaster)
                }
                postDelayed({
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(Color.rgb(55,62,72))
                        setStroke(dp(1), colorMaster)
                    }
                }, 350L)
            }
        }
        var bpmFineSeek: SeekBar? = null
        fun bpmNudgeButton(symbol: String, amount: Float): TextView = TextView(this).apply {
            text = symbol
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.rgb(55,62,72))
                setStroke(dp(1), colorMaster)
            }
            setOnClickListener {
                delayBpmCorrection = (delayBpmCorrection + amount).coerceIn(-10f,10f)
                delayBpm = (delayTapBpm + delayBpmCorrection).coerceIn(60f,180f)
                if (bpmSyncLocked) bpmSyncLockedValue = delayBpm
                bpmFineSeek?.progress = (100 + (delayBpmCorrection * 10f).roundToInt()).coerceIn(0,200)
                bpmLabel.text = String.format(java.util.Locale.US, "%.1f", delayBpm)
                save(); scheduleUpdate()
            }
        }
        val delayPower = TextView(this).apply {
            styleToggle(this, delayOn)
            setOnClickListener {
                delayOn = !delayOn
                styleToggle(this, delayOn)
                save(); scheduleUpdate()
                if (delayOn) updateEffectsCapture(true)
            }
        }
        delayTop.addView(delayPower, LinearLayout.LayoutParams(dp(50), dp(42)))
        delayTop.addView(bpmNudgeButton("−", -0.1f), LinearLayout.LayoutParams(dp(34), dp(42)))
        delayTop.addView(tapButton, LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(4); rightMargin = dp(4) })
        delayTop.addView(bpmNudgeButton("+", 0.1f), LinearLayout.LayoutParams(dp(34), dp(42)).apply { leftMargin = dp(3); rightMargin = dp(3) })
        delayTop.addView(bpmLabel, LinearLayout.LayoutParams(0, dp(46), 1f))
        delayTop.addView(autoButton, LinearLayout.LayoutParams(dp(48), dp(38)).apply { leftMargin = dp(3) })
        delayTop.addView(syncButton, LinearLayout.LayoutParams(dp(48), dp(38)).apply { leftMargin = dp(3) })
        delayControls.addView(delayTop, LinearLayout.LayoutParams(-1, 0, 1.6f))
        val bpmFineCaption = label("FINE BPM  ±10", 8f, colorMaster).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        delayControls.addView(bpmFineCaption, LinearLayout.LayoutParams(-1, 0, 0.65f).apply { topMargin = dp(2) })
        val fineSeek = SeekBar(this).apply {
            max = 200
            progress = (100 + (delayBpmCorrection * 10f).roundToInt()).coerceIn(0,200)
            setPadding(0, 0, 0, 0)
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        delayBpmCorrection = (progress - 100) * 0.1f
                        delayBpm = (delayTapBpm + delayBpmCorrection).coerceIn(60f,180f)
                        if (bpmSyncLocked) bpmSyncLockedValue = delayBpm
                        bpmLabel.text = String.format(java.util.Locale.US, "%.1f", delayBpm)
                        scheduleUpdate()
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { save() }
            })
        }
        bpmFineSeek = fineSeek
        delayControls.addView(fineSeek, LinearLayout.LayoutParams(-1, 0, 0.9f).apply { topMargin = dp(2); bottomMargin = dp(2) })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val modeLabels = arrayOf("SHORT DELAY", "LONG DELAY", "DUB ECHO")
        val modeViews = ArrayList<TextView>()
        fun paintModeButtons() {
            modeViews.forEachIndexed { index, button ->
                val selected = selectedBeatFx == index
                button.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(if (selected) colorMaster else Color.rgb(55,62,72))
                    setStroke(dp(1), colorMaster)
                }
                button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
            }
        }
        modeLabels.forEachIndexed { index, caption ->
            val button = TextView(this).apply {
                text = caption
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(3), 0, dp(3), 0)
                setOnClickListener {
                    selectedBeatFx = index
                    delayMode = index
                    paintModeButtons()
                    save(); scheduleUpdate()
                }
            }
            modeViews.add(button)
            modeRow.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index > 0) leftMargin = dp(3)
            })
        }
        paintModeButtons()
        delayControls.addView(modeRow, LinearLayout.LayoutParams(-1, 0, 1.1f).apply { topMargin = dp(3); bottomMargin = dp(3) })

        val divisions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val divisionLabels = arrayOf("¼", "½", "¾", "1", "2", "4", "8")
        val divisionViews = ArrayList<TextView>()
        fun paintDivisionButtons() {
            divisionViews.forEachIndexed { index, button ->
                val selected = delayDivision == index
                button.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(if (selected) colorMaster else Color.rgb(55,62,72))
                    setStroke(dp(1), colorMaster)
                }
                button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
            }
        }
        divisionLabels.forEachIndexed { index, caption ->
            val button = TextView(this).apply {
                text = caption
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setOnClickListener {
                    delayDivision = index
                    paintDivisionButtons()
                    save(); scheduleUpdate()
                }
            }
            divisionViews.add(button)
            divisions.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index > 0) leftMargin = dp(3)
            })
        }
        paintDivisionButtons()
        delayControls.addView(divisions, LinearLayout.LayoutParams(-1, 0, 1.15f).apply { topMargin = dp(3); bottomMargin = dp(3) })

        val frequencyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val frequencyLabels = arrayOf("FX LOW", "FX MID", "FX HIGH")
        val frequencyViews = ArrayList<TextView>()
        fun paintFrequencyButtons() {
            frequencyViews.forEachIndexed { index, button ->
                val active = fxFrequency == index
                button.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(if (active) colorMaster else Color.rgb(55,62,72))
                    setStroke(dp(1), colorMaster)
                }
                button.setTextColor(if (active) Color.BLACK else Color.WHITE)
            }
        }
        frequencyLabels.forEachIndexed { index, caption ->
            val button = TextView(this).apply {
                text = caption
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setOnClickListener {
                    fxFrequency = index
                    paintFrequencyButtons()
                    save(); scheduleUpdate()
                }
            }
            frequencyViews.add(button)
            frequencyRow.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index > 0) leftMargin = dp(4)
            })
        }
        paintFrequencyButtons()
        delayControls.addView(frequencyRow, LinearLayout.LayoutParams(-1, 0, 1.15f).apply { topMargin = dp(3); bottomMargin = dp(3) })



        val delayTimeDial = RotaryControl(
            this, "TIME", delayTimePercent.toFloat(), 50f, 200f, colorMaster,
            { value -> delayTimePercent = value.roundToInt().coerceIn(50,200); scheduleUpdate() },
            { "${delayTimePercent}%" }, onEnd = { save() }, titleInside = false, spaciousText = true,
            wheelMultiplier = { if (mouseWheelAdjustOn) mouseWheelSensitivity else 1f }
        )
        val delayFeedbackDial = RotaryControl(
            this, "FEEDBACK", delayFeedbackPercent.toFloat(), 0f, 90f, colorMaster,
            { value -> delayFeedbackPercent = value.roundToInt().coerceIn(0,90); scheduleUpdate() },
            { "${delayFeedbackPercent}%" }, onEnd = { save() }, titleInside = false, spaciousText = true,
            wheelMultiplier = { if (mouseWheelAdjustOn) mouseWheelSensitivity else 1f }
        )
        val delayToneDial = RotaryControl(
            this, "TONE", delayTonePercent.toFloat(), 0f, 100f, colorMaster,
            { value -> delayTonePercent = value.roundToInt().coerceIn(0,100); scheduleUpdate() },
            { "${delayTonePercent}%" }, onEnd = { save() }, titleInside = false, spaciousText = true,
            wheelMultiplier = { if (mouseWheelAdjustOn) mouseWheelSensitivity else 1f }
        )
        val delayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        delayRow.addView(delayDial, LinearLayout.LayoutParams(0, -1, 1f))
        delayRow.addView(delayTimeDial, LinearLayout.LayoutParams(0, -1, 1f))
        delayRow.addView(delayFeedbackDial, LinearLayout.LayoutParams(0, -1, 1f))
        delayRow.addView(delayToneDial, LinearLayout.LayoutParams(0, -1, 1f))
        val reverbColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val reverbControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val reverbSideColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        reverbSideColumn.addView(reverbDecayDial, LinearLayout.LayoutParams(-1, 0, 1f))
        reverbSideColumn.addView(reverbColorDial, LinearLayout.LayoutParams(-1, 0, 1f))
        // Give DECAY and COLOR enough width for noticeably larger rotary faces
        // while keeping their captions and values below each dial.
        reverbControlsRow.addView(reverbSideColumn, LinearLayout.LayoutParams(dp(if (!phonePortrait && !wideLayout) 58 else 74), -1).apply {
            rightMargin = dp(3)
        })
        reverbControlsRow.addView(reverbRow, LinearLayout.LayoutParams(0, -1, 1f))
        reverbColumn.addView(reverbControlsRow, LinearLayout.LayoutParams(-1, 0, 1f))
        val delayColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        delayColumn.addView(delayRow, LinearLayout.LayoutParams(-1, 0, 0.36f))
        delayColumn.addView(delayControls, LinearLayout.LayoutParams(-1, 0, 0.64f))
        val effectsBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        effectsBody.addView(reverbColumn, LinearLayout.LayoutParams(0, -1, if (!phonePortrait && !wideLayout) 0.37f else 0.43f))
        effectsBody.addView(delayColumn, LinearLayout.LayoutParams(0, -1, if (!phonePortrait && !wideLayout) 0.63f else 0.57f))
        panel.addView(effectsBody, LinearLayout.LayoutParams(-1, 0, 1f))

        return panel
    }

    private fun addEffectsSection(parent: LinearLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        box.addView(buildEffectsPanel(), LinearLayout.LayoutParams(-1, -1))
        parent.addView(box, LinearLayout.LayoutParams(0, -1, 1f))
        sections["FX"] = box
    }
    private fun startAudioCaptureTest() {
        // Reuse the existing MediaProjection session after a track/route switch.
        // Only request Android approval when no live session exists.
        if (captureRunning || captureRestartPending) return
        captureProjection?.let { existingProjection ->
            captureRestartAttempts = 0
            beginAudioCapture(existingProjection)
            return
        }
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
            captureConsentLocked = true
            Toast.makeText(this, "Cattura non autorizzata • attiva AUTO BPM oppure ON su Reverb/Delay per riprovare", Toast.LENGTH_LONG).show()
            return
        }
        if (!captureNeeded()) return
        try {
            // Use the same proven startup sequence as the S25 build:
            // start the mediaProjection foreground service first, then obtain
            // the projection only after Android has had time to promote it.
            val serviceIntent = android.content.Intent(this, AudioCaptureForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            handler.postDelayed({
                try {
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    val projection = manager.getMediaProjection(resultCode, data)
                    if (projection == null) {
                        showCaptureError("ANDROID NON HA RESTITUITO LA SESSIONE DI CATTURA", null)
                        stopAudioCaptureTest()
                        return@postDelayed
                    }
                    intentionallyStoppingProjection = null
                    captureProjection = projection
                    projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                        override fun onStop() {
                            // This callback is already delivered on the main handler supplied
                            // to registerCallback(). Handle revocation synchronously here:
                            // posting a second runOnUiThread block leaves a race in which the
                            // AudioRecord error handler can schedule a restart with a dead token.
                            // Ignore the callback caused by our own stop/cleanup.
                            if (intentionallyStoppingProjection === projection) {
                                intentionallyStoppingProjection = null
                                return
                            }
                            // A delayed callback from an older projection must never tear down
                            // a newer capture session that is already active.
                            if (captureProjection !== projection) {
                                captureDiag("MEDIA_PROJECTION onStop ignorato: callback di sessione non corrente; activeProjectionPresent=${captureProjection != null} captureRunning=$captureRunning")
                                return
                            }
                            captureDiag("MEDIA_PROJECTION onStop della sessione corrente; captureRunning=$captureRunning restartPending=$captureRestartPending projectionId=${System.identityHashCode(projection)} autoBpm=$autoBpmEnabled restartAttempts=$captureRestartAttempts")
                            // Invalidate the token before releasing I/O so no queued retry can
                            // rebuild AudioRecord/AudioTrack from this revoked projection.
                            captureRunning = false
                            captureRestartPending = false
                            captureProjection = null
                            reverbOn = false
                            delayOn = false
                            releaseCaptureIoOnly()
                            save()
                            refreshUi()
                            captureConsentLocked = true
                            try { stopService(android.content.Intent(this@MainActivity, AudioCaptureForegroundService::class.java)) } catch (_: Throwable) {}
                            showCaptureRevokedDialog()
                        }
                    }, handler)
                    beginAudioCapture(projection)
                } catch (t: Throwable) {
                    showCaptureError("ERRORE CREAZIONE SESSIONE DI CATTURA", t)
                    stopAudioCaptureTest()
                }
            }, 500)
        } catch (t: Throwable) {
            showCaptureError("IMPOSSIBILE AVVIARE IL SERVIZIO MEDIA PROJECTION", t)
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
                // Capture other apps (Morphe), never FREQUALIZER's own wet return.
                .excludeUid(android.os.Process.myUid())
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
            val minOut = android.media.AudioTrack.getMinBufferSize(
                sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            if (minOut <= 0) throw IllegalStateException("AudioTrack buffer non valido: $minOut")
            val outputFormat = android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build()
            // Route the wet return as normal media so it follows the selected
            // music output (including Bluetooth). The capture config excludes
            // this app UID to prevent recursive feedback.
            val outputAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(outputAttributes)
                .setAudioFormat(outputFormat)
                .setBufferSizeInBytes(maxOf(minOut * 2, 8192))
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
            if (track.state != android.media.AudioTrack.STATE_INITIALIZED) {
                track.release()
                throw IllegalStateException("AudioTrack non inizializzato sull'uscita audio")
            }
            captureTrack = track
            parallelReverbProcessor = SchroederReverb(sampleRate)
            captureRecord = record
            track.play()
            record.startRecording()
            captureDiag("I/O avviato: rate=$sampleRate minInBuffer=$minBuffer inBuffer=${maxOf(minBuffer * 2, 8192)} minOutBuffer=$minOut outBuffer=${maxOf(minOut * 2, 8192)} recordState=${record.state} recordingState=${record.recordingState} trackState=${track.state} playState=${track.playState}")
            captureRunning = true
            captureThread = Thread({
                val buffer = ShortArray(2048)
                val wetBuffer = ShortArray(buffer.size)
                val processor = parallelReverbProcessor ?: return@Thread
                var lastUi = 0L
                var lastReadCompleted = 0L
                try {
                    while (captureRunning) {
                        val readStarted = android.os.SystemClock.elapsedRealtime()
                        val count = record.read(buffer, 0, buffer.size)
                        val readCompleted = android.os.SystemClock.elapsedRealtime()
                        if (lastReadCompleted > 0L && readCompleted - lastReadCompleted > 100L) {
                            captureDiag("GAP tra letture AudioRecord: ${readCompleted - lastReadCompleted}ms; readDuration=${readCompleted - readStarted}ms count=$count recordState=${record.state} recordingState=${record.recordingState} trackPlayState=${track.playState}")
                        }
                        lastReadCompleted = readCompleted
                        if (count > 0) {
                            captureRestartAttempts = 0
                            var peak = 0
                            var sum = 0.0
                            for (i in 0 until count) {
                                val sample = buffer[i].toInt()
                                val absolute = kotlin.math.abs(sample)
                                if (absolute > peak) peak = absolute
                                sum += sample.toDouble() * sample.toDouble()
                            }
                            processor.process(
                                buffer, wetBuffer, count,
                                reverbMix, reverbSize, reverbHpHz, reverbLpHz, false,
                                reverbOn, reverbFadeMs, delayMix, delayBpm,
                                delayOn, delayFadeMs, delayDivision, delayMode, delayTimePercent, delayFeedbackPercent, delayTonePercent, fxFrequency,
                                reverbFeedbackPercent, reverbDecayPercent, reverbColorPercent,
                                delaySyncOffsetMs
                            )
                            var written = 0
                            while (written < count && captureRunning) {
                                val writeStarted = android.os.SystemClock.elapsedRealtime()
                                val n = track.write(wetBuffer, written, count - written, android.media.AudioTrack.WRITE_BLOCKING)
                                val writeDuration = android.os.SystemClock.elapsedRealtime() - writeStarted
                                if (writeDuration > 80L || n <= 0) captureDiag("AudioTrack.write: n=$n duration=${writeDuration}ms written=$written/$count trackState=${track.state} playState=${track.playState}")
                                if (n <= 0) throw IllegalStateException("AudioTrack write non riuscita: $n")
                                written += n
                            }
                            val rms = kotlin.math.sqrt(sum / count)
                            analyzeAutoBpm(buffer, count, readCompleted, sampleRate)
                            val rmsDb = if (rms > 0.0) 20.0 * kotlin.math.log10(rms / 32768.0) else -120.0
                            val peakDb = if (peak > 0) 20.0 * kotlin.math.log10(peak / 32768.0) else -120.0
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastUi >= 400) {
                                lastUi = now
                                handler.post {
                                    captureStatusView?.text =
                                        "REVERB PARALLELO ATTIVO • 48 kHz mono\n" +
                                        "Ingresso Morphe RMS: ${"%.1f".format(rmsDb)} dBFS\n" +
                                        "Picco: ${"%.1f".format(peakDb)} dBFS\n" +
                                        "DISSOLVENZA: ${"%.1f".format(reverbFadeMs / 1000f)} s • ${if (reverbOn) "ON" else "OFF"}\n\n" +
                                        "La traccia originale resta attiva; FREQUALIZER riproduce il ritorno riverberato."
                                }
                            }
                        } else if (count < 0 && captureRunning) {
                            captureDiag("AudioRecord.read errore: $count recordState=${record.state} recordingState=${record.recordingState}")
                            throw IllegalStateException("AudioRecord read: $count")
                        }
                    }
                } catch (t: Throwable) {
                    if (captureRunning) scheduleCaptureRestart(projection, t)
                } finally {
                    captureRunning = false
                }
            }, "Frequalizer-ReverbReturn").apply { priority = Thread.MAX_PRIORITY; start() }
        } catch (t: Throwable) {
            scheduleCaptureRestart(projection, t)
        }
    }

    // Rebuild only the input/output audio streams after a track or route change.
    // Keep the MediaProjection token and its foreground service alive, so Android
    // does not ask for a new screen-capture approval for every song.
    private fun releaseCaptureIoOnly() {
        val record = captureRecord
        captureRecord = null
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        val track = captureTrack
        captureTrack = null
        try { track?.pause() } catch (_: Throwable) {}
        try { track?.flush() } catch (_: Throwable) {}
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        parallelReverbProcessor = null
        captureThread = null
    }

    private fun scheduleCaptureRestart(projection: android.media.projection.MediaProjection, error: Throwable) {
        captureDiag("RESTART richiesto: ${error.javaClass.simpleName}: ${error.message}; projectionSame=${captureProjection === projection} effectsActive=${effectsActive()}")
        captureRunning = false
        handler.post {
            releaseCaptureIoOnly()
            if (captureProjection !== projection || !captureNeeded() || captureConsentLocked) {
                captureRestartPending = false
                return@post
            }
            if (captureRestartAttempts >= 20) {
                // Keep the user's MediaProjection consent and foreground service alive.
                // A temporary AudioRecord/AudioTrack failure must not destroy the
                // projection token: the next effect ON or route retry can rebuild I/O
                // without showing Android's consent dialog again.
                captureRestartPending = false
                showCaptureError("FLUSSO AUDIO MOMENTANEAMENTE NON DISPONIBILE • SESSIONE AUTORIZZATA CONSERVATA", error)
                return@post
            }
            captureRestartAttempts++
            val waitMs = (250L + captureRestartAttempts * 250L).coerceAtMost(5000L)
            captureRestartPending = true
            handler.postDelayed({
                captureRestartPending = false
                if (captureProjection === projection && captureNeeded() && !captureRunning && !captureConsentLocked) {
                    beginAudioCapture(projection)
                }
            }, waitMs)
        }
    }

    private fun showCaptureMeter() {
        val view = TextView(this).apply {
            text = "Richiesta di avvio Reverb parallelo…\n\nApri YouTube Morphe e fai partire un brano. FREQUALIZER elaborerà la copia catturata e riprodurrà il ritorno riverberato. L'audio originale continuerà a suonare."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        captureStatusView = view
        // If the capture is already running, simply reopen its status window.
        captureDialog?.let { if (it.isShowing) { it.dismiss() } }
        val dialog = AlertDialog.Builder(this)
            .setTitle("TEST CATTURA AUDIO")
            .setView(view)
            .setPositiveButton("FERMA TEST", null)
            .setNegativeButton("NASCONDI", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                stopAudioCaptureTest()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        }
        // Dismissing or hiding this window no longer ends MediaProjection.
        dialog.setOnDismissListener {
            if (captureDialog === dialog) captureDialog = null
            if (captureStatusView === view) captureStatusView = null
        }
        captureDialog = dialog
        dialog.show()
    }

    // A revoked MediaProjection token cannot be reused on Android 14+.
    // Keep the user's FX/AUTO selections intact and offer a fresh, explicit
    // consent flow instead of leaving the user with only a generic error dialog.
    private fun showCaptureRevokedDialog() {
        val detail = "Android ha terminato la sessione di cattura.\\n" +
            "Le impostazioni degli effetti sono conservate, ma il flusso audio resta fermo finché non autorizzi una nuova sessione.\\n\\n" +
            "DIAGNOSTICA CATTURA (ultimi eventi):\\n" + captureDiagSnapshot()
        val dialog = AlertDialog.Builder(this)
            .setTitle("SESSIONE DI CATTURA INTERROTTA")
            .setMessage(detail)
            .setPositiveButton("RIATTIVA CATTURA") { _, _ ->
                captureConsentLocked = false
                if (captureNeeded()) {
                    startAudioCaptureTest()
                } else {
                    Toast.makeText(this, "Attiva prima Reverb, Delay o AUTO BPM.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("CHIUDI", null)
            .setNeutralButton("COPIA DETTAGLI", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FREQUALIZER Capture Error", detail))
                Toast.makeText(this, "Dettagli copiati", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showCaptureError(title: String, error: Throwable?) {
        val detail = buildString {
            append(title)
            if (error != null) {
                append("\n\n")
                append(error.javaClass.name)
                append(": ")
                append(error.message ?: "(nessun messaggio)")
                append("\n\nDETTAGLI TECNICI:\n")
                append(android.util.Log.getStackTraceString(error))
            }
            append("\\n\\nDIAGNOSTICA CATTURA (ultimi eventi):\\n")
            append(captureDiagSnapshot())
        }
        val detailsView = ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 12f
                setTextColor(Color.WHITE)
                setTextIsSelectable(true)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("TEST CATTURA AUDIO • ERRORE")
            .setView(detailsView)
            .setPositiveButton("CHIUDI", null)
            .setNeutralButton("COPIA DETTAGLI", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FREQUALIZER Capture Error", detail))
                Toast.makeText(this, "Dettagli copiati", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun stopAudioCaptureTest() {
        captureRunning = false
        captureRestartPending = false
        releaseCaptureIoOnly()
        captureRestartAttempts = 0
        val projection = captureProjection
        captureProjection = null
        if (projection != null) {
            intentionallyStoppingProjection = projection
            try { projection.stop() } catch (_: Throwable) {}
        }
        try { stopService(android.content.Intent(this, AudioCaptureForegroundService::class.java)) } catch (_: Throwable) {}
        captureStatusView = null
        captureDialog = null
    }


    private fun connectAudio(){
        if(Build.VERSION.SDK_INT<28){status.text="Dynamics Processing richiede Android 9 o superiore";return}
        try{
            val cfg=DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,2,true,4,true,1,true,3,limiterOn).build()
            dpEffect=DynamicsProcessing(0,0,cfg).also{it.enabled=false}
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
        val wheelValue=label("Velocità: ${"%.1f".format(java.util.Locale.US,mouseWheelSensitivity)}X",12f,gold)
        panel.addView(wheelValue,LinearLayout.LayoutParams(-1,dp(28)))
        val wheelScaleLabels=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
            addView(label("−3",10f,Color.LTGRAY),LinearLayout.LayoutParams(0,dp(18),1f))
            addView(label("1X • STANDARD",10f,gold),LinearLayout.LayoutParams(0,dp(18),1f))
            addView(label("+3",10f,Color.LTGRAY),LinearLayout.LayoutParams(0,dp(18),1f))
        }
        panel.addView(wheelScaleLabels,LinearLayout.LayoutParams(-1,dp(20)))
        val wheelSeek=SeekBar(this).apply{
            max=6;progress=((mouseWheelSensitivity-0.7f)*10f).roundToInt().coerceIn(0,6);isEnabled=mouseWheelAdjustOn
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seek:SeekBar?,progress:Int,fromUser:Boolean){
                    if(fromUser){mouseWheelSensitivity=(0.7f+progress*0.1f).coerceIn(0.7f,1.3f);wheelValue.text="Velocità: ${"%.1f".format(java.util.Locale.US,mouseWheelSensitivity)}X";save()}
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
        // An intentional close starts a fresh session next time; background
        // process reclamation and configuration changes keep the saved state.
        if (isFinishing) {
            prefs.edit().putBoolean("session_in_background", false).apply()
            stopAudioCaptureTest()
        }
        try{dspHandler.removeCallbacksAndMessages(null);dspThread.quitSafely();dpEffect?.enabled=false;dpEffect?.release()}catch(_:Throwable){}
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
    private val bottomTextLift:Float = 0f,
    private val step:Float = 0f,
    private val spaciousText:Boolean = false,
    private val labelScale:Float = 1f,
    private val wheelStep:Float = 0f
):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var v=initial
    private var downY=0f
    private var downV=initial
    private var dragged=false
    private val density get()=resources.displayMetrics.density
    private val layoutScale:Float get() {
        val cfg=resources.configuration
        val w=cfg.screenWidthDp.toFloat(); val h=cfg.screenHeightDp.toFloat()
        return if(min(w,h)<600f && w>h) min(w/1280f,h/800f).coerceIn(0.42f,1f) else 1f
    }
    private fun dp(x:Float)=x*density*layoutScale
    private val rect get()={
        // In tablet portrait double-mid EQ, reserve a dedicated lower text lane
        // for title and value. The dial itself is sized from the remaining square
        // area, so the LOW face cannot be squeezed into an oval or cut by its labels.
        val dialTextReserve=if(portraitDialFit) dp(27f) else if(spaciousText) dp(32f) else dp(18f)
        val side=min(width.toFloat(),(height.toFloat()-dialTextReserve).coerceAtLeast(1f))* .94f
        val cy=if(portraitDialFit) (height-dp(27f))/2f else if(spaciousText) (height-dp(32f))/2f else (height-dp(26f))/2f
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
        val titleSize=min(dp(11f*labelScale),r.width()*.86f/(title.length.coerceAtLeast(1)*.58f))
        p.textSize=titleSize.coerceAtLeast(dp(5.5f));p.color=Color.WHITE
        if(titleInside){p.textAlign=Paint.Align.CENTER;c.drawText(title,cx,cy+rad*.48f,p)}else{c.drawText(title,cx,height-dp((if(spaciousText)25f else 17f)+bottomTextLift),p)}
        val value=valueText()
        p.color=accent;p.textSize=min(dp(10f*labelScale),r.width()*.86f/(value.length.coerceAtLeast(1)*.58f)).coerceAtLeast(dp(5.5f));c.drawText(value,cx,height-dp((if(spaciousText)5f else 4f)+bottomTextLift),p)
    }
    private fun quantize(value:Float):Float {
        if (step <= 0f) return value.coerceIn(minV,maxV)
        return (minV + ((value-minV)/step).roundToInt()*step).coerceIn(minV,maxV)
    }
    private fun updateFromPoint(x:Float,y:Float){
        val r=rect;val cx=r.centerX();val cy=r.centerY()
        var degrees=Math.toDegrees(atan2((y-cy).toDouble(),(x-cx).toDouble())).toFloat()
        if(degrees<0f)degrees+=360f
        if(degrees<45f)degrees+=360f
        val fraction=((degrees.coerceIn(135f,405f)-135f)/270f).coerceIn(0f,1f)
        v=quantize(minV+fraction*(maxV-minV))
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
                if(abs(delta)>dp(if(step>0f)1f else 4f)) dragged=true
                if(dragged){
                    // Effect dials use a deliberately fine 1-unit-per-step feel;
                    // the other mixer controls retain their original response.
                    val sensitivity=(maxV-minV)*(if(step>0f)0.25f else 2.2f)/height.coerceAtLeast(1)
                    v=quantize(downV+delta*sensitivity);changed(v);invalidate()
                }
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
            val wheel = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            // Match the main EQ wheel response exactly. The shared sensitivity
            // option scales every dial, including the effect controls.
            // Every mouse-wheel detent advances every rotary by the same fraction
            // of its total travel: 1/28 of the range, matching EQ's 1 dB step
            // across its 28 dB sweep. This keeps physical dial response identical.
            val wheelUnit = if (wheelStep > 0f) wheelStep else (maxV - minV) / 28f
            val delta = wheel * wheelUnit * wheelMultiplier().coerceIn(0.7f, 1.3f)
            v=quantize(v+delta)
            changed(v);invalidate();onEnd();return true
        }
        return super.onGenericMotionEvent(e)
    }
    override fun performClick():Boolean{super.performClick();return true}
}

// Experimental master compressor prototype: Android DynamicsProcessing MBC.

