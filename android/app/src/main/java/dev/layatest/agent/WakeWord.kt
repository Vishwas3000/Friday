package dev.layatest.agent

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

private const val RATE = 16_000
private const val FRAME = 1_280          // 80 ms per step
private const val MEL_IN = FRAME + 480   // the reference adds 3 frames of left context: 1,760 samples -> 8 mel frames
private const val MEL_NEW = 8
private const val MEL_BINS = 32
private const val MEL_WIN = 76           // mel frames per embedding
private const val EMB_DIM = 96
private const val EMB_WIN = 16           // embeddings per wake score (1.28 s)
private const val THRESHOLD = 0.5f
private const val PATIENCE = 2           // frames in a row: bare "friday" fired 4/12 TTS voices at 1, 1/12 at 2 (research/16 §8)
private const val REFRACTORY_MS = 2_000L
private const val MODEL = "hey_friday.onnx"  // also fires on "hi friday" (research/16 §8)

/**
 * openWakeWord (D-035, research/16) in the ORT Friday already ships. Every 80 ms: melspectrogram.onnx on the
 * last 1,760 samples -> 8 mel frames x 32, embedding_model.onnx on the last 76 mel frames -> one 96-d
 * embedding, and the wake model on the last 16 embeddings -> a score in 0..1. Audio lives only in these
 * arrays: nothing is written to disk or logs, and the app has no INTERNET permission.
 */
class WakeDetector(ctx: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    // one thread each, to save power; XNNPACK cut the listening thread from ~50% to ~33% of a little core with
    // identical scores (research/16 §8)
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1); setCPUArenaAllocator(false); addXnnpack(mapOf("intra_op_num_threads" to "1"))
    }
    private val mel = Model(ctx, "melspectrogram.onnx", 1, MEL_IN.toLong())
    private val emb = Model(ctx, "embedding_model.onnx", 1, MEL_WIN.toLong(), MEL_BINS.toLong(), 1)
    private val wake = Model(ctx, MODEL, 1, EMB_WIN.toLong(), EMB_DIM.toLong())

    private val raw = FloatArray(MEL_IN)                          // int16 values as floats; never /32768 (research/16 §1)
    internal val mels = FloatArray(MEL_WIN * MEL_BINS) { 1f }     // starts as ones, like the reference
    private val embs = FloatArray(EMB_WIN * EMB_DIM)
    private var pushed = 0

    /** One 80 ms frame in; its wake score out. 0 until 16 embeddings of real audio fill the window. */
    fun push(frame: ShortArray): Float {
        require(frame.size == FRAME)
        System.arraycopy(raw, FRAME, raw, 0, MEL_IN - FRAME)
        for (i in 0 until FRAME) raw[MEL_IN - FRAME + i] = frame[i].toFloat()
        val spec = mel.run(raw)
        check(spec.remaining() == MEL_NEW * MEL_BINS)
        System.arraycopy(mels, MEL_NEW * MEL_BINS, mels, 0, mels.size - MEL_NEW * MEL_BINS)
        for (i in mels.size - MEL_NEW * MEL_BINS until mels.size) mels[i] = spec.get() / 10f + 2f  // the reference's transform
        val e = emb.run(mels)
        check(e.remaining() == EMB_DIM)
        System.arraycopy(embs, EMB_DIM, embs, 0, embs.size - EMB_DIM)
        e.get(embs, embs.size - EMB_DIM, EMB_DIM)
        val score = wake.run(embs).get(0)
        return if (++pushed < EMB_WIN) 0f else score
    }

    override fun close() {
        mel.close(); emb.close(); wake.close(); opts.close()
        raw.fill(0f)
    }

    /** A single-input model whose input tensor wraps a direct buffer, so a frame allocates no input tensors. */
    private inner class Model(ctx: Context, file: String, vararg shape: Long) {
        val session: OrtSession = env.createSession(ctx.assets.open("wakeword/$file").use { it.readBytes() }, opts)
        val buf: FloatBuffer = ByteBuffer.allocateDirect(shape.reduce { a, b -> a * b }.toInt() * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val input: OnnxTensor = OnnxTensor.createTensor(env, buf, shape)
        val name: String = session.inputNames.first()

        fun run(x: FloatArray): FloatBuffer {
            buf.clear(); buf.put(x)
            session.run(mapOf(name to input)).use { return (it.get(0) as OnnxTensor).floatBuffer }
        }

        fun close() { input.close(); session.close() }
    }
}

/**
 * When Friday listens (D-035). Owner, 2026-10-17: no always-on mic (the green mic dot shows whenever an app
 * listens), so it's opt-in and only while the phone is plugged in, never during a call. Off or unplugged,
 * the AudioRecord is released and the dot goes away. It runs inside AgentService: the accessibility binding
 * gives the process the mic capability, so it needs no foreground service (research/16 §2.2, §8).
 */
class WakeWord(private val svc: AgentService) : AutoCloseable {
    private val audio = svc.getSystemService(AudioManager::class.java)
    private val events = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = update() }
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var listener: Listener? = null

    // debug stats for the bridge "wake" command, reset at each read; no audio or scores are logged or stored
    @Volatile private var frames = 0
    @Volatile private var busyNs = 0L
    @Volatile private var rmsSum = 0.0
    @Volatile private var maxScore = 0f
    @Volatile private var detections = 0
    @Volatile private var lastError: String? = null
    private var lastWake = 0L

    fun start() {
        svc.registerReceiver(events, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, Context.RECEIVER_NOT_EXPORTED)
        // shortcut: API 30 has no mode listener, so calls don't pause listening there; Friday runs on Android 16
        if (Build.VERSION.SDK_INT >= 31) {
            modeListener = AudioManager.OnModeChangedListener { update() }.also { audio.addOnModeChangedListener(svc.mainExecutor, it) }
        }
        update()
    }

    @Synchronized fun update() {
        val want = isOn(svc) && plugged() && audio.mode == AudioManager.MODE_NORMAL  // calls: IN_CALL, IN_COMMUNICATION
        val l = listener
        if (want && (l == null || l.stopped)) listener = Listener(l).also { it.start() }
        if (!want) l?.stopped = true
    }

    private fun plugged() = (svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    /** "Hey Friday": a short buzz, then the request box with the keyboard up. Not while Friday is busy. */
    private fun woke() {
        detections++
        if (svc.busy) return
        val buzz = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        val vibrator = svc.getSystemService(Vibrator::class.java)
        // a plain click counts as touch feedback, which the owner has off (measured: ignored_for_settings);
        // accessibility vibrations are exempt from the haptics settings
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(buzz, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
        else vibrator.vibrate(buzz)
        svc.startActivity(Intent(svc, MainActivity::class.java)  // it opens ready to type
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Bridge "wake": optionally {"on": bool} flips the toggle; returns state and stats since the last call. */
    fun command(c: JSONObject): JSONObject {
        if (c.has("on")) setOn(svc, c.getBoolean("on")) else update()
        val l = listener?.takeUnless { it.stopped }
        val n = frames
        val out = JSONObject().put("on", isOn(svc)).put("listening", l != null).put("frames", n)
            .put("ms_per_frame", if (n > 0) busyNs / 1e6 / n else 0.0).put("rms", if (n > 0) rmsSum / n else 0.0)
            .put("max_score", maxScore.toDouble()).put("detections", detections)
            .put("silenced", l?.record?.activeRecordingConfiguration?.isClientSilenced ?: JSONObject.NULL)
            .put("error", lastError ?: JSONObject.NULL)
        frames = 0; busyNs = 0; rmsSum = 0.0; maxScore = 0f
        return out
    }

    override fun close() {
        runCatching { svc.unregisterReceiver(events) }
        if (Build.VERSION.SDK_INT >= 31) modeListener?.let { audio.removeOnModeChangedListener(it) }
        listener?.stopped = true
    }

    /** One capture session: 16 kHz mono from VOICE_RECOGNITION, scored frame by frame; joins [prev] first. */
    private inner class Listener(private val prev: Thread?) : Thread("friday-wake") {
        @Volatile var stopped = false
        @Volatile var record: AudioRecord? = null

        @SuppressLint("MissingPermission")  // update() only starts a Listener once RECORD_AUDIO is granted
        override fun run() {
            prev?.join()
            val frame = ShortArray(FRAME)
            var det: WakeDetector? = null
            var rec: AudioRecord? = null
            try {
                det = WakeDetector(svc)
                if (stopped) return  // switched off while the models loaded: never open the mic
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT), RATE * 2))  // 1 s of slack
                record = rec
                rec.startRecording()
                var held = 0
                while (!stopped && rec.read(frame, 0, FRAME) == FRAME) {
                    val t = SystemClock.elapsedRealtimeNanos()
                    val s = det.push(frame)
                    busyNs += SystemClock.elapsedRealtimeNanos() - t
                    var e = 0.0
                    for (x in frame) e += x * x.toDouble()
                    rmsSum += sqrt(e / FRAME)
                    frames++
                    if (s > maxScore) maxScore = s
                    held = if (s >= THRESHOLD) held + 1 else 0
                    if (held >= PATIENCE && SystemClock.elapsedRealtime() - lastWake > REFRACTORY_MS) {
                        lastWake = SystemClock.elapsedRealtime()
                        held = 0
                        svc.main.post { woke() }
                    }
                }
            } catch (e: Exception) {
                lastError = e.toString()  // the next update() (power, call end, switch) tries again
            } finally {
                stopped = true
                record = null
                rec?.run { runCatching { stop() }; release() }
                det?.close()
                frame.fill(0)
            }
        }
    }

    companion object {
        private const val KEY = "wake_charging"  // SharedPreferences "friday"

        /** The owner's opt-in switch in MainActivity: "Listen for "Hey Friday" while charging". Off by default. */
        fun wanted(ctx: Context) = ctx.getSharedPreferences("friday", Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun isOn(ctx: Context) = wanted(ctx) &&
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun setOn(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("friday", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
            AgentService.instance?.wake?.update()
        }
    }
}

/** Bridge "wake_test": score a WAV in filesDir; 2 s of silence first, so both ports' start-up state is flushed. */
fun wakeTest(ctx: Context, wav: File): JSONObject {
    val pcm = readWav(wav.readBytes())
    val audio = ShortArray(2 * RATE + pcm.size + RATE).also { pcm.copyInto(it, 2 * RATE) }
    WakeDetector(ctx).use { d ->
        val t = SystemClock.elapsedRealtimeNanos()
        val s = FloatArray(audio.size / FRAME) { d.push(audio.copyOfRange(it * FRAME, (it + 1) * FRAME)) }
        val ms = (SystemClock.elapsedRealtimeNanos() - t) / 1e6 / s.size
        val held = (0..s.size - PATIENCE).maxOf { i -> (i until i + PATIENCE).minOf { s[it] } }  // what triggering sees
        return JSONObject().put("score", s.max().toDouble()).put("held", held.toDouble()).put("fires", held >= THRESHOLD)
            .put("frames", s.size).put("ms_per_frame", ms)
    }
}

/** Samples of a 16 kHz mono 16-bit PCM WAV. Walks the chunks: macOS `say` puts a JUNK chunk before "fmt ". */
internal fun readWav(bytes: ByteArray): ShortArray {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
    var p = 12
    var pcm16k = false
    while (p + 8 <= bytes.size) {
        val id = String(bytes, p, 4)
        val len = b.getInt(p + 4)
        require(len >= 0) { "bad WAV chunk" }
        if (id == "fmt ") pcm16k = b.getShort(p + 8).toInt() == 1 && b.getShort(p + 10).toInt() == 1 &&
            b.getInt(p + 12) == RATE && b.getShort(p + 22).toInt() == 16
        if (id == "data") {
            require(pcm16k) { "need 16 kHz mono 16-bit PCM" }
            return ShortArray(minOf(len, bytes.size - p - 8) / 2) { b.getShort(p + 8 + 2 * it) }
        }
        p += 8 + len + (len and 1)
    }
    error("no data chunk")
}

/** Shapes, the int16 scaling trap and the WAV reader, on synthetic audio (bridge "selfcheck"). */
fun wakeSelfCheck(ctx: Context): String {
    WakeDetector(ctx).use { d ->
        val tone = ShortArray(FRAME) { (3000 * sin(2 * PI * 440 * it / RATE)).toInt().toShort() }
        val s = FloatArray(EMB_WIN) { d.push(tone) }
        check(s.take(EMB_WIN - 1).all { it == 0f })       // no score until 16 real embeddings (1.28 s)
        check(s.last() in 0f..1f && s.last() < THRESHOLD)  // a tone isn't "hey friday"
        // int16 values in: this tone's newest mel frames peak near 11; divided by 32768 they'd peak near 2 [measured, Mac]
        check(d.mels.takeLast(MEL_NEW * MEL_BINS).max() > 6f)
    }
    val wav = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(52).put("WAVE".toByteArray())
        .put("JUNK".toByteArray()).putInt(4).putInt(0)
        .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(4).putShort(1).putShort(-2)
    check(readWav(wav.array()).contentEquals(shortArrayOf(1, -2)))
    return "ok"
}
