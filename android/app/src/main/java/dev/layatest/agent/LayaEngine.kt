package dev.layatest.agent

import android.content.Context
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/** One answer to a choice question: the option's index and key, and Laya's calibrated confidence. */
class Pick(val index: Int, val key: String, val confidence: Double, val probabilities: Map<String, Double>)

/** Laya's next UI action on a screen: an operation and, for CLICK/LONG_PRESS, a candidate index. */
class Step(val op: String, val opConfidence: Double, val target: Int?, val targetConfidence: Double?, val tokens: Int = 0)

/**
 * The on-device Laya model (D-023: fp16, CPU arena off, 4 threads). Created only when a request needs
 * it and closed when the request ends (D-016). Laya only ever chooses: [choose] answers any short
 * option list (routing, slot spans, "did you mean"), [step] picks the next UI action.
 */
class LayaEngine(ctx: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tok: Tokenizer
    /** Load cost, for the "route" bridge test: (model session ms, tokenizer ms). */
    val loadMs: Pair<Long, Long>

    init {
        val model = File(ctx.filesDir, MODEL)
        val tokFile = File(ctx.filesDir, "tokenizer.json")
        require(model.exists() && tokFile.exists()) { "Laya isn't on the phone yet (push $MODEL and tokenizer.json)" }
        // The tokenizer (~8 s) and the model session (~10 s) load in parallel (measured 2026-10-16).
        var tokLoaded: Tokenizer? = null
        var tokError: Throwable? = null
        var tokMs = 0L
        val tokThread = Thread {
            val t = SystemClock.elapsedRealtime()
            try { tokLoaded = Tokenizer(tokFile, Tokenizer.vocabMapFor(model)) } catch (e: Throwable) { tokError = e }
            tokMs = SystemClock.elapsedRealtime() - t
        }.apply { start() }
        val t0 = SystemClock.elapsedRealtime()
        session = env.createSession(model.absolutePath, OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        })
        val sessionMs = SystemClock.elapsedRealtime() - t0
        tokThread.join()
        tokError?.let { session.close(); throw it }
        tok = tokLoaded!!
        loadMs = sessionMs to tokMs
        // The first forward pass pays one-off kernel setup (~4.5 s); pay it here, not on the owner's first request.
        choose("warm up", "Pick one.", listOf("a" to "yes", "b" to "no"))
    }

    /** One choice question about [goal]. Options are (key, text), rendered "key: text"; keep them to 16 or fewer. */
    fun choose(goal: String, instructions: String, options: List<Pair<String, String>>): Pick {
        val q = Question("q", 0, options.map { it.first }, options.map { "${it.first}: ${it.second}" }, instructions)
        val built = buildChoice(tok::encode, serializeFlat(listOf("goal" to goal)), q)
        val (logits, act) = forward(built)
        val a = decode(logits, act, built).getValue("q")
        return Pick(a.choiceIndex, a.choiceKey, a.answerConfidence, a.probabilities)
    }

    /**
     * The operation first; the target row -- every element again as an option, most of the tokens -- only for
     * steps that use a target. The rows are independent passes, so the answers match asking both at once, but
     * a scroll / back / done step skips the long row and nothing is padded to it (research/19: ~1.5-4x).
     * The target row shows only [shown] (indices into [cands], see [shortlist]); the operation row sees [opShown],
     * every element unless a test says otherwise (research/18 §11).
     */
    fun step(goal: String, history: List<String>, cands: List<ScreenCandidate>, shown: List<Int> = cands.indices.toList(),
             opShown: List<Int> = cands.indices.toList()): Step {
        val c = cands.map { it.toCandidate() }
        val (op, opTokens) = operation(goal, history, opShown.map { c[it] })
        var tokens = opTokens
        if (op.choiceKey !in TARGETED || c.isEmpty()) return Step(op.choiceKey, op.answerConfidence, null, null, tokens)
        val tIn = buildInputs(tok::encode, goal, history, shown.map { c[it] }, ask = setOf("target"))
        val tg = answers(tIn).getValue("target")
        tokens += tIn.ort.inputIds.sumOf { it.size }
        return Step(op.choiceKey, op.answerConfidence, shown[tg.choiceIndex], tg.answerConfidence, tokens)
    }

    /** The operation question alone, on the elements [c]: its answer and the row's tokens. */
    internal fun operation(goal: String, history: List<String>, c: List<Candidate>): Pair<Answer, Int> {
        val opIn = buildInputs(tok::encode, goal, history, c, ask = setOf("operation"))
        return answers(opIn).getValue("operation") to opIn.ort.inputIds.sumOf { it.size }
    }

    private fun answers(built: BuiltInputs) = forward(built).let { (logits, act) -> decode(logits, act, built) }

    /** Both questions in one padded batch, as before [step] split them; kept so "step_parity" can check the split. */
    internal fun stepBatched(goal: String, history: List<String>, cands: List<ScreenCandidate>): Step {
        val built = buildInputs(tok::encode, goal, history, cands.map { it.toCandidate() })
        val ans = answers(built)
        val op = ans.getValue("operation")
        val tg = ans["target"]
        return Step(op.choiceKey, op.answerConfidence, tg?.choiceIndex, tg?.answerConfidence, built.ort.inputIds.sumOf { it.size })
    }

    // Experiment (research/19 lever 3), off until an A/B on a cool phone shows a gain: live, 3 of 4 pool threads
    // sat on the little cores, but the one pinned run was slower (43.8 s; two threads shared a mid core while the
    // app's own threads also want those cores). Flip with the "laya_pin" bridge command.
    @Volatile var pinFastCores = false

    private fun forward(built: BuiltInputs): Pair<Array<FloatArray>, Array<FloatArray>> {
        if (pinFastCores) Native.pinAllThreads(Native.fastCores)
        val t = linkedMapOf(
            "input_ids" to OnnxTensor.createTensor(env, built.ort.inputIds),
            "attention_mask" to OnnxTensor.createTensor(env, built.ort.attentionMask),
            "marker_pos" to OnnxTensor.createTensor(env, built.ort.markerPos),
            "marker_mask" to OnnxTensor.createTensor(env, built.ort.markerMask),
            "qtype" to OnnxTensor.createTensor(env, built.ort.qtype),
        )
        try {
            session.run(t).use { r ->
                @Suppress("UNCHECKED_CAST") val logits = r.get(0).value as Array<FloatArray>
                @Suppress("UNCHECKED_CAST") val act = r.get(1).value as Array<FloatArray>
                return logits to act
            }
        } finally {
            t.values.forEach { it.close() }
        }
    }

    override fun close() {
        tok.close()
        session.close()
    }

    companion object {
        // 8-bit MatMulNBits on ORT 1.31: 2.1× faster than fp16 on the Pixel 8, same decisions (D-047, research/18 §6)
        const val MODEL = "laya-android.nb8-h-e16.onnx"
        // INPUT_TEXT's target only outlines the field on the approval card; scrolls use the largest scrollable
        private val TARGETED = setOf("CLICK", "LONG_PRESS", "INPUT_TEXT")
    }
}
