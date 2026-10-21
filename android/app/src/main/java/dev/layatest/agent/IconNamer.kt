package dev.layatest.agent

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Icon labels step 4 (research/25-26, D-055): names an icon that is still unnamed after the free text sources
 * (resource-id words, [Meanings]) from its pixels. The model is trained on the GPU box (training/icons/) and pushed as
 * filesDir/icons.onnx + icons_labels.json; without both, this is off. A screen with new unnamed icons costs one
 * screenshot and one batch. A name counts only above the labels' threshold (set for ≥ 95% precision on held-out icons),
 * and "other" never names anything: a wrong name misleads Laya more than none. Every result, "no confident name"
 * included, is cached per app element in filesDir/icon_names.json, so each icon is classified once per pushed model;
 * "Delete all learned data" removes it. Screenshots and crops stay in memory (D-004), and none is taken while a money
 * app is on screen (they shoot black anyway, research/26). 2026-10-19.
 */
object IconNamer {
    const val SIZE = 64  // the model's input: 64x64 grey
    private const val TAG = "FridayIcons"
    private const val MODEL = "icons.onnx"
    private const val LABELS = "icons_labels.json"
    private var loaded: String? = null  // the pushed files [model] came from: a re-push loads again, a bad push is tried once
    private var model: Model? = null

    /** Same lock as classify: never close an ONNX session while its inference is using it. */
    @Synchronized internal fun release() {
        val previous = model ?: return
        model = null
        loaded = null
        previous.close()
    }

    /**
     * [cands] with their unnamed icons named, from the cache or the model; [note] gets a trace line when the model ran.
     * Nothing runs unless an unnamed icon is on screen, the model is on the phone and no money app is on screen.
     */
    fun relabel(svc: AgentService, pkg: String?, state: JSONObject, cands: List<ScreenCandidate>,
                beforeScreenshot: () -> Unit = {}, note: (String) -> Unit): List<ScreenCandidate> {
        val side = state.getJSONArray("screen").let { minOf(it.getInt(0), it.getInt(1)) } / 4
        val icons = cands.filter { isIcon(it, side) }
        if (icons.isEmpty()) return cands
        val stamp = stamp(svc) ?: return cands
        val wins = state.getJSONArray("windows")
        if ((0 until wins.length()).any { Safety.isMoneyApp(wins.getJSONObject(it).getJSONObject("root").optString("package")) }) return cands
        // An icon without an id is known by its place, which only holds still in the top and bottom bars: in scrolling
        // content another icon lands in the same box and would inherit the name. Those are named for this screen only.
        val height = state.getJSONArray("screen").getInt(1)
        fun kept(e: ScreenCandidate) = e.rid.isNotEmpty() || e.bounds[3] <= height * 0.15 || e.bounds[1] >= height * 0.85
        val known = cached(svc, stamp)
        val now = HashMap<String, String>()  // this screen's names, cached or not
        val unseen = icons.filter { !kept(it) || !known.has(Meanings.key(pkg, it)) }
        if (unseen.isNotEmpty()) classify(svc, stamp, unseen.map { it.bounds }, beforeScreenshot, note)?.let { words ->
            // icons sharing an id (a list's rows) keep a name only if the ones it was sure of agree: a generic id
            // ("image") on different icons names none of them
            val learned = unseen.indices.groupBy { Meanings.key(pkg, unseen[it]) }
                .mapValues { (_, idx) -> idx.mapNotNull { words[it] }.distinct().singleOrNull().orEmpty() }
            now.putAll(learned)
            val lasting = learned.filterKeys { k -> unseen.any { Meanings.key(pkg, it) == k && kept(it) } }
            lasting.forEach { (k, v) -> known.put(k, v) }
            if (lasting.isNotEmpty()) learn(svc, stamp, lasting)
        }
        return cands.map { e ->
            if (!isIcon(e, side)) return@map e
            val k = Meanings.key(pkg, e)
            (now[k] ?: known.optString(k)).ifEmpty { null }?.let { e.copy(label = it) } ?: e
        }
    }

    /** An unnamed element shaped like an icon: both sides at most [side] (a quarter of the screen's width), roughly square. */
    fun isIcon(e: ScreenCandidate, side: Int): Boolean {
        val w = e.bounds[2] - e.bounds[0]
        val h = e.bounds[3] - e.bounds[1]
        return e.label.isEmpty() && w <= side && h <= side && w <= 2 * h && h <= 2 * w
    }

    /** One screenshot, [boxes] cropped from it, one batch: each box's word or null. Null if it couldn't run. */
    @Synchronized private fun classify(svc: AgentService, stamp: String, boxes: List<IntArray>, beforeScreenshot: () -> Unit,
                                       note: (String) -> Unit): List<String?>? {
        val t0 = SystemClock.elapsedRealtime()
        var load = ""
        if (stamp != loaded) {
            model?.close()
            model = null
            loaded = stamp
            model = Model(svc)
            load = ", load ${SystemClock.elapsedRealtime() - t0} ms"
        }
        val m = model ?: return null
        // a failed shot names nothing and caches nothing: the next screen tries again
        beforeScreenshot()
        val shot = svc.screenshot().getOrElse { Log.w(TAG, "no screenshot", it); return null }
        val crops = boxes.map { crop(shot, it) }
        val size = "${shot.width}x${shot.height}"
        shot.recycle()
        val t1 = SystemClock.elapsedRealtime()
        val words = m.names(crops)
        val named = words.filterNotNull()
        note("icons: named ${named.size} of ${boxes.size} in ${SystemClock.elapsedRealtime() - t0} ms (model " +
            "${SystemClock.elapsedRealtime() - t1} ms$load; shot $size)" + (if (named.isEmpty()) "" else ": " + named.joinToString(", ")))
        return words
    }

    /**
     * [box] of [shot] as the model takes it (training/icons): the element's bounds, padding included, clamped to the shot;
     * resized bilinearly to 64x64; grey as PIL's convert('L') (ITU-R 601 luma), /255. Null when there's nothing to see:
     * the box is off the shot, or the crop is flat (a secure window shoots black).
     */
    fun crop(shot: Bitmap, box: IntArray): FloatArray? {
        val l = box[0].coerceIn(0, shot.width)
        val t = box[1].coerceIn(0, shot.height)
        val r = box[2].coerceIn(l, shot.width)
        val b = box[3].coerceIn(t, shot.height)
        if (r == l || b == t) return null
        val px = IntArray(SIZE * SIZE)
        Bitmap.createScaledBitmap(Bitmap.createBitmap(shot, l, t, r - l, b - t), SIZE, SIZE, true).getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val grey = FloatArray(px.size) { (0.299f * Color.red(px[it]) + 0.587f * Color.green(px[it]) + 0.114f * Color.blue(px[it])) / 255f }
        return grey.takeIf { it.max() - it.min() > 0.02f }
    }

    /** icons_labels.json: each class's word (null for "other": not a known icon) and the confidence a name needs. */
    class Labels(j: JSONObject) {
        val words: List<String?>
        private val threshold = j.getDouble("threshold").toFloat()

        init {
            val classes = j.getJSONArray("classes")
            val w = j.getJSONArray("words")
            val input = j.getJSONObject("input")
            require(classes.length() == w.length() && input.getInt("size") == SIZE && input.getBoolean("gray") &&
                input.getString("scale") == "x/255") { "$LABELS doesn't match the crops (64x64 grey, x/255, a word per class)" }
            words = List(classes.length()) { i -> w.getString(i).takeIf { classes.getString(i) != "other" } }
        }

        /** The top class's word, if it clears the threshold and isn't "other". */
        fun pick(probs: FloatArray): String? {
            val top = probs.indices.maxBy { probs[it] }
            return words[top]?.takeIf { probs[top] >= threshold }
        }
    }

    /** The pushed model: one ORT session on the CPU, 2 threads, arena off (as Laya's), with labels for its classes. */
    private class Model(c: Context) : AutoCloseable {
        private val labels = Labels(JSONObject(File(c.filesDir, LABELS).readText()))
        private val session = OrtEnvironment.getEnvironment().createSession(File(c.filesDir, MODEL).absolutePath,
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            })

        init {
            val k = runCatching { (session.outputInfo.getValue("probs").info as TensorInfo).shape[1] }.getOrNull()
            if (k != labels.words.size.toLong()) { session.close(); error("$MODEL's probs have $k classes, $LABELS ${labels.words.size}") }
        }

        /** Each crop's word, in one batch: null where there's no crop, no confident name, or "other". */
        @Suppress("UNCHECKED_CAST")
        fun names(crops: List<FloatArray?>): List<String?> {
            val batch = crops.filterNotNull()
            if (batch.isEmpty()) return crops.map { null }
            val input = FloatArray(batch.size * SIZE * SIZE).also { a -> batch.forEachIndexed { i, x -> x.copyInto(a, i * SIZE * SIZE) } }
            val probs = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input),
                longArrayOf(batch.size.toLong(), 1, SIZE.toLong(), SIZE.toLong())).use { t ->
                session.run(mapOf("image" to t)).use { it.get(0).value as Array<FloatArray> }
            }
            var i = 0
            return crops.map { x -> x?.let { labels.pick(probs[i++]) } }
        }

        override fun close() = session.close()
    }

    private fun file(c: Context) = File(c.filesDir, "icon_names.json")

    /** Which pushed files these are, by their times (a re-push changes it); null while either is missing: off. */
    private fun stamp(c: Context): String? {
        val m = File(c.filesDir, MODEL).lastModified()
        val l = File(c.filesDir, LABELS).lastModified()
        return if (m > 0 && l > 0) "$m-$l" else null
    }

    /** Names cached under the pushed files [stamp] ([Meanings.key] -> word, "" for none); another push's don't count. */
    @Synchronized private fun cached(c: Context, stamp: String): JSONObject =
        runCatching { JSONObject(file(c).readText()) }.getOrNull()?.takeIf { it.optString("model") == stamp }?.optJSONObject("names") ?: JSONObject()

    /** Adds [names] to the cache, read again under the lock, as [Meanings.learn] does: a wipe meanwhile stays wiped. */
    @Synchronized private fun learn(c: Context, stamp: String, names: Map<String, String>) = file(c).writeText(JSONObject()
        .put("model", stamp).put("names", cached(c, stamp).apply { names.forEach { (k, v) -> put(k, v) } }).toString())

    /** "Delete all learned data": every cached icon name. Returns how many were removed. */
    @Synchronized fun wipe(c: Context): Int =
        runCatching { JSONObject(file(c).readText()).getJSONObject("names").length() }.getOrDefault(0).also { file(c).delete() }
}

/** The parts that run without the model: which elements are icons, the crop, and how probabilities become a name. */
fun iconNamerSelfCheck(): String {
    fun c(label: String, w: Int, h: Int) = ScreenCandidate(label, "ImageView", true, false, false, false, false, false, false,
        true, intArrayOf(0, 0, w, h))
    check(IconNamer.isIcon(c("", 126, 126), 270) && !IconNamer.isIcon(c("Search", 126, 126), 270) &&
        !IconNamer.isIcon(c("", 1080, 126), 270) && !IconNamer.isIcon(c("", 100, 250), 270)) { "isIcon" }
    // a 4x2 screenshot: white white black black / red green blue black
    val shot = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
    shot.setPixels(intArrayOf(Color.WHITE, Color.WHITE, Color.BLACK, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE, Color.BLACK),
        0, 4, 0, 0, 4, 2)
    fun near(a: Float, b: Float) = abs(a - b) < 0.01f
    val row = IconNamer.crop(shot, intArrayOf(0, 0, 4, 1))!!
    check(row.size == 64 * 64 && (0 until 64).all { y -> near(row[y * 64], 1f) && near(row[y * 64 + 63], 0f) }) { "row edges" }
    check(row[31] in 0.3f..0.7f) { "not bilinear: ${row[31]}" }  // nearest would still be white here
    // grey is ITU-R 601 luma, as PIL's convert('L'): the bottom of a white/red, white/green and black/blue column
    for ((x, luma) in listOf(0 to 0.299f, 1 to 0.587f, 2 to 0.114f)) {
        val col = IconNamer.crop(shot, intArrayOf(x, 0, x + 1, 2))!!
        check(near(col.first(), if (x < 2) 1f else 0f) && near(col.last(), luma)) { "grey of column $x: ${col.first()}, ${col.last()}" }
    }
    // boxes are clamped to the shot; nothing is seen off it, or in a flat crop (a secure window shoots black)
    check(IconNamer.crop(shot, intArrayOf(-5, -5, 2, 2))!!.let { near(it.first(), 1f) && near(it.last(), 0.587f) }) { "clamp" }
    check(IconNamer.crop(shot, intArrayOf(4, 0, 9, 2)) == null && IconNamer.crop(shot, intArrayOf(3, 0, 4, 2)) == null) { "nothing" }
    shot.recycle()
    // a name needs the threshold, and "other" never gives one
    val labels = IconNamer.Labels(JSONObject("""{"classes": ["search", "other", "arrow_backward"], "words": ["search", "other",
        "back"], "threshold": 0.7, "input": {"size": 64, "gray": true, "scale": "x/255"}}"""))
    check(labels.pick(floatArrayOf(0.1f, 0.05f, 0.85f)) == "back" && labels.pick(floatArrayOf(0.6f, 0.1f, 0.3f)) == null &&
        labels.pick(floatArrayOf(0.01f, 0.98f, 0.01f)) == null) { "pick" }
    return "ok"
}
