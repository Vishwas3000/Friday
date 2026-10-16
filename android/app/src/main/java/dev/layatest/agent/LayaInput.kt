package dev.layatest.agent

import kotlin.math.exp
import kotlin.math.ln

/**
 * Deterministic port of Laya-Android's input build + decode for on-device ONNX (Phase 2).
 *
 * Mirrors, byte-for-byte, so the exported graph gets exactly the input it was trained on:
 *   - scripts/laya_android.py : OP_DESC, option_label, history_entry, decide() state/questions
 *   - laya/common.py          : serialize_state, build_head/build_sequence, collate_items
 *   - laya/onnx_agent.py       : _encode_state (shared state_ids), _infer_batch casts, _decode_answers
 *
 * Tokenizer is owned by another file; this code only needs its encoder, passed as
 * `encode = tokenizer::encode` where `fun encode(text: String, addSpecialTokens: Boolean): LongArray`
 * returns the same ids as HF `tok(text, add_special_tokens=...)["input_ids"]`. We always call it
 * with addSpecialTokens=false, exactly as the Python does.
 *
 * Run `main()` for the assert-based self-check (values derived from the Python; see comments).
 */

// Special-token ids and config from the pinned checkpoint ByunByun/laya-android @5a03b2b
// (tokenizer/special_tokens_map.json + rl_agent_config.json). temperature_by_options is {} and
// decide() emits only `choice` questions, so t_scale is always temperature[0] for us.
// shortcut: these are hardcoded to the pin; re-read those two files if PIN["revision"] changes.
private const val CLS_ID = 2L          // <bos>
private const val SEP_ID = 1L          // <eos>
private const val MASK_ID = 4L         // <mask>
private const val PAD_ID = 0L          // <pad>  (collate pads ids with this)
private const val MASK_TOKEN = "<mask>"
private const val QTYPE_CHOICE = 0
private val TEMPERATURE = doubleArrayOf(1.1609286069869995, 1.0, 1.0)  // index by qtype

/** OP_DESC from laya_android.py; insertion order is the operation option order and the choice keys. */
val OP_DESC: Map<String, String> = linkedMapOf(
    "CLICK" to "tap a UI element",
    "LONG_PRESS" to "long-press a UI element",
    "SCROLL_UP" to "scroll up",
    "SCROLL_DOWN" to "scroll down",
    "SCROLL_LEFT" to "scroll left",
    "SCROLL_RIGHT" to "scroll right",
    "OPEN_APP" to "launch an app by name",
    "INPUT_TEXT" to "type text into the focused field",
    "BACK" to "press the system back button",
    "HOME" to "go to the home screen",
    "WAIT" to "wait for the screen to update",
    "DONE" to "the goal is complete; stop",
)
const val Q_OP = "Which operation should be performed next?"
const val Q_TARGET = "Which UI element should be targeted?"

/**
 * One actionable candidate, the subset of the app forest / build_candidates output that input
 * building reads (label, class, the flags option_label consults). The caller already holds bounds
 * etc. for acting; they are not needed here.
 */
data class Candidate(
    val label: String,
    val className: String,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

/** Port of option_label: `"<label|-> (<Class>) <flags...>"`, flags in a fixed order. */
fun optionLabel(c: Candidate): String {
    val flags = buildList {
        if (c.editable) add("edit")
        if (c.longClickable && !c.clickable) add("long")
        if (c.scrollable && !c.clickable) add("scroll")
        if (c.checked) add("on")
        if (c.selected) add("selected")
        if (!c.enabled) add("disabled")
    }
    val head = c.label.take(40).ifEmpty { "-" }
    return (listOf(head, "(${c.className})") + flags).joinToString(" ")
}

/** Port of history_entry: the string appended to `history` after an action runs. */
fun historyEntry(op: String, cand: Candidate? = null, payload: String? = null): String = when (op) {
    "CLICK", "LONG_PRESS" -> "$op " + (cand?.let { it.label.ifEmpty { it.className } } ?: "(unlabeled)")
    "INPUT_TEXT" -> "INPUT_TEXT \"${payload ?: ""}\""
    "OPEN_APP" -> "OPEN_APP ${payload ?: ""}"
    else -> op
}

/** A `choice` question: its id, rendered option strings, the choice keys those options map to, and its instructions. */
class Question(
    val id: String, val qtype: Int, val keys: List<String>, val options: List<String>,
    val instructions: String = Q_INSTRUCTIONS.getValue(id),
)

private class Item(val ids: LongArray, val markers: IntArray, val qtype: Int)

/** The five ORT inputs, cast as _infer_batch casts them: int64 ids/mask/markerPos/qtype, bool markerMask. */
class OrtInputs(
    val inputIds: Array<LongArray>,      // [rows][L] int64, padded with PAD_ID
    val attentionMask: Array<LongArray>, // [rows][L] int64, 1 real / 0 pad
    val markerPos: Array<LongArray>,     // [rows][kmax] int64, padded 0
    val markerMask: Array<BooleanArray>, // [rows][kmax] bool, true where a real option marker sits
    val qtype: LongArray,                // [rows] int64
)

/** ORT inputs plus the per-row question metadata `decode` needs. */
class BuiltInputs(val ort: OrtInputs, val questions: List<Question>, val markerCounts: IntArray)

/** One decoded answer. choice* index into this question's options/keys; probabilities rounded to 4dp as Python. */
data class Answer(
    val choiceIndex: Int,
    val choiceKey: String,
    val probabilities: LinkedHashMap<String, Double>,
    val confidence: Double,          // confidence_from_probs (normalized entropy)
    val answerConfidence: Double,    // answer_confidence (max p)
    val actProbability: Double,      // softmax(act_logits)[0]
)

/** json.dumps(str, ensure_ascii=False) escaping: only ", \\, the C0 controls; everything >=0x20 (incl. non-ASCII) verbatim. */
private fun jsonStr(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        '\b' -> sb.append("\\b")
        '\u000C' -> sb.append("\\f")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    return sb.append('"').toString()
}

/** Port of serialize_state for decide()'s fixed `{goal, history, ui}` dict == json.dumps(ensure_ascii=False). */
fun serializeState(goal: String, history: List<String>, ui: List<String>): String {
    val h = history.joinToString(", ") { jsonStr(it) }
    val u = ui.joinToString(", ") { jsonStr(it) }
    return "{\"goal\": ${jsonStr(goal)}, \"history\": [$h], \"ui\": [$u]}"
}

/**
 * Port of decide()'s deterministic half + _encode_state + collate_items: assemble the state and the
 * operation(+target) questions, tokenize the state once, build each question's sequence, collate.
 */
fun buildInputs(
    encode: (String, Boolean) -> LongArray,
    goal: String,
    history: List<String>,
    candidates: List<Candidate>,
    maxLen: Int = 3072,
    headMaxLen: Int = 1536,
): BuiltInputs {
    val labels = candidates.map { optionLabel(it) }
    val questions = ArrayList<Question>(2)
    // criteria render as "<key>: <value>"; every value here is non-empty so render_options' str(k) branch never fires.
    questions.add(Question("operation", QTYPE_CHOICE, OP_DESC.keys.toList(),
        OP_DESC.map { (k, v) -> "$k: $v" }))
    if (candidates.isNotEmpty()) {
        questions.add(Question("target", QTYPE_CHOICE, labels.indices.map { it.toString() },
            labels.mapIndexed { i, l -> "$i: $l" }))
    }
    val ui = labels.mapIndexed { i, l -> "[$i] $l" }
    // Serialize once, blank any literal mask token, tokenize once; reused across questions (state_ids).
    val stateText = serializeState(goal, history.takeLast(3), ui).replace(MASK_TOKEN, " ")
    val stateIds = encode(stateText, false)

    val items = questions.map { q ->
        val item = buildSequence(encode, stateIds, q, maxLen, headMaxLen)
        // _encode_state's guard: a question whose markers didn't all fit would decode onto wrong options.
        check(item.markers.size == q.options.size) {
            "question '${q.id}': only ${item.markers.size} of ${q.options.size} option markers fit in " +
                "max_len=$maxLen with head_max_len=$headMaxLen; raise max_len or use fewer options"
        }
        item
    }
    return BuiltInputs(collate(items), questions, IntArray(items.size) { items[it].markers.size })
}

/** json.dumps(dict, ensure_ascii=False) for a flat string->string dict, e.g. {"goal": "play jazz music"}. */
fun serializeFlat(pairs: List<Pair<String, String>>): String =
    pairs.joinToString(", ", "{", "}") { (k, v) -> "${jsonStr(k)}: ${jsonStr(v)}" }

/** One generic choice question about [stateText] (routing, slot spans, "did you mean") -- same build as decide(). */
fun buildChoice(
    encode: (String, Boolean) -> LongArray,
    stateText: String,
    q: Question,
    maxLen: Int = 3072,
    headMaxLen: Int = 1536,
): BuiltInputs {
    val item = buildSequence(encode, encode(stateText.replace(MASK_TOKEN, " "), false), q, maxLen, headMaxLen)
    check(item.markers.size == q.options.size) { "question '${q.id}': only ${item.markers.size} of ${q.options.size} options fit" }
    return BuiltInputs(collate(listOf(item)), listOf(q), intArrayOf(item.markers.size))
}

/** Port of build_head: [CLS] <type> ins [SEP] ([MASK] opt)* [SEP]; returns ids and each [MASK]'s absolute index. */
private fun buildHead(encode: (String, Boolean) -> LongArray, q: Question, headMaxLen: Int): Pair<LongArray, IntArray> {
    val ins = q.instructions.replace(MASK_TOKEN, " ")
    val headTokens = encode("choice question: $ins", false)  // decide() only emits choice questions
    val optIds = q.options.map { opt ->
        // Cap each option at 48 tokens (truncation=True, max_length=48): first-48 of the encoding.
        val t = encode(" " + opt.replace(MASK_TOKEN, " "), false)
        val capped = if (t.size > 48) t.copyOf(48) else t
        longArrayOf(MASK_ID) + capped
    }.toMutableList()

    var optBudget = headMaxLen - optIds.sumOf { it.size }
    if (optBudget < 16) {
        val per = maxOf(4, (headMaxLen - 16) / maxOf(1, optIds.size))
        for (j in optIds.indices) if (optIds[j].size > per) optIds[j] = optIds[j].copyOf(per)
        optBudget = headMaxLen - optIds.sumOf { it.size }
    }
    val headTrunc = headTokens.copyOf(minOf(headTokens.size, maxOf(8, optBudget)))

    val ids = ArrayList<Long>()
    ids.add(CLS_ID); headTrunc.forEach { ids.add(it) }; ids.add(SEP_ID)
    val markers = ArrayList<Int>(optIds.size)
    for (o in optIds) { markers.add(ids.size); o.forEach { ids.add(it) } }
    ids.add(SEP_ID)
    return ids.toLongArray() to markers.toIntArray()
}

/** Port of build_sequence (dict state -> truncate_left=false): head + state[:room] + [SEP], clamped to maxLen. */
private fun buildSequence(
    encode: (String, Boolean) -> LongArray,
    stateIds: LongArray,
    q: Question,
    maxLen: Int,
    headMaxLen: Int,
): Item {
    val (headIds, markers) = buildHead(encode, q, headMaxLen)
    val room = maxOf(0, maxLen - headIds.size - 1)   // -1 reserves the closing [SEP]
    val take = minOf(room, stateIds.size)            // state_ids[:room]
    val ids = ArrayList<Long>(headIds.size + take + 1)
    headIds.forEach { ids.add(it) }
    for (i in 0 until take) ids.add(stateIds[i])
    ids.add(SEP_ID)
    // Clamp to maxLen and drop any marker pushed past it (only possible when the head alone overflows).
    val finalIds = if (ids.size > maxLen) LongArray(maxLen) { ids[it] } else ids.toLongArray()
    val finalMarkers = markers.filter { it < maxLen }.toIntArray()
    return Item(finalIds, finalMarkers, q.qtype)
}

/** Port of collate_items: pad rows to a common seq length and marker count. */
private fun collate(items: List<Item>): OrtInputs {
    val rows = items.size
    val l = items.maxOf { it.ids.size }
    val kmax = items.maxOf { it.markers.size }
    val inputIds = Array(rows) { LongArray(l) { PAD_ID } }   // torch.full(..., pad_id)
    val attentionMask = Array(rows) { LongArray(l) }
    val markerPos = Array(rows) { LongArray(kmax) }
    val markerMask = Array(rows) { BooleanArray(kmax) }
    val qtype = LongArray(rows)
    for (r in items.indices) {
        val it = items[r]
        for (j in it.ids.indices) { inputIds[r][j] = it.ids[j]; attentionMask[r][j] = 1L }
        for (j in it.markers.indices) { markerPos[r][j] = it.markers[j].toLong(); markerMask[r][j] = true }
        qtype[r] = it.qtype.toLong()
    }
    return OrtInputs(inputIds, attentionMask, markerPos, markerMask, qtype)
}

/**
 * Port of _decode_answers for choice questions: softmax(logits[r][:k] / temperature) over the k real
 * markers, then argmax -> choiceKey and the per-key probabilities. option_order is never set by
 * decide(), so the model row is already in option order (unpermute_probs is the identity here).
 *
 * logits: [rows][>=kmax] float (padded slots are the model's -1e4 and ignored by the [:k] slice).
 * actLogits: [rows][2] float.
 */
fun decode(logits: Array<FloatArray>, actLogits: Array<FloatArray>, built: BuiltInputs): LinkedHashMap<String, Answer> {
    val out = LinkedHashMap<String, Answer>()
    for (r in built.questions.indices) {
        val q = built.questions[r]
        val k = built.markerCounts[r]
        val temp = TEMPERATURE[q.qtype]
        val z = DoubleArray(k) { logits[r][it] / temp }
        var zMax = z[0]
        for (i in 1 until k) if (z[i] > zMax) zMax = z[i]
        var sum = 0.0
        val p = DoubleArray(k) { val e = exp(z[it] - zMax); sum += e; e }
        for (i in 0 until k) p[i] /= sum

        var argmax = 0
        for (i in 1 until k) if (p[i] > p[argmax]) argmax = i   // numpy argmax: first on ties
        val probs = LinkedHashMap<String, Double>(k)
        for (i in 0 until k) probs[q.keys[i]] = round4(p[i])

        out[q.id] = Answer(
            choiceIndex = argmax,
            choiceKey = q.keys[argmax],
            probabilities = probs,
            confidence = round4(confidenceFromProbs(p)),
            answerConfidence = round4(p[argmax].coerceIn(0.0, 1.0)),  // p[argmax] == max(p)
            actProbability = round4(softmax2(actLogits[r])),
        )
    }
    return out
}

/** confidence_from_probs: 1 - H(p)/ln(k), clipped to [0,1]; 1.0 for k<2. */
private fun confidenceFromProbs(p: DoubleArray): Double {
    val k = p.size
    if (k < 2) return 1.0
    var ent = 0.0
    for (pi in p) ent -= pi * ln(pi.coerceIn(1e-12, 1.0))
    return (1.0 - ent / ln(k.toDouble())).coerceIn(0.0, 1.0)
}

/** Softmax over the 2 act logits, returning P(index 0) == act_probability. */
private fun softmax2(a: FloatArray): Double {
    val m = maxOf(a[0], a[1]).toDouble()
    val e0 = exp(a[0].toDouble() - m)
    val e1 = exp(a[1].toDouble() - m)
    return e0 / (e0 + e1)
}

/** Python round(x, 4): round half to even. */
private fun round4(x: Double): Double = Math.rint(x * 1e4) / 1e4

internal val Q_INSTRUCTIONS = mapOf("operation" to Q_OP, "target" to Q_TARGET)

// --------------------------------------------------------------------------------------------
// Assert-based self-check (mirrors the one at the bottom of scripts/laya_android.py). Expected
// values were derived by running the Python (laya/common.py, laya_android.py) on these inputs.
// --------------------------------------------------------------------------------------------
fun main() {
    // (1) option_label + history_entry, the exact cases scripts/laya_android.py __main__ asserts.
    val battery = Candidate("Battery 80%", "LinearLayout", clickable = true)
    val search = Candidate("Search", "EditText", clickable = true, editable = true)
    val wifi = Candidate("Wi-Fi", "Switch", checked = true, enabled = false)
    check(optionLabel(battery) == "Battery 80% (LinearLayout)") { optionLabel(battery) }
    check(optionLabel(search) == "Search (EditText) edit") { optionLabel(search) }
    check(optionLabel(wifi) == "Wi-Fi (Switch) on disabled") { optionLabel(wifi) }
    check(historyEntry("CLICK", battery) == "CLICK Battery 80%")
    check(historyEntry("BACK") == "BACK")
    check(historyEntry("INPUT_TEXT", payload = "hi") == "INPUT_TEXT \"hi\"")

    // (2) serialize_state byte-for-byte vs Python json.dumps(ensure_ascii=False) (quotes + backslash/newline/unicode).
    val s1 = serializeState("Open settings", listOf("CLICK Home", "INPUT_TEXT \"hi\""),
        listOf("[0] Settings (Button)", "[1] Wi-Fi (Switch) on"))
    check(s1 == "{\"goal\": \"Open settings\", \"history\": [\"CLICK Home\", \"INPUT_TEXT \\\"hi\\\"\"], " +
        "\"ui\": [\"[0] Settings (Button)\", \"[1] Wi-Fi (Switch) on\"]}") { s1 }
    val s2 = serializeState("a\\b\nxé", emptyList(), emptyList())
    check(s2 == "{\"goal\": \"a\\\\b\\nxé\", \"history\": [], \"ui\": []}") { s2 }

    // (3) build: marker placement + counts + collate. Fixed-3-token stub so positions are hand-checkable:
    //     [CLS]+3 ins+[SEP] -> first marker at 5; each option is [MASK]+3 -> stride 4.
    //     (With the REAL tokenizer the operation markers are [12,19,30,38,46,54,62,72,83,91,99,108],
    //      which matches models/fixture.json row 0 exactly — cross-checked via `uv run python`.)
    val stub: (String, Boolean) -> LongArray = { _, _ -> LongArray(3) { 9L } }
    val cands = (0 until 14).map { Candidate("Item $it", "Button") }
    val built = buildInputs(stub, "Open settings and turn on Wi-Fi", emptyList(), cands)
    check(built.markerCounts.toList() == listOf(12, 14)) { built.markerCounts.toList().toString() }
    check(built.ort.qtype.toList() == listOf(0L, 0L))
    val opMarkers = (0 until 12).map { (5 + 4 * it).toLong() }
    check(built.ort.markerPos[0].toList() == opMarkers + listOf(0L, 0L)) { built.ort.markerPos[0].toList().toString() }
    check(built.ort.markerPos[1].toList() == (0 until 14).map { (5 + 4 * it).toLong() })
    check(built.ort.markerMask[0].count { it } == 12 && built.ort.markerMask[1].count { it } == 14)
    check(built.ort.markerMask[0].toList().subList(12, 14) == listOf(false, false))

    // (4) decode from the real fp16 logits in models/fixture.json (rows=2, k=[12,14]).
    //     Python _decode_answers at temperature[0]=1.1609286… gives:
    //       operation argmax=8 -> "BACK", target argmax=0 -> "0".
    val neg = -1.0e4f
    val opLogits = floatArrayOf(4.90625f, -3.705078125f, -1.6650390625f, -2.16796875f, -2.7421875f,
        -3.28125f, 5.5859375f, -4.41015625f, 5.6875f, 2.4140625f, -0.050262451171875f, -4.68359375f, neg, neg)
    val tgLogits = floatArrayOf(3.84375f, 1.9248046875f, 0.375244140625f, -0.583984375f, -0.7861328125f,
        -2.298828125f, -2.361328125f, -3.41796875f, -2.64453125f, -3.4609375f, -3.44140625f, -2.83984375f,
        -2.490234375f, -2.607421875f)
    val zeros = floatArrayOf(0f, 0f)
    val ans = decode(arrayOf(opLogits, tgLogits), arrayOf(zeros, zeros), built)
    val op = ans.getValue("operation"); val tg = ans.getValue("target")
    check(op.choiceIndex == 8 && op.choiceKey == "BACK") { "${op.choiceIndex} ${op.choiceKey}" }
    check(tg.choiceIndex == 0 && tg.choiceKey == "0") { "${tg.choiceIndex} ${tg.choiceKey}" }
    check(op.actProbability == 0.5)   // softmax([0,0])[0]
    // Python probabilities (full precision), compared within 3e-4 to absorb float32-vs-double + 4dp rounding.
    val opExp = listOf(0.204254, 0.000123, 0.000711, 0.000461, 0.000281, 0.000177, 0.366806, 0.000067,
        0.400341, 0.023871, 0.002857, 0.000053)
    val tgExp = listOf(0.761627, 0.145841, 0.038388, 0.016802, 0.014117, 0.003836, 0.003635, 0.001463,
        0.002848, 0.00141, 0.001434, 0.002407, 0.003253, 0.00294)
    OP_DESC.keys.forEachIndexed { i, kk -> check(kotlin.math.abs(op.probabilities.getValue(kk) - opExp[i]) < 3e-4) { "op $kk" } }
    tgExp.forEachIndexed { i, e -> check(kotlin.math.abs(tg.probabilities.getValue("$i") - e) < 3e-4) { "tg $i" } }
    check(kotlin.math.abs(op.answerConfidence - 0.4003) < 3e-4)
    check(kotlin.math.abs(tg.answerConfidence - 0.7616) < 3e-4)

    println("ok")
}
