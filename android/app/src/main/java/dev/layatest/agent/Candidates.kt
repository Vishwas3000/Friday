package dev.layatest.agent

import org.json.JSONObject

/**
 * On-device port of scripts/laya_android.py `parse_forest` + `build_candidates` + `_label` (D-013):
 * the AgentService `state()` forest -> the ordered, deduped actionable candidate list Laya scores.
 * Keeps bounds (for acting) and scrollable (to pick a scroll container); [toCandidate] drops them to
 * the exact fields [optionLabel] reads, so the on-device label == the Mac label.
 *
 * Must match the Python byte-for-byte or targets shift. `candidatesSelfCheck()` asserts the three
 * cases scripts/laya_android.py __main__ checks; run it via the "selfcheck" bridge command.
 */
data class ScreenCandidate(
    val label: String,
    val className: String,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val bounds: IntArray,   // [l, t, r, b], clipped to the screen
    val rid: String = "",   // resource id without its package: finds the element again later (Routes); not shown to Laya
)

fun ScreenCandidate.toCandidate() = Candidate(
    label, className, clickable, longClickable, editable, scrollable, checked, selected, enabled,
)

private val FLAGS = arrayOf("clickable", "long_clickable", "editable", "scrollable", "checkable")
private const val IME_WINDOW = 2  // AccessibilityWindowInfo.TYPE_INPUT_METHOD

private class Node(val j: JSONObject, val depth: Int, val ime: Boolean)

/** (text || content_desc || hint).strip(), with Python's "" == falsy semantics. */
private fun ownText(j: JSONObject): String {
    for (k in arrayOf("text", "content_desc", "hint")) {
        val v = j.optString(k, "").trim()
        if (v.isNotEmpty()) return v
    }
    return ""
}

/** Port of _label: own text, else descendants' texts in DFS order, whitespace-collapsed, capped at limit. */
private fun label(j: JSONObject, limit: Int = 60): String {
    val own = ownText(j)
    if (own.isNotEmpty()) return collapse(own).take(limit)
    val parts = ArrayList<String>()
    val stack = ArrayDeque<JSONObject>()
    val kids = j.optJSONArray("children")
    if (kids != null) for (i in kids.length() - 1 downTo 0) stack.addLast(kids.getJSONObject(i))  // reversed -> DFS
    while (stack.isNotEmpty() && parts.sumOf { it.length + 1 } < limit) {
        val c = stack.removeLast()
        val t = ownText(c)
        if (t.isNotEmpty()) parts.add(collapse(t))
        val ck = c.optJSONArray("children")
        if (ck != null) for (i in ck.length() - 1 downTo 0) stack.addLast(ck.getJSONObject(i))
    }
    return parts.joinToString(" ").take(limit)
}

private fun collapse(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
private fun shortClass(c: String) = c.substringAfterLast('.')

/** Flatten every window's tree to a node list, tagging depth and whether the node is in the IME window. */
private fun flatten(state: JSONObject): List<Node> {
    val out = ArrayList<Node>()
    val wins = state.getJSONArray("windows")
    for (wi in 0 until wins.length()) {
        val w = wins.getJSONObject(wi)
        val ime = w.getInt("type") == IME_WINDOW
        fun walk(j: JSONObject, depth: Int) {
            out.add(Node(j, depth, ime))
            val kids = j.optJSONArray("children") ?: return
            for (i in 0 until kids.length()) walk(kids.getJSONObject(i), depth + 1)
        }
        walk(w.getJSONObject("root"), 0)
    }
    return out
}

/** Port of build_candidates: visible, actionable, non-IME nodes, deduped on (box,label) keeping the deepest, in screen order. */
fun buildCandidates(state: JSONObject, screenW: Int, screenH: Int): List<ScreenCandidate> {
    // value holds the mutable flag merge; depth tracks which node won the (box,label) key.
    class G(var label: String, var className: String, var rid: String, var depth: Int,
            val box: IntArray, var enabled: Boolean, var checked: Boolean, var selected: Boolean,
            val flags: BooleanArray)
    val groups = LinkedHashMap<String, G>()
    for (n in flatten(state)) {
        val j = n.j
        if (n.ime || !j.getBoolean("visible") || FLAGS.none { j.getBoolean(it) }) continue
        val b = j.getJSONArray("bounds")
        val box = intArrayOf(maxOf(0, b.getInt(0)), maxOf(0, b.getInt(1)),
                             minOf(screenW, b.getInt(2)), minOf(screenH, b.getInt(3)))
        if (box[2] <= box[0] || box[3] <= box[1]) continue
        val lab = label(j)
        val key = "${box.joinToString(",")}|$lab"
        val f = BooleanArray(FLAGS.size) { j.getBoolean(FLAGS[it]) }
        val g = groups[key]
        if (g == null || n.depth > g.depth) {
            val merged = BooleanArray(FLAGS.size) { f[it] || (g?.flags?.get(it) ?: false) }
            groups[key] = G(lab, shortClass(j.getString("class")), j.getString("resource_id").substringAfterLast('/'),
                n.depth, box, j.getBoolean("enabled"), j.getBoolean("checked"), j.getBoolean("selected"), merged)
        } else {
            for (i in FLAGS.indices) g.flags[i] = g.flags[i] || f[i]
        }
    }
    return groups.values.sortedWith(compareBy({ it.box[1] }, { it.box[0] }, { it.box[3] }, { it.box[2] }, { it.label }, { it.className }))
        .map { ScreenCandidate(it.label, it.className, it.flags[0], it.flags[1], it.flags[2], it.flags[3], it.flags[4],
            it.checked, it.selected, it.enabled, it.box, it.rid) }
}

/** Labels of the visible selected nodes (the current tab, a chosen item) -- often not actionable, so not candidates. */
fun selectedLabels(state: JSONObject): List<String> = flatten(state)
    .filter { !it.ime && it.j.getBoolean("visible") && it.j.getBoolean("selected") }.map { label(it.j) }.filter { it.isNotEmpty() }

/**
 * The elements the target question shows when a screen has more than [k] (D-049). That row lists every element twice
 * (as an option and in the screen list), and Laya picks right 84% of the time from 1-5 elements but 54% from 21-50
 * (research/01 §2.6). Kept: elements whose words (label or resource id) meet [want], best first; text fields; then the
 * screen's top and bottom alternately (toolbars, tab bars). Indices into [cands], in screen order.
 */
fun shortlist(cands: List<ScreenCandidate>, want: Set<String>, k: Int): List<Int> {
    if (k <= 0 || cands.size <= k) return cands.indices.toList()
    fun hits(c: ScreenCandidate) = ((Routes.contentWords(c.label) + Routes.contentWords(c.rid)) intersect want).size
    val keep = LinkedHashSet<Int>()
    cands.indices.filter { hits(cands[it]) > 0 }.sortedByDescending { hits(cands[it]) }.take(k).forEach { keep += it }
    cands.indices.filter { cands[it].editable }.forEach { if (keep.size < k) keep += it }
    var top = 0
    var bottom = cands.size - 1
    while (keep.size < k) { keep += top++; if (keep.size < k) keep += bottom-- }
    return keep.sorted()
}

fun shortlistSelfCheck(): String {
    fun c(label: String, rid: String = "", edit: Boolean = false) =
        ScreenCandidate(label, "TextView", true, false, edit, false, false, false, false, true, IntArray(4), rid)
    val cands = listOf(c("Back"), c("Search", edit = true)) + (1..20).map { c("Item $it") } + listOf(c("", "action_orders"), c("Home"))
    val want = Routes.aboutWords("open my orders on meesho", "Meesho")
    // the orders tab (by its resource id), the text field, then the top and bottom of the screen
    check(shortlist(cands, want, 6) == listOf(0, 1, 2, 21, 22, 23)) { "shortlist: ${shortlist(cands, want, 6)}" }
    check(shortlist(cands.take(5), want, 6) == listOf(0, 1, 2, 3, 4) && shortlist(cands, want, 0) == cands.indices.toList())
    return "ok"
}

/** A visible text and its box: what the READ step's extractors parse (amounts, dates, names). */
class ScreenText(val text: String, val bounds: IntArray)

/** Every visible, non-IME node that has its own text, top-to-bottom then left-to-right. */
fun screenTexts(state: JSONObject): List<ScreenText> = flatten(state)
    .filter { !it.ime && it.j.getBoolean("visible") && ownText(it.j).isNotEmpty() }
    .map { n -> val b = n.j.getJSONArray("bounds"); ScreenText(ownText(n.j), IntArray(4) { b.getInt(it) }) }
    .sortedWith(compareBy({ it.bounds[1] }, { it.bounds[0] }))

/** Asserts the three option_label cases from scripts/laya_android.py __main__ (via a synthetic forest). */
fun candidatesSelfCheck(): String {
    fun node(cls: String, box: IntArray, kids: List<JSONObject> = emptyList(), text: String = "",
             desc: String = "", clickable: Boolean = false, editable: Boolean = false,
             checkable: Boolean = false, checked: Boolean = false, enabled: Boolean = true,
             visible: Boolean = true): JSONObject {
        val arr = org.json.JSONArray(); kids.forEach { arr.put(it) }
        return JSONObject().put("text", text).put("content_desc", desc).put("hint", "")
            .put("class", "android.widget.$cls").put("package", "p").put("resource_id", "")
            .put("bounds", org.json.JSONArray(box.toList())).put("visible", visible)
            .put("clickable", clickable).put("long_clickable", false).put("editable", editable)
            .put("scrollable", false).put("checkable", checkable).put("checked", checked)
            .put("selected", false).put("enabled", enabled).put("children", arr)
    }
    val app = node("FrameLayout", intArrayOf(0, 0, 100, 200), listOf(
        node("LinearLayout", intArrayOf(0, 0, 100, 50), listOf(
            node("TextView", intArrayOf(5, 5, 60, 25), text = "Battery"),
            node("TextView", intArrayOf(5, 25, 60, 45), text = "80%")), clickable = true),
        node("EditText", intArrayOf(0, 60, 100, 90), desc = "", clickable = true, editable = true).put("hint", "Search"),
        node("Switch", intArrayOf(0, 100, 100, 300), desc = "Wi-Fi", checkable = true, checked = true, enabled = false),
    ))
    val ime = node("Button", intArrayOf(0, 150, 10, 160), text = "q", clickable = true)
    val state = JSONObject().put("windows", org.json.JSONArray()
        .put(JSONObject().put("type", 1).put("root", app))
        .put(JSONObject().put("type", IME_WINDOW).put("root", ime)))
    val got = buildCandidates(state, 100, 200).map { optionLabel(it.toCandidate()) }
    val want = listOf("Battery 80% (LinearLayout)", "Search (EditText) edit", "Wi-Fi (Switch) on disabled")
    check(got == want) { "candidates: $got" }
    return "ok: $got"
}
