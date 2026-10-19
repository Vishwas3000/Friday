package dev.layatest.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import kotlin.math.abs

/**
 * Quick access (owner, 2026-10-18): a small round button floating over every app. Tap: Friday listens (red), and
 * what it hears shows at the top. Tap again: it runs that. If speech ended first, the button turns green and the
 * next tap runs it. Long-press: the typed request box. Drag it anywhere; it settles at the nearer side.
 *
 * Speech is Android's on-device recognizer only (createOnDeviceSpeechRecognizer): no audio leaves the phone,
 * and Friday still has no INTERNET permission. An accessibility overlay, so it needs no extra permission; it
 * steps aside while Friday works or is taught, so Friday's own taps never land on it. On/off in the main screen.
 */
class Bubble(private val svc: AgentService) {
    private enum class Mode { IDLE, LISTENING, HEARD }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val dm = svc.resources.displayMetrics
    private val size = (56 * dm.density).toInt()
    private val params = LayoutParams(size, size, LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dm.widthPixels - size
        y = dm.heightPixels / 3
    }
    private var view: TextView? = null
    private var strip: TextView? = null  // what Friday heard, at the top of the screen
    private var mode = Mode.IDLE
    private var recognizer: SpeechRecognizer? = null
    private var heard: String? = null
    private var runWhenHeard = false
    private val unsay = Runnable { strip?.let { runCatching { wm.removeView(it) } }; strip = null }

    fun show() = svc.main.post {
        if (view == null && isOn(svc)) view = create().also { runCatching { wm.addView(it, params) } }
    }

    fun hide() = svc.main.post {
        reset(null)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** Start listening without a tap: Reply on the answer card (D-061). */
    fun listenNow() = svc.main.post { if (view == null) show(); if (mode == Mode.IDLE) listen() }

    private fun tapped() = when (mode) {
        Mode.IDLE -> listen()
        Mode.LISTENING -> { runWhenHeard = true; recognizer?.stopListening() }  // onResults runs it
        Mode.HEARD -> run()
    }

    private fun listen() {
        if (svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return openBox(askMic = true)
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(svc)) {
            return say("Offline speech isn't installed: Settings › System › Languages › Speech › On-device recognition", 6000)
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(svc).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle) { first(b)?.let { say(it) } }
            override fun onResults(b: Bundle) {
                heard = first(b)
                when {
                    heard.isNullOrBlank() -> reset("Didn't catch that")
                    runWhenHeard -> run()
                    else -> { mode = Mode.HEARD; paint(); say("$heard  ·  tap to run") }
                }
            }
            override fun onError(error: Int) = reset(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> "The microphone is busy"
                else -> "Couldn't listen (error $error)"
            })
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(type: Int, b: Bundle?) {}
        })
        heard = null
        runWhenHeard = false
        mode = Mode.LISTENING
        paint()
        say("Listening…  tap again to run")
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    private fun first(b: Bundle) = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun run() {
        val q = heard
        reset(null)
        if (!q.isNullOrBlank()) { say(q, 2500); svc.runGoal(q) }
    }

    private fun reset(message: String?) {
        recognizer?.destroy()
        recognizer = null
        mode = Mode.IDLE
        heard = null
        paint()
        if (message != null) say(message, 2500) else unsay.run()
    }

    private fun openBox(askMic: Boolean = false) {
        svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.ASK_MIC, askMic))
    }

    /** One line at the top of the screen; [ms] > 0 takes it down after that long. */
    private fun say(text: String, ms: Long = 0) {
        svc.main.removeCallbacks(unsay)
        val t = strip ?: TextView(svc).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(40, 24, 40, 24)
            background = GradientDrawable().apply { setColor(0xEE202124.toInt()); cornerRadius = 36f }
        }.also { s ->
            strip = s
            runCatching {
                wm.addView(s, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (48 * dm.density).toInt()
                })
            }
        }
        t.text = text
        if (ms > 0) svc.main.postDelayed(unsay, ms)
    }

    private fun paint(v: TextView? = view) {
        v ?: return
        val (glyph, color) = when (mode) {
            Mode.IDLE -> "F" to 0xE61A73E8.toInt()       // blue
            Mode.LISTENING -> "●" to 0xE6D93025.toInt()  // red
            Mode.HEARD -> "▶" to 0xE61E8E3E.toInt()      // green
        }
        v.text = glyph
        (v.background as GradientDrawable).setColor(color)
    }

    @SuppressLint("ClickableViewAccessibility")  // taps go through performClick(), so TalkBack can still use it
    private fun create(): TextView = TextView(svc).apply {
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        elevation = 8 * dm.density
        contentDescription = "Friday: tap to talk, tap again to run, long-press to type, drag to move"
        setOnClickListener { tapped() }
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        var longPressed = false
        val longPress = Runnable { if (!dragged) { longPressed = true; openBox() } }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    dragged = false; longPressed = false
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragged || abs(dx) > slop || abs(dy) > slop) {
                        dragged = true
                        v.removeCallbacks(longPress)
                        params.x = startX + dx.toInt()
                        params.y = (startY + dy.toInt()).coerceIn(0, dm.heightPixels - size)
                        wm.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (dragged) {
                        params.x = if (params.x + size / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - size
                        wm.updateViewLayout(v, params)
                    } else if (!longPressed) v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            }
            true
        }
        paint(this)
    }

    companion object {
        private const val KEY = "bubble"
        fun isOn(c: Context) = c.getSharedPreferences("friday", Context.MODE_PRIVATE).getBoolean(KEY, false)
        fun setOn(c: Context, on: Boolean) = c.getSharedPreferences("friday", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
