package dev.layatest.agent

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase-2 gate: run the exported Laya ONNX on the phone and measure load time, per-step latency and
 * peak PSS, checking the logits against the Mac (fixture.json's expected_logits). Driven by
 * `{"cmd":"bench"}` over the bridge; both files must already be in the app's filesDir
 * (scripts/bench_device.py copies them there). This measures only the ORT forward pass — tokenizing
 * is cheap and ported separately — so the number is the thing that decides if on-device Laya is viable.
 */
object Bench {
    fun run(ctx: Context, c: JSONObject): JSONObject {
        val model = File(ctx.filesDir, c.optString("model", LayaEngine.MODEL))
        val fixtureFile = File(ctx.filesDir, c.optString("fixture", "fixture.json"))
        require(model.exists()) { "model not in filesDir: ${model.absolutePath} (run scripts/bench_device.py)" }
        require(fixtureFile.exists()) { "fixture not in filesDir: ${fixtureFile.absolutePath}" }
        val fx = JSONObject(fixtureFile.readText())
        val iters = c.optInt("iters", 10)
        val threads = c.optInt("threads", 4)

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
            if (c.optBoolean("xnnpack", false)) addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            if (c.optBoolean("lowmem", false)) {
                // The CPU arena + memory-pattern prealloc inflate PSS far past the weights; drop them.
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            }
        }

        val t0 = SystemClock.elapsedRealtime()
        val session = env.createSession(model.absolutePath, opts)
        val loadMs = SystemClock.elapsedRealtime() - t0

        val tensors = linkedMapOf(
            "input_ids" to OnnxTensor.createTensor(env, long2d(fx.getJSONArray("input_ids"))),
            "attention_mask" to OnnxTensor.createTensor(env, long2d(fx.getJSONArray("attention_mask"))),
            "marker_pos" to OnnxTensor.createTensor(env, long2d(fx.getJSONArray("marker_pos"))),
            "marker_mask" to OnnxTensor.createTensor(env, bool2d(fx.getJSONArray("marker_mask"))),
            "qtype" to OnnxTensor.createTensor(env, long1d(fx.getJSONArray("qtype"))),
        )

        var logits: Array<FloatArray>? = null
        // "pin": ORT's threads on the fast cores (research/19 lever 3), re-applied each run as LayaEngine does
        val pin = c.optBoolean("pin", false)
        val heatBefore = heat(ctx)
        if (pin) Native.pinAllThreads(Native.fastCores)
        session.run(tensors).use { } // warm-up (not timed)
        val ns = LongArray(iters)
        for (i in 0 until iters) {
            if (pin) Native.pinAllThreads(Native.fastCores)
            val s = SystemClock.elapsedRealtimeNanos()
            session.run(tensors).use { res ->
                @Suppress("UNCHECKED_CAST")
                if (i == 0) logits = res.get(0).value as Array<FloatArray>
            }
            ns[i] = SystemClock.elapsedRealtimeNanos() - s
        }

        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val expected = float2d(fx.getJSONArray("expected_logits"))
        var maxDiff = 0.0
        for (r in expected.indices) for (j in expected[r].indices)
            maxDiff = maxOf(maxDiff, kotlin.math.abs(expected[r][j] - logits!![r][j]).toDouble())

        tensors.values.forEach { it.close() }
        session.close()
        ns.sort()
        return JSONObject()
            .put("load_ms", loadMs)
            .put("median_ms", ns[iters / 2] / 1e6)
            .put("p90_ms", ns[minOf(iters - 1, (iters * 9) / 10)] / 1e6)
            .put("min_ms", ns[0] / 1e6)
            .put("iters", iters)
            .put("threads", threads)
            .put("pin", pin)
            .put("heat_before", heatBefore)
            .put("heat_after", heat(ctx))
            .put("xnnpack", c.optBoolean("xnnpack", false))
            .put("lowmem", c.optBoolean("lowmem", false))
            .put("peak_pss_mb", mi.totalPss / 1024.0)
            .put("native_heap_mb", Debug.getNativeHeapAllocatedSize() / 1048576.0)
            .put("op_argmax", argmax(logits!![0]))
            .put("target_argmax", argmax(logits!![1]))
            .put("max_logit_diff_vs_mac", maxDiff)
    }

    private fun long2d(a: JSONArray) = Array(a.length()) { i -> a.getJSONArray(i).let { r -> LongArray(r.length()) { r.getLong(it) } } }
    private fun bool2d(a: JSONArray) = Array(a.length()) { i -> a.getJSONArray(i).let { r -> BooleanArray(r.length()) { r.getInt(it) != 0 } } }
    private fun long1d(a: JSONArray) = LongArray(a.length()) { a.getLong(it) }
    private fun float2d(a: JSONArray) = Array(a.length()) { i -> a.getJSONArray(i).let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } } }
    private fun argmax(a: FloatArray): Int { var m = 0; for (i in a.indices) if (a[i] > a[m]) m = i; return m }
}

/**
 * The phone's own heat report: thermal status (0 none, 1 light, 2 moderate, 3 severe, ...) and the headroom
 * forecast for the next 10 s (1.0 = severe throttling), so a slow Laya step can be told from a hot phone.
 */
fun heat(ctx: Context): String {
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    return "status ${pm.currentThermalStatus}, headroom ${"%.2f".format(pm.getThermalHeadroom(10))}"
}
