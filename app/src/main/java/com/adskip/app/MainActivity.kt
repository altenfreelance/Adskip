package com.adskip.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {

    private lateinit var ir: IrSender
    private val roku = RokuControl()
    private val detector = AdDetector()
    private val main = Handler(Looper.getMainLooper())

    private val cutCount = AtomicInteger(0)
    @Volatile private var logoNow = false
    @Volatile private var diffNow = 0f
    @Volatile private var dbNow: Double? = null

    private var running = false
    private var useRoku = false
    private var adStart = 0L
    private var runStart = 0L
    private var audio: AudioMeter? = null
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null

    private lateinit var preview: PreviewView
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button
    private lateinit var autoBox: CheckBox
    private lateinit var remoteSpinner: Spinner
    private lateinit var muteEdit: EditText
    private lateinit var playEdit: EditText
    private lateinit var ffEdit: EditText
    private lateinit var rokuIpEdit: EditText

    private val logLines = ArrayList<String>()
    private val debugRows = ArrayList<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (granted(Manifest.permission.CAMERA)) {
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
    }

    override fun onStop() {
        super.onStop()
        if (running) stopRun()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(32))

        fun add(v: View, top: Int = 12, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            lp.topMargin = dp(top)
            col.addView(v, lp)
        }

        fun label(s: String, size: Float = 14f, bold: Boolean = false): TextView {
            val t = TextView(this)
            t.text = s
            t.textSize = size
            if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
            return t
        }

        add(label("Ad Skip", 28f, true), 0)
        add(label("Prop the phone up so the camera fills its view with the TV, plug it in, and tap Start. Keep this screen open."), 4)

        preview = PreviewView(this)
        add(preview, 12, dp(240))

        statusView = label("SHOW", 36f, true)
        statusView.setTextColor(Color.rgb(14, 122, 107))
        add(statusView, 8)
        statsView = label("Not watching yet.", 13f)
        statsView.typeface = Typeface.MONOSPACE
        add(statsView, 0)

        startBtn = Button(this)
        startBtn.text = "Start watching"
        startBtn.setOnClickListener { if (running) stopRun() else begin() }
        add(startBtn, 12)

        add(label("What are you watching?", 14f, true), 20)
        val modeGroup = RadioGroup(this)
        val recBtn = RadioButton(this)
        recBtn.text = "Recorded show (fast-forward the ad)"
        recBtn.id = View.generateViewId()
        val liveBtn = RadioButton(this)
        liveBtn.text = "Live TV (mute during the ad)"
        liveBtn.id = View.generateViewId()
        modeGroup.addView(recBtn)
        modeGroup.addView(liveBtn)
        modeGroup.check(recBtn.id)
        modeGroup.setOnCheckedChangeListener { _, id ->
            detector.mode = if (id == recBtn.id) Mode.RECORDED else Mode.LIVE
        }
        add(modeGroup, 4)

        add(label("Sensitivity (higher flags more as ads)", 14f, true), 16)
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = 50
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                detector.sensitivity = p
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        add(seek, 4)

        add(label("How do you control the TV?", 14f, true), 20)
        val ctlGroup = RadioGroup(this)
        val irBtn = RadioButton(this)
        irBtn.text = "IR blaster (TV remote codes)"
        irBtn.id = View.generateViewId()
        val rokuBtn = RadioButton(this)
        rokuBtn.text = "Roku over Wi-Fi"
        rokuBtn.id = View.generateViewId()
        ctlGroup.addView(irBtn)
        ctlGroup.addView(rokuBtn)
        ctlGroup.check(irBtn.id)
        ctlGroup.setOnCheckedChangeListener { _, id -> useRoku = (id == rokuBtn.id) }
        add(ctlGroup, 4)

        add(label("Roku: IP address (Roku Settings > Network > About), or tap Find Roku"), 12)
        rokuIpEdit = EditText(this)
        rokuIpEdit.hint = "for example 192.168.1.50"
        rokuIpEdit.setSingleLine()
        rokuIpEdit.typeface = Typeface.MONOSPACE
        add(rokuIpEdit, 0)
        val findBtn = Button(this)
        findBtn.text = "Find Roku"
        findBtn.setOnClickListener {
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
        add(findBtn, 4)
        add(label("If a Roku key does nothing, check Roku Settings > System > Advanced system settings > Control by mobile apps, and allow network access."), 4)

        add(label("IR remote codes", 14f, true), 20)
        remoteSpinner = Spinner(this)
        remoteSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Remotes.all.map { it.name }
        )
        add(remoteSpinner, 4)

        muteEdit = hexField("Mute (8 hex digits)")
        playEdit = hexField("Play (8 hex digits)")
        ffEdit = hexField("Fast-forward (8 hex digits)")
        add(label("Mute"), 8)
        add(muteEdit, 0)
        add(label("Play"), 4)
        add(playEdit, 0)
        add(label("Fast-forward"), 4)
        add(ffEdit, 0)

        remoteSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val r = Remotes.all[pos]
                muteEdit.setText(hex(r.mute))
                playEdit.setText(hex(r.play))
                ffEdit.setText(hex(r.ff))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        add(label("Test each button with the phone aimed at the TV:"), 12)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val tests = listOf(
            Triple("Mute", muteEdit, "VolumeMute"),
            Triple("Play", playEdit, "Play"),
            Triple("FF", ffEdit, "Fwd")
        )
        for (item in tests) {
            val b = Button(this)
            b.text = item.first
            b.setOnClickListener { send(item.first, item.second, item.third, true) }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        add(row, 4)

        autoBox = CheckBox(this)
        autoBox.text = "Send commands automatically when an ad starts and ends"
        autoBox.isChecked = false
        add(autoBox, 12)

        add(label("Log", 14f, true), 20)
        logView = label("", 12f)
        logView.typeface = Typeface.MONOSPACE
        add(logView, 4)

        val copyBtn = Button(this)
        copyBtn.text = "Copy debug data"
        copyBtn.setOnClickListener { copyDebug() }
        add(copyBtn, 12)
        add(label("After a bad stretch, tap this and paste the result into your chat with Claude."), 4)

        val scroll = ScrollView(this)
        scroll.addView(col)
        return scroll
    }

    private fun hexField(hint: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setSingleLine()
        e.typeface = Typeface.MONOSPACE
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        return e
    }

    private fun hex(v: Long): String =
        if (v == 0L) "" else String.format(Locale.US, "%08X", v)

    // ----------------------------------------------------------- permissions

    private fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun begin() {
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
            val meter = AudioMeter { db -> dbNow = db }
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
        statusView.text = "SHOW"
        statusView.setTextColor(Color.rgb(14, 122, 107))
        statsView.text = "Not watching."
        log("Stopped.")
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
            SystemClock.elapsedRealtime() - adStart > 120_000L
        ) {
            detector.forceExit()
            onAdChange(false, " (fast-forward time limit)")
        }

        val ad = detector.inAd
        statusView.text = if (ad) "AD" else "SHOW"
        statusView.setTextColor(if (ad) Color.rgb(196, 55, 45) else Color.rgb(14, 122, 107))
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
            if (detector.mode == Mode.RECORDED) send("FAST_FORWARD", ffEdit, "Fwd", false)
            else send("MUTE", muteEdit, "VolumeMute", false)
        } else {
            log("Show is back.$note")
            if (detector.mode == Mode.RECORDED) send("PLAY", playEdit, "Play", false)
            else send("MUTE (restore)", muteEdit, "VolumeMute", false)
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
        cm.setPrimaryClip(ClipData.newPlainText("adskip debug", text))
        Toast.makeText(this, "Copied " + debugRows.size + " rows", Toast.LENGTH_SHORT).show()
        log("Copied " + debugRows.size + " rows of debug data.")
    }

    // ------------------------------------------------------------- sending

    private fun send(name: String, field: EditText, rokuKey: String, force: Boolean) {
        if (!force && !autoBox.isChecked) {
            log("$name (not sent, automatic sending is off)")
            return
        }
        if (useRoku) {
            val ip = rokuIpEdit.text.toString().trim()
            if (!roku.valid(ip)) {
                log("$name: enter the Roku's IP address first.")
                return
            }
            roku.host = ip
            roku.keypress(rokuKey) { ok, msg ->
                runOnUiThread { log("$name via Roku: " + (if (ok) "sent." else "failed ($msg).")) }
            }
            return
        }
        val proto = Remotes.all[remoteSpinner.selectedItemPosition].proto
        val text = field.text.toString().trim().removePrefix("0x").removePrefix("0X")
        val value = text.toLongOrNull(16)
        if (value == null || value < 0L || value > 0xFFFFFFFFL) {
            log("$name: enter a code as 8 hex digits.")
            return
        }
        if (!ir.available) {
            log("$name: this phone reports no IR blaster.")
            return
        }
        log(if (ir.send(proto, value)) "$name sent." else "$name failed to send.")
    }

    private fun log(s: String) {
        logLines.add(clock.format(Date()) + "  " + s)
        while (logLines.size > 40) logLines.removeAt(0)
        logView.text = logLines.joinToString("\n")
    }
}
