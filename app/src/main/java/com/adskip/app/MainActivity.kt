package com.adskip.app

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The three remote buttons the app presses. */
enum class Key(val label: String, val rokuKey: String) {
    MUTE("Mute", "VolumeMute"),
    PLAY("Play", "Play"),
    FF("Fast-forward", "Fwd")
}

class MainActivity : ComponentActivity() {

    // ---- colors
    private val NAVY = Color.parseColor("#0F1720")
    private val INK = Color.parseColor("#15212C")
    private val MUTED = Color.parseColor("#5A6A78")
    private val LINE = Color.parseColor("#CBD3DA")
    private val PAGE = Color.parseColor("#EDF0F3")
    private val ACCENT = Color.parseColor("#2A4FA0")
    private val TEAL = Color.parseColor("#0E7A6B")
    private val RED = Color.parseColor("#C4372D")
    private val TINT_SHOW = Color.parseColor("#E3F3EF")
    private val TINT_AD = Color.parseColor("#FBE7E4")

    // ---- control and detection
    private lateinit var ir: IrSender
    private val roku = RokuControl()
    private val detector = AdDetector()
    private val main = Handler(Looper.getMainLooper())
    private var db: TvDb? = null
    private var activeSet: TvDb.CodeSet? = null

    private val cutCount = AtomicInteger(0)
    @Volatile private var logoNow = false
    @Volatile private var diffNow = 0f
    @Volatile private var dbNow: Double? = null

    private var running = false
    private var useRoku = false
    private var adStart = 0L
    private var ffCapMs = 45_000L
    private var runStart = 0L
    private var audio: AudioMeter? = null
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null

    // ---- Find my TV
    private var finding = false
    private var findToken = 0
    private var findList: List<Int> = emptyList()
    private var findIdx = 0
    private var findBrand: TvDb.Brand? = null
    private var findAudio: AudioMeter? = null
    private var findBaseline = Double.NaN
    private var pendingFind = false
    private val recentDb = CopyOnWriteArrayList<Double>()

    // ---- views
    private lateinit var preview: PreviewView
    private lateinit var statusCard: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button
    private lateinit var autoBox: CheckBox
    private lateinit var irBox: LinearLayout
    private lateinit var rokuBox: LinearLayout
    private lateinit var manualBox: LinearLayout
    private lateinit var setBox: LinearLayout
    private lateinit var brandSpinner: Spinner
    private lateinit var setSpinner: Spinner
    private lateinit var findBtn: Button
    private lateinit var remoteSpinner: Spinner
    private lateinit var muteEdit: EditText
    private lateinit var playEdit: EditText
    private lateinit var ffEdit: EditText
    private lateinit var rokuIpEdit: EditText

    private val logLines = ArrayList<String>()
    private val debugRows = ArrayList<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val prefs by lazy { getSharedPreferences("commercial_skip", Context.MODE_PRIVATE) }
    private var pendingSetId: String? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (pendingFind) {
            pendingFind = false
            startFind()
        } else if (granted(Manifest.permission.CAMERA)) {
            startRun()
        } else {
            log("Camera permission is needed to watch the TV.")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = IrSender(this)
        setContentView(buildUi())
        log(if (ir.available) "IR blaster found." else "This phone reports no IR blaster.")
        Thread {
            val loaded = TvDb.load(this)
            runOnUiThread {
                db = loaded
                populateBrands()
                if (loaded == null) {
                    log("TV code database is not in this build. Manual codes and Roku still work.")
                } else {
                    log("Loaded codes for " + loaded.brands.size + " TV brands.")
                }
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        if (finding) stopFind("Search stopped.")
        if (running) stopRun()
        savePrefs()
    }

    private fun savePrefs() {
        val bp = brandSpinner.selectedItemPosition
        val d = db
        val brandName = if (bp > 0 && d != null && bp <= d.brands.size) d.brands[bp - 1].name else ""
        prefs.edit()
            .putString("mode", if (detector.mode == Mode.LIVE) "live" else "recorded")
            .putInt("sens", detector.sensitivity)
            .putInt("ffcap", (ffCapMs / 1000).toInt())
            .putBoolean("auto", autoBox.isChecked)
            .putBoolean("roku", useRoku)
            .putString("rokuIp", rokuIpEdit.text.toString().trim())
            .putInt("remote", remoteSpinner.selectedItemPosition)
            .putString("brand", brandName)
            .putString("set", activeSet?.id ?: "")
            .apply()
    }

    // ------------------------------------------------------------ UI helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) g.setStroke(dp(1), stroke)
        return g
    }

    private fun text(s: String, size: Float = 14f, color: Int = INK, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun put(parent: LinearLayout, v: View, top: Int = 10, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        lp.topMargin = dp(top)
        parent.addView(v, lp)
    }

    private fun card(parent: LinearLayout, title: String?): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(Color.WHITE, 14, LINE)
        c.setPadding(dp(14), dp(14), dp(14), dp(14))
        put(parent, c, 12)
        if (title != null) {
            val t = text(title.uppercase(Locale.US), 12f, MUTED, true)
            t.letterSpacing = 0.08f
            c.addView(t)
        }
        return c
    }

    private fun styledButton(label: String, primary: Boolean): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 15f
        b.setTextColor(if (primary) Color.WHITE else INK)
        val bg = rounded(if (primary) ACCENT else Color.WHITE, 10, if (primary) null else LINE)
        b.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33000000")), bg, null)
        b.minHeight = dp(48)
        b.minimumHeight = dp(48)
        b.stateListAnimator = null
        return b
    }

    private fun field(hint: String, mono: Boolean = true): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setSingleLine()
        e.textSize = 15f
        e.setTextColor(INK)
        if (mono) e.typeface = Typeface.MONOSPACE
        e.background = rounded(Color.WHITE, 8, LINE)
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        return e
    }

    private fun hexField(hint: String): EditText {
        val e = field(hint)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        return e
    }

    private fun hex(v: Long): String =
        if (v == 0L) "" else String.format(Locale.US, "%08X", v)

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(32))

        // header with the logo
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.background = rounded(NAVY, 18)
        header.setPadding(dp(14), dp(12), dp(16), dp(12))
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_launcher_foreground)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        header.addView(logo, LinearLayout.LayoutParams(dp(72), dp(72)))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(text(getString(R.string.app_name), 24f, Color.WHITE, true))
        titles.addView(text(getString(R.string.tagline), 14f, Color.parseColor("#9FB3C8")))
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = dp(8)
        header.addView(titles, tlp)
        col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // camera preview
        preview = PreviewView(this)
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.setBackgroundColor(NAVY)
        preview.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
            }
        }
        preview.clipToOutline = true
        put(col, preview, 12, dp(220))

        // status
        statusCard = LinearLayout(this)
        statusCard.orientation = LinearLayout.VERTICAL
        statusCard.background = rounded(TINT_SHOW, 14)
        statusCard.setPadding(dp(16), dp(12), dp(16), dp(12))
        put(col, statusCard, 12)
        statusView = text("SHOW", 40f, TEAL, true)
        statusCard.addView(statusView)
        statsView = text("Not watching yet.", 13f, MUTED)
        statsView.typeface = Typeface.MONOSPACE
        statusCard.addView(statsView)

        startBtn = styledButton("Start watching", true)
        startBtn.setOnClickListener { if (running) stopRun() else begin() }
        put(col, startBtn, 12)
        put(col, text("Prop the phone up so the camera fills its view with the TV, plug it in, and keep this screen open.", 13f, MUTED), 6)

        // what are you watching + sensitivity
        val watchCard = card(col, "What are you watching?")
        val modeGroup = RadioGroup(this)
        val recBtn = RadioButton(this)
        recBtn.text = "Recorded show (fast-forward the ad)"
        recBtn.id = View.generateViewId()
        val liveBtn = RadioButton(this)
        liveBtn.text = "Live TV (mute during the ad)"
        liveBtn.id = View.generateViewId()
        modeGroup.addView(recBtn)
        modeGroup.addView(liveBtn)
        val savedLive = prefs.getString("mode", "recorded") == "live"
        modeGroup.check(if (savedLive) liveBtn.id else recBtn.id)
        detector.mode = if (savedLive) Mode.LIVE else Mode.RECORDED
        modeGroup.setOnCheckedChangeListener { _, id ->
            detector.mode = if (id == recBtn.id) Mode.RECORDED else Mode.LIVE
        }
        put(watchCard, modeGroup, 6)
        put(watchCard, text("Sensitivity (higher flags more as ads)", 14f, INK, true), 14)
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = prefs.getInt("sens", 50)
        detector.sensitivity = seek.progress
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                detector.sensitivity = p
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        put(watchCard, seek, 4)

        val capLabel = text("", 14f, INK, true)
        put(watchCard, capLabel, 14)
        val capSeek = SeekBar(this)
        capSeek.max = 105
        val savedCap = prefs.getInt("ffcap", 45).coerceIn(15, 120)
        capSeek.progress = savedCap - 15
        ffCapMs = savedCap * 1000L
        capLabel.text = "Longest fast-forward: $savedCap s"
        capSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                ffCapMs = (p + 15) * 1000L
                capLabel.text = "Longest fast-forward: " + (p + 15) + " s"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        put(watchCard, capSeek, 4)
        put(watchCard, text("Fast-forward stops after this long even if the show has not been spotted. Lower it if the show gets skipped.", 12f, MUTED), 4)

        // TV control
        val tvCard = card(col, "Your TV")
        val ctlGroup = RadioGroup(this)
        val irBtn = RadioButton(this)
        irBtn.text = "IR blaster (any TV)"
        irBtn.id = View.generateViewId()
        val rokuBtn = RadioButton(this)
        rokuBtn.text = "Roku over Wi-Fi"
        rokuBtn.id = View.generateViewId()
        ctlGroup.addView(irBtn)
        ctlGroup.addView(rokuBtn)
        val savedRoku = prefs.getBoolean("roku", false)
        ctlGroup.check(if (savedRoku) rokuBtn.id else irBtn.id)
        useRoku = savedRoku
        ctlGroup.setOnCheckedChangeListener { _, id ->
            useRoku = (id == rokuBtn.id)
            refreshControlUi()
        }
        put(tvCard, ctlGroup, 6)

        // IR: brand, code set, Find my TV, manual codes
        irBox = LinearLayout(this)
        irBox.orientation = LinearLayout.VERTICAL
        put(tvCard, irBox, 6)
        put(irBox, text("TV brand", 14f, INK, true), 6)
        brandSpinner = Spinner(this)
        brandSpinner.adapter = spinnerAdapter(listOf(MANUAL_LABEL))
        put(irBox, brandSpinner, 2)

        setBox = LinearLayout(this)
        setBox.orientation = LinearLayout.VERTICAL
        put(irBox, setBox, 6)
        put(setBox, text("Code set (try another if a button does nothing)", 14f, INK, true), 0)
        setSpinner = Spinner(this)
        put(setBox, setSpinner, 2)

        findBtn = styledButton("Find my TV", false)
        findBtn.setOnClickListener { findClicked() }
        put(irBox, findBtn, 10)
        put(irBox, text("Find my TV tries this brand's mute codes while listening. Have the TV playing with sound and the phone aimed at it. Some codes may flash a menu.", 12f, MUTED), 4)

        manualBox = LinearLayout(this)
        manualBox.orientation = LinearLayout.VERTICAL
        put(irBox, manualBox, 8)
        remoteSpinner = Spinner(this)
        remoteSpinner.adapter = spinnerAdapter(Remotes.all.map { it.name })
        remoteSpinner.setSelection(prefs.getInt("remote", 0).coerceIn(0, Remotes.all.size - 1))
        put(manualBox, remoteSpinner, 0)
        muteEdit = hexField("Mute (8 hex digits)")
        playEdit = hexField("Play (8 hex digits)")
        ffEdit = hexField("Fast-forward (8 hex digits)")
        put(manualBox, text("Mute", 13f, MUTED), 8)
        put(manualBox, muteEdit, 2)
        put(manualBox, text("Play", 13f, MUTED), 8)
        put(manualBox, playEdit, 2)
        put(manualBox, text("Fast-forward", 13f, MUTED), 8)
        put(manualBox, ffEdit, 2)
        remoteSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val r = Remotes.all[pos]
                muteEdit.setText(hex(r.mute))
                playEdit.setText(hex(r.play))
                ffEdit.setText(hex(r.ff))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        brandSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                onBrandChosen(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        setSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val d = db ?: return
                val bp = brandSpinner.selectedItemPosition
                if (bp <= 0 || bp > d.brands.size) return
                val sets = d.brands[bp - 1].sets
                if (pos in sets.indices) activeSet = sets[pos]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Roku
        rokuBox = LinearLayout(this)
        rokuBox.orientation = LinearLayout.VERTICAL
        put(tvCard, rokuBox, 6)
        put(rokuBox, text("Roku IP address (Roku Settings > Network > About), or tap Find Roku", 14f, INK, true), 6)
        rokuIpEdit = field("for example 192.168.1.50")
        rokuIpEdit.setText(prefs.getString("rokuIp", "") ?: "")
        put(rokuBox, rokuIpEdit, 4)
        val findRoku = styledButton("Find Roku", false)
        findRoku.setOnClickListener {
            log("Looking for a Roku on Wi-Fi...")
            roku.discover(this) { ip ->
                runOnUiThread {
                    if (ip != null) {
                        rokuIpEdit.setText(ip)
                        log("Found a Roku at $ip.")
                    } else {
                        log("No Roku found. Type its IP address instead.")
                    }
                }
            }
        }
        put(rokuBox, findRoku, 8)
        put(rokuBox, text("If a Roku key does nothing, open Roku Settings > System > Advanced system settings > Control by mobile apps and allow network access.", 12f, MUTED), 6)

        // test buttons
        put(tvCard, text("Test with the phone aimed at the TV", 14f, INK, true), 14)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (k in listOf(Key.MUTE, Key.PLAY, Key.FF)) {
            val b = styledButton(if (k == Key.FF) "FF" else k.label, false)
            b.setOnClickListener { send(k, true) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = dp(6)
            row.addView(b, lp)
        }
        put(tvCard, row, 6)

        autoBox = CheckBox(this)
        autoBox.text = "Send commands automatically when an ad starts and ends"
        autoBox.isChecked = prefs.getBoolean("auto", false)
        put(tvCard, autoBox, 12)

        // log and debug
        val logCard = card(col, "Log")
        logView = text("", 12f, INK)
        logView.typeface = Typeface.MONOSPACE
        put(logCard, logView, 6)
        val copyBtn = styledButton("Copy debug data", false)
        copyBtn.setOnClickListener { copyDebug() }
        put(logCard, copyBtn, 12)
        put(logCard, text("After a bad stretch, tap this and paste the result into your chat with Claude.", 12f, MUTED), 4)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(PAGE)
        scroll.addView(col)
        refreshControlUi()
        return scroll
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun refreshControlUi() {
        irBox.visibility = if (useRoku) View.GONE else View.VISIBLE
        rokuBox.visibility = if (useRoku) View.VISIBLE else View.GONE
        val manual = brandSpinner.selectedItemPosition <= 0
        manualBox.visibility = if (manual) View.VISIBLE else View.GONE
        setBox.visibility = if (manual) View.GONE else View.VISIBLE
        findBtn.visibility = if (manual) View.GONE else View.VISIBLE
    }

    private fun populateBrands() {
        val d = db
        val names = ArrayList<String>()
        names.add(MANUAL_LABEL)
        if (d != null) for (b in d.brands) names.add(b.name)
        brandSpinner.adapter = spinnerAdapter(names)
        val savedBrand = prefs.getString("brand", "") ?: ""
        val at = if (savedBrand.isEmpty()) -1 else names.indexOf(savedBrand)
        pendingSetId = if (at > 0) prefs.getString("set", "") else null
        brandSpinner.setSelection(if (at > 0) at else 0)
        refreshControlUi()
    }

    private fun onBrandChosen(pos: Int) {
        val d = db
        if (pos <= 0 || d == null || pos > d.brands.size) {
            activeSet = null
        } else {
            val sets = d.brands[pos - 1].sets
            setSpinner.adapter = spinnerAdapter(sets.map { it.summary() })
            var best = -1
            val saved = pendingSetId
            if (!saved.isNullOrEmpty()) best = sets.indexOfFirst { it.id == saved }
            pendingSetId = null
            if (best < 0) best = sets.indexOfFirst { it.play >= 0 && it.ff >= 0 }
            if (best < 0) best = 0
            setSpinner.setSelection(best)
            activeSet = sets[best]
        }
        refreshControlUi()
    }

    // ----------------------------------------------------------- permissions

    private fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun begin() {
        if (finding) stopFind("Search stopped.")
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isEmpty()) startRun() else permLauncher.launch(need.toTypedArray())
    }

    // ------------------------------------------------------------- running

    private fun startRun() {
        if (running) return
        detector.reset()
        cutCount.set(0)
        dbNow = null
        logoNow = false
        diffNow = 0f
        debugRows.clear()
        runStart = SystemClock.elapsedRealtime()
        running = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = "Stop watching"

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                bindCamera(future.get())
            } catch (e: Exception) {
                log("Camera failed: ${e.message}")
                stopRun()
            }
        }, ContextCompat.getMainExecutor(this))

        if (granted(Manifest.permission.RECORD_AUDIO)) {
            val meter = AudioMeter { v -> dbNow = v }
            meter.start()
            audio = meter
        } else {
            log("No microphone permission. Using the camera only.")
        }
        log("Watching.")
        main.postDelayed(tick, 500)
    }

    private fun bindCamera(p: ProcessCameraProvider) {
        provider = p
        val exec = Executors.newSingleThreadExecutor()
        executor = exec

        val previewUse = Preview.Builder().build()
        previewUse.setSurfaceProvider(preview.surfaceProvider)

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(exec, FrameAnalyzer { cut, logo, diff ->
            if (cut) cutCount.incrementAndGet()
            logoNow = logo
            diffNow = diff
        })

        p.unbindAll()
        p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis)
    }

    private fun stopRun() {
        val wasInAd = detector.inAd
        running = false
        main.removeCallbacks(tick)
        audio?.stop()
        audio = null
        provider?.unbindAll()
        executor?.shutdown()
        executor = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = "Start watching"
        if (wasInAd) onAdChange(false, " (stopped)")
        detector.reset()
        showStatus(false)
        statsView.text = "Not watching."
        log("Stopped.")
    }

    private fun showStatus(ad: Boolean) {
        statusView.text = if (ad) "AD" else "SHOW"
        statusView.setTextColor(if (ad) RED else TEAL)
        statusCard.background = rounded(if (ad) TINT_AD else TINT_SHOW, 14)
    }

    private fun step() {
        val db = dbNow
        dbNow = null
        val cutNow = cutCount.getAndSet(0) > 0
        val logo = logoNow
        val diff = diffNow
        val r = detector.step(AdDetector.Step(cutNow, logo, db))
        if (r.changed) {
            onAdChange(r.inAd, "")
        } else if (r.inAd && detector.mode == Mode.RECORDED &&
            SystemClock.elapsedRealtime() - adStart > ffCapMs
        ) {
            detector.forceExit()
            onAdChange(false, " (fast-forward time limit)")
        }

        val ad = detector.inAd
        showStatus(ad)
        val vol = if (r.dbDelta == null) "n/a" else String.format(Locale.US, "%+.1f dB", r.dbDelta)
        statsView.text = String.format(
            Locale.US,
            "cuts in 6s: %d   volume: %s   logo: %d%%\nscore %.2f   threshold %.2f   change %.1f",
            r.cuts, vol, r.logoPct, r.score, r.threshold, diff
        )

        val t = (SystemClock.elapsedRealtime() - runStart) / 1000.0
        val dbText = if (db == null) "" else String.format(Locale.US, "%.1f", db)
        val deltaText = if (r.dbDelta == null) "" else String.format(Locale.US, "%.1f", r.dbDelta)
        debugRows.add(
            String.format(
                Locale.US, "%.1f,%.1f,%d,%d,%s,%d,%d,%s,%.2f,%d",
                t, diff, if (cutNow) 1 else 0, if (logo) 1 else 0, dbText,
                r.cuts, r.logoPct, deltaText, r.score, if (ad) 1 else 0
            )
        )
        while (debugRows.size > 240) debugRows.removeAt(0)
    }

    private fun onAdChange(inAd: Boolean, note: String) {
        if (inAd) {
            adStart = SystemClock.elapsedRealtime()
            log("Ad started.")
            if (detector.mode == Mode.RECORDED) send(Key.FF, false) else send(Key.MUTE, false)
        } else {
            log("Show is back.$note")
            if (detector.mode == Mode.RECORDED) send(Key.PLAY, false) else send(Key.MUTE, false)
        }
    }

    private fun copyDebug() {
        if (debugRows.isEmpty()) {
            log("No data yet. Start watching first.")
            return
        }
        val header = "t,change,cut,logo,mic_db,cuts_6s,logo_pct,vol_delta,score,in_ad"
        val text = "mode=${detector.mode} sensitivity=${detector.sensitivity}\n" +
            header + "\n" + debugRows.joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("debug data", text))
        Toast.makeText(this, "Copied " + debugRows.size + " rows", Toast.LENGTH_SHORT).show()
        log("Copied " + debugRows.size + " rows of debug data.")
    }

    // ------------------------------------------------------------- sending

    private fun send(key: Key, force: Boolean) {
        if (!force && !autoBox.isChecked) {
            log(key.label + " (not sent, automatic sending is off)")
            return
        }
        if (useRoku) {
            val ip = rokuIpEdit.text.toString().trim()
            if (!roku.valid(ip)) {
                log(key.label + ": enter the Roku's IP address first.")
                return
            }
            roku.host = ip
            roku.keypress(key.rokuKey) { ok, msg ->
                runOnUiThread { log(key.label + " via Roku: " + (if (ok) "sent." else "failed ($msg).")) }
            }
            return
        }
        if (!ir.available) {
            log(key.label + ": this phone reports no IR blaster.")
            return
        }
        val set = activeSet
        val d = db
        if (set != null && d != null) {
            val idx = when (key) {
                Key.MUTE -> set.mute
                Key.PLAY -> set.play
                Key.FF -> set.ff
            }
            val raw = d.pattern(idx)
            if (raw == null) {
                log(key.label + ": this code set has no such button. Try another set.")
                return
            }
            log(if (ir.sendRaw(raw.freq, raw.timings)) key.label + " sent." else key.label + " failed to send.")
            return
        }
        val field = when (key) {
            Key.MUTE -> muteEdit
            Key.PLAY -> playEdit
            Key.FF -> ffEdit
        }
        val proto = Remotes.all[remoteSpinner.selectedItemPosition].proto
        val text = field.text.toString().trim().removePrefix("0x").removePrefix("0X")
        val value = text.toLongOrNull(16)
        if (value == null || value < 0L || value > 0xFFFFFFFFL) {
            log(key.label + ": enter a code as 8 hex digits.")
            return
        }
        log(if (ir.send(proto, value)) key.label + " sent." else key.label + " failed to send.")
    }

    // --------------------------------------------------------- Find my TV

    private fun findClicked() {
        if (finding) {
            stopFind("Search stopped.")
            return
        }
        if (running) {
            log("Stop watching before searching for your TV.")
            return
        }
        val d = db
        val pos = brandSpinner.selectedItemPosition
        if (d == null || pos <= 0 || pos > d.brands.size) {
            log("Pick a TV brand first.")
            return
        }
        if (!ir.available) {
            log("This phone reports no IR blaster.")
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            pendingFind = true
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        startFind()
    }

    private fun startFind() {
        val d = db ?: return
        val pos = brandSpinner.selectedItemPosition
        if (pos <= 0 || pos > d.brands.size) return
        val brand = d.brands[pos - 1]
        findBrand = brand
        findList = d.uniqueMutes(brand)
        findIdx = 0
        findBaseline = Double.NaN
        recentDb.clear()
        findToken++
        finding = true
        findBtn.text = "Stop searching"
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            val meter = AudioMeter { v ->
                recentDb.add(v)
                while (recentDb.size > 8) recentDb.removeAt(0)
            }
            meter.start()
            findAudio = meter
        }
        log("Searching " + brand.name + ": " + findList.size + " codes. Keep the TV playing with sound.")
        postFind(1500) { findNext() }
    }

    private fun postFind(delayMs: Long, action: () -> Unit) {
        val token = findToken
        main.postDelayed({
            if (finding && token == findToken) action()
        }, delayMs)
    }

    private fun avgLast(n: Int): Double {
        val l = recentDb.toList()
        if (l.size < n) return Double.NaN
        var s = 0.0
        for (i in l.size - n until l.size) s += l[i]
        return s / n
    }

    private fun findNext() {
        val d = db
        val brand = findBrand
        if (d == null || brand == null) {
            stopFind("Search stopped.")
            return
        }
        if (findIdx >= findList.size) {
            stopFind("No " + brand.name + " code muted the TV. Try another brand or code set.")
            return
        }
        val idx = findList[findIdx]
        val raw = d.pattern(idx)
        if (raw == null) {
            findIdx++
            postFind(50) { findNext() }
            return
        }
        findBaseline = avgLast(2)
        ir.sendRaw(raw.freq, raw.timings)
        log("Trying code " + (findIdx + 1) + " of " + findList.size + "...")
        postFind(1700) { evaluateFind(idx) }
    }

    private fun evaluateFind(idx: Int) {
        val after = avgLast(2)
        val base = findBaseline
        val heard = !base.isNaN() && !after.isNaN() && base > -45.0
        if (heard) {
            if (after < base - 8.0) {
                confirmFind(idx)
            } else {
                findIdx++
                postFind(300) { findNext() }
            }
        } else {
            // No usable microphone reading, so ask the person.
            confirmFind(idx)
        }
    }

    private fun confirmFind(idx: Int) {
        AlertDialog.Builder(this)
            .setTitle("Did the TV mute?")
            .setMessage("Code " + (findIdx + 1) + " of " + findList.size)
            .setCancelable(false)
            .setPositiveButton("Yes") { _, _ -> acceptFind(idx) }
            .setNegativeButton("No") { _, _ ->
                findIdx++
                postFind(300) { findNext() }
            }
            .setNeutralButton("Stop") { _, _ -> stopFind("Search stopped.") }
            .show()
    }

    private fun acceptFind(idx: Int) {
        val d = db
        val brand = findBrand
        if (d == null || brand == null) {
            stopFind("Search stopped.")
            return
        }
        var chosen = brand.sets.firstOrNull { it.mute == idx && it.play >= 0 && it.ff >= 0 }
        if (chosen == null) chosen = brand.sets.firstOrNull { it.mute == idx }
        if (chosen != null) {
            val at = brand.sets.indexOf(chosen)
            setSpinner.setSelection(at)
            activeSet = chosen
        }
        // Mute is a toggle, so send it once more to bring the sound back.
        val raw = d.pattern(idx)
        if (raw != null) main.postDelayed({ ir.sendRaw(raw.freq, raw.timings) }, 800)
        val note = if (chosen == null) "" else
            " Using " + chosen.id + (if (chosen.ff < 0) ". It has no fast-forward code, so try another set." else ".")
        stopFind("Found your TV's mute code." + note)
    }

    private fun stopFind(msg: String) {
        findToken++
        finding = false
        findAudio?.stop()
        findAudio = null
        findBtn.text = "Find my TV"
        log(msg)
    }

    private fun log(s: String) {
        logLines.add(clock.format(Date()) + "  " + s)
        while (logLines.size > 40) logLines.removeAt(0)
        logView.text = logLines.joinToString("\n")
    }

    companion object {
        private const val MANUAL_LABEL = "Manual codes (Samsung, LG, custom)"
    }
}
