package dev.layatest.agent

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase-2c end-to-end proof on-device: rebuild the Mac fixture's state (goal + 14 "Item i" buttons),
 * tokenize with the real DJL tokenizer, build the ORT inputs with [buildInputs], run the fp16 model,
 * and [decode] it — then check the generated tokens against models/fixture.json. If `input_ids_match`
 * is true the whole Kotlin pipeline (tokenizer + serialization + markers) equals the Python; the
 * decoded answer must then be operation=BACK, target=0. Driven by `{"cmd":"decide_test"}`.
 */
object Decide {
    fun test(ctx: Context, c: JSONObject): JSONObject {
        val tokFile = File(ctx.filesDir, "tokenizer.json")
        val modelFile = File(ctx.filesDir, c.optString("model", "laya-android.fp16.onnx"))
        require(tokFile.exists()) { "tokenizer.json not in filesDir (run scripts/bench_device.py --decide)" }
        require(modelFile.exists()) { "model not in filesDir: ${modelFile.name}" }

        Tokenizer(tokFile).use { tok ->
            val candidates = (0 until 14).map { Candidate("Item $it", "Button") }
            val built = buildInputs(tok::encode, "Open settings and turn on Wi-Fi", emptyList(), candidates)

            // Tokenizer + buildInputs parity vs the Mac fixture: a byte-exact match proves the port.
            val parity = JSONObject()
            File(ctx.filesDir, "fixture.json").takeIf { it.exists() }?.let {
                val fx = JSONObject(it.readText())
                parity.put("input_ids_match", eq2d(built.ort.inputIds, fx.getJSONArray("input_ids")))
                parity.put("marker_pos_match", eq2d(built.ort.markerPos, fx.getJSONArray("marker_pos")))
                parity.put("built_seq", built.ort.inputIds[0].size).put("fixture_seq", fx.getJSONArray("input_ids").getJSONArray(0).length())
            }

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            }
            val session = env.createSession(modelFile.absolutePath, opts)
            val tensors = linkedMapOf(
                "input_ids" to OnnxTensor.createTensor(env, built.ort.inputIds),
                "attention_mask" to OnnxTensor.createTensor(env, built.ort.attentionMask),
                "marker_pos" to OnnxTensor.createTensor(env, built.ort.markerPos),
                "marker_mask" to OnnxTensor.createTensor(env, built.ort.markerMask),
                "qtype" to OnnxTensor.createTensor(env, built.ort.qtype),
            )
            val result = session.run(tensors)
            @Suppress("UNCHECKED_CAST")
            val logits = result.get(0).value as Array<FloatArray>
            @Suppress("UNCHECKED_CAST")
            val act = result.get(1).value as Array<FloatArray>
            result.close()
            tensors.values.forEach { it.close() }
            session.close()

            val ans = decode(logits, act, built)
            val op = ans.getValue("operation")
            val tg = ans.getValue("target")
            return JSONObject()
                .put("operation", op.choiceKey)
                .put("operation_conf", op.answerConfidence)
                .put("target", tg.choiceKey)
                .put("target_conf", tg.answerConfidence)
                .put("marker_counts", JSONArray(built.markerCounts.toList()))
                .put("parity", parity)
        }
    }

    private fun eq2d(a: Array<LongArray>, b: JSONArray): Boolean {
        if (a.size != b.length()) return false
        for (r in a.indices) {
            val row = b.getJSONArray(r)
            if (a[r].size != row.length()) return false
            for (j in a[r].indices) if (a[r][j] != row.getLong(j)) return false
        }
        return true
    }
}
