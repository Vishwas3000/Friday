package dev.layatest.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** One bounded local interpretation. Only the caller can supply verified sources from the selected chat. */
internal object RequestResolver {
    data class Source(val id: String, val text: String)
    data class Resolution(val intent: JSONObject? = null, val error: String? = null)

    private val referenceWords = setOf("a", "an", "the", "any", "some", "all", "one", "ones", "of", "from", "in", "on", "it", "its", "this", "that", "them", "they", "these", "those", "above", "below", "previous", "earlier", "same", "former", "latter", "first", "last", "next")
    private val referencePhrase = Regex("\\b(?:any|anyone|some|one|ones|either|all)\\b.*\\b(?:above|below|previous|earlier|these|those|them)\\b", RegexOption.IGNORE_CASE)
    private val sourceId = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val listedLine = Regex("^[\\t ]*(?:[-*+•][\\t ]+|[0-9]+[.)][\\t ]+)")
    private data class Span(val start: Int, val end: Int)

    fun resolve(request: String, sources: List<Source>, write: (WriterRequest) -> JSONObject): Resolution {
        inputProblem(request, sources)?.let { return Resolution(error = it) }
        val result = try { write(writerRequest(request, sources)) } catch (_: Exception) {
            return Resolution(error = "The local intent model failed. Name the exact target and app section to continue.")
        }
        if (result.optString("status") != "ok") return Resolution(error =
            "The local intent model didn't return a checked interpretation (${result.optString("status", "unknown")}). Name the exact target to continue.")
        val intent = JSONObject()
        for (key in Writer.intentKeys) if (result.has(key)) intent.put(key, result.get(key))
        val malformed = Writer.intentErrors(intent)
        if (malformed.isNotEmpty()) return Resolution(error = "The intent model returned malformed fields: ${malformed.joinToString("; ")}.")
        if (selected(request, sources, intent.getString("source_id")) == null)
            return Resolution(error = "The intent cited an unavailable source. Choose a target from this chat's verified results.")
        // A section cannot also be its own contained item; retain an explicitly quoted item title.
        val target = intent.optString("target")
        val scope = intent.optString("scope")
        fun section(value: String) = value.trim().lowercase().replace(Regex("^(?:my|your|our|the)\\s+"), "")
        val currentScope = scope.isNotBlank() && span(request, scope) != null
        val targetAt = target.takeIf { it.isNotBlank() }?.let { targetSpan(request, "request", it) }
        val indefinite = intent.optString("source_id") == "request" && targetAt != null &&
            Regex("\\b(?:a|an|any|some)[\\t ]+$", RegexOption.IGNORE_CASE).containsMatchIn(request.take(targetAt.start))
        if (target.isNotBlank() && currentScope && !quoted(request, target) && (section(target) == section(scope) || indefinite)) {
            intent.put("mode", "navigate").put("target", "").put("source_id", "request")
        }
        val error = groundingError(request, sources, intent)
        if (error != null) return Resolution(error = error)
        intent.put("proof", proof(request, sources, intent))
        return Resolution(intent = intent)
    }

    /** Saved inputs may carry additional source provenance; the model sees only these exact id/text pairs. */
    fun inputError(input: JSONObject): String? {
        return try {
        if (input.opt("request") !is String || input.opt("sources") !is JSONArray) "Saved intent inputs need a request and a source array."
        else {
            val records = input.getJSONArray("sources")
            val sources = ArrayList<Source>()
            for (i in 0 until records.length()) {
                val item = records.optJSONObject(i) ?: return "A saved source record is invalid."
                if (item.opt("id") !is String || item.opt("text") !is String) return "A saved source needs exact id and text strings."
                sources += Source(item.getString("id"), item.getString("text"))
            }
            inputProblem(input.getString("request"), sources)
        }
        } catch (_: Exception) { "Saved intent inputs are invalid." }
    }

    /** Re-run at the coordinator boundary, including after restoring an interrupted task. */
    fun validate(request: String, sources: List<Source>, intent: JSONObject): String? {
        inputProblem(request, sources)?.let { return it }
        if (intent.keys().asSequence().toSet() != Writer.intentKeys + "proof") return "Resolved intent fields are missing or unexpected."
        val fields = JSONObject()
        Writer.intentKeys.forEach { fields.put(it, intent.get(it)) }
        groundingError(request, sources, fields)?.let { return it }
        val saved = intent.optJSONObject("proof") ?: return "Resolved intent has no source binding."
        val expected = proof(request, sources, fields)
        if (saved.keys().asSequence().toSet() != expected.keys().asSequence().toSet() || expected.keys().asSequence().any { saved.opt(it) != expected.get(it) })
            return "Resolved intent no longer matches the exact request and cited source spans."
        return null
    }

    private fun writerRequest(request: String, sources: List<Source>): WriterRequest = WriterRequest(
        kind = "intent", instruction = request,
        text = JSONArray().also { records -> sources.forEach { records.put(JSONObject().put("id", it.id).put("text", it.text)) } }.toString(),
        maxTokens = 128, timeoutMs = 30_000,
    )

    private fun inputProblem(request: String, sources: List<Source>): String? {
        if (request.isBlank()) return "What should Friday do?"
        if (sources.any { it.id == "request" || !sourceId.matches(it.id) || it.text.isBlank() } || sources.map { it.id }.distinct().size != sources.size)
            return "Cited sources need distinct identifiers and nonempty exact text."
        val errors = writerRequest(request, sources).errors()
        if (errors.any { it.contains("2800") }) return "The request and cited excerpts exceed the local 2800-character context limit. Provide a shorter exact target or a smaller cited excerpt."
        return errors.takeIf { it.isNotEmpty() }?.let { "The local intent input is unsupported: ${it.joinToString("; ")}." }
    }

    private fun selected(request: String, sources: List<Source>, id: String): Source? =
        if (id == "request") Source("request", request) else sources.singleOrNull { it.id == id }

    private fun actionMatch(request: String, action: String) = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(action)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).find(request)

    /** Exact case and whole word/number boundaries; repeated spans need clarification. */
    private fun span(text: String, value: String): Span? {
        if (value.isEmpty()) return Span(0, 0)
        return Regex("(?<![\\p{L}\\p{M}\\p{N}_])${Regex.escape(value)}(?![\\p{L}\\p{M}\\p{N}_])")
            .findAll(text).map { Span(it.range.first, it.range.last + 1) }.singleOrNull()
    }

    private fun targetSpan(text: String, sourceId: String, value: String): Span? {
        if (sourceId == "request" && quoted(text, value)) {
            val matches = listOf("\"$value\"", "“$value”").flatMap { quoted ->
                Regex(Regex.escape(quoted)).findAll(text).map { Span(it.range.first + 1, it.range.last) }.toList()
            }
            return matches.singleOrNull()
        }
        return span(text, value)
    }

    private fun containingLine(text: String, at: Span): Pair<Int, String> {
        val start = text.lastIndexOf('\n', at.start - 1) + 1
        return start to text.substring(start).substringBefore('\n').removeSuffix("\r")
    }

    private fun unresolved(value: String): Boolean {
        val words = Regex("[\\p{L}]+").findAll(value).map { it.value.lowercase() }.toList()
        return words.isNotEmpty() && words.all { it in referenceWords } || referencePhrase.containsMatchIn(value)
    }

    private fun quoted(request: String, value: String) = request.contains("\"$value\"") || request.contains("“$value”")

    private fun groundingError(request: String, sources: List<Source>, fields: JSONObject): String? {
        val malformed = Writer.intentErrors(fields)
        if (malformed.isNotEmpty()) return "The intent model returned malformed fields: ${malformed.joinToString("; ")}."
        val action = actionMatch(request, fields.getString("action")) ?: return "The resolved action wasn't present in the current request."
        val cited = selected(request, sources, fields.getString("source_id")) ?: return "The intent cited an unavailable source. Choose a target from this chat's verified results."
        val mode = fields.getString("mode")
        val target = fields.getString("target")
        val scope = fields.getString("scope")
        if (target.isNotEmpty() && target.isBlank() || scope.isNotEmpty() && scope.isBlank()) return "Target and section values cannot be whitespace only."
        if (target.isBlank() && scope.isBlank()) return "Which exact item or app section should I use? The supplied excerpts didn't resolve the request."
        if (mode != "navigate" && target.isBlank()) return "The request needs an exact item or search target; a section name alone isn't a search query."
        if (target.isEmpty() && cited.id != "request") return "Scope-only navigation must be grounded in the current request."
        val targetAt = targetSpan(cited.text, cited.id, target)
        if (target.isNotEmpty() && targetAt == null) return "The target isn't one unique exact word/number span of the cited source."
        if (scope.isNotEmpty() && span(request, scope) == null) return "The app section wasn't uniquely requested in the current turn. Earlier source text cannot add a destination."
        if (cited.id != "request" && target.isNotEmpty() && referencePhrase.containsMatchIn(request)) {
            val line = containingLine(cited.text, targetAt!!).second
            if (!listedLine.containsMatchIn(line)) return "The selected value is a heading or explanation, not one of the listed items."
        }
        if (scope.isNotEmpty() && unresolved(scope) && !quoted(request, scope)) return "The app section is still an unresolved reference. Name the section to continue."
        if (cited.id == "request" && target.isNotEmpty() && !quoted(request, target)) {
            if (unresolved(target)) return "The target is still an unresolved reference. Choose an exact item from a cited result."
            if (targetAt!!.start <= action.range.last && targetAt.end > action.range.first)
                return "The target still includes the action rather than an exact item."
        }
        if ('\n' in target || '\r' in target) return "Choose one exact item; a whole list isn't an executable target."
        return null
    }

    private fun proof(request: String, sources: List<Source>, intent: JSONObject): JSONObject {
        val cited = selected(request, sources, intent.getString("source_id"))!!
        val target = intent.getString("target")
        val scope = intent.getString("scope")
        val scopeSource = Source("request", request)
        val action = actionMatch(request, intent.getString("action"))!!
        val proof = JSONObject().put("request_sha256", hash(request))
            .put("action_text", action.value).put("action_start", action.range.first).put("action_end", action.range.last + 1)
            .put("source_text_sha256", hash(cited.text)).put("scope_source_id", scopeSource.id)
            .put("scope_source_sha256", hash(scopeSource.text))
        fun bind(key: String, text: String, at: Span) {
            val start = at.start
            val end = at.end
            val excerptStart = maxOf(0, start - 80)
            val excerptEnd = minOf(text.length, end + 80)
            proof.put("${key}_start", start).put("${key}_end", end)
                .put("${key}_excerpt_start", excerptStart).put("${key}_excerpt", text.substring(excerptStart, excerptEnd))
        }
        val targetAt = targetSpan(cited.text, cited.id, target)!!
        bind("target", cited.text, targetAt)
        bind("scope", scopeSource.text, span(scopeSource.text, scope)!!)
        if (cited.id != "request" && target.isNotEmpty()) {
            val (start, line) = containingLine(cited.text, targetAt)
            proof.put("target_item", line).put("target_item_start", start)
                .put("target_item_listed", listedLine.containsMatchIn(line))
        }
        return proof
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun selfCheck(): String {
        val source = Source("task-verified", "Verified choices:\n1. Amber Horizon\n2. Quiet River\n")
        val other = Source("task-other", "Unrelated choice: Silver Meadow")
        fun answer(mode: String = "act", action: String = "play", target: String = "Amber Horizon", scope: String = "", sourceId: String = source.id) =
            JSONObject().put("status", "ok").put("mode", mode).put("action", action).put("target", target).put("scope", scope).put("source_id", sourceId)
        val request = "play any one of above in my collection"
        var calls = 0
        val resolved = resolve(request, listOf(source, other)) { r ->
            calls++
            check(r.kind == "intent" && r.maxTokens == 128 && r.timeoutMs <= 30_000 && r.errors().isEmpty())
            check(r.instruction == request && JSONArray(r.text).getJSONObject(0).getString("text") == source.text)
            answer(scope = "my collection")
        }
        check(calls == 1 && resolved.error == null && validate(request, listOf(source, other), resolved.intent!!) == null)
        check(resolved.intent.getJSONObject("proof").getString("scope_source_id") == "request")
        check(resolved.intent.getJSONObject("proof").getString("target_item") == "1. Amber Horizon")
        check(resolved.intent.getJSONObject("proof").getInt("target_item_start") == source.text.indexOf("1. Amber Horizon"))
        check(resolved.intent.getJSONObject("proof").getBoolean("target_item_listed"))
        check(span("songs", "song") == null && span("A731 7310", "731") == null)
        check(span("A song appears.", "song") == Span(2, 6) && span("A Song appears.", "song") == null)
        check(resolve(request, listOf(source, other)) { answer(target = "Silver Meadow") }.intent == null)
        check(resolve(request, listOf(source)) { answer(sourceId = "missing-task") }.intent == null)
        check(resolve(request, listOf(source)) { answer(target = "amber horizon") }.intent == null)
        check(resolve(request, listOf(source)) { answer(scope = "Silver Meadow") }.intent == null)
        check(resolve("play any one of above", listOf(source)) { answer(target = "Verified choices:") }.intent == null)
        check(resolve("play any one of above", listOf(source)) { answer(scope = "Verified choices:") }.intent == null)
        check(resolve("play any one of above", listOf(source.copy(text = "Amber Horizon\n1. Quiet River"))) { answer() }.intent == null)
        check(resolve("play any one of above", listOf(source.copy(text = "1. Amber Horizon\n2. Amber Horizon"))) { answer() }.intent == null)
        check(resolve("play any one of above", listOf(source.copy(text = "1. Popular songs\n2. Quiet River"))) { answer(target = "song") }.intent == null)
        check(resolve("play any one of above", emptyList()) { answer(target = "any one of above", sourceId = "request") }.intent == null)
        check(resolve("play anyone of the above song", emptyList()) { answer(target = "anyone of the above song", sourceId = "request") }.intent == null)
        check(resolve("play any one of above", emptyList()) { answer(target = "", sourceId = "request") }.intent == null)
        check(resolve("play any one of above", emptyList()) { JSONObject().put("status", "ok").put("text", "Amber Horizon") }.intent == null)
        check(resolve("play any one of above", listOf(source)) { answer().put("target", 7) }.intent == null)
        check(resolve("play any one of above", listOf(source)) { answer().put("status", "timeout") }.intent == null)
        val scopedAction = resolve("play a song from my collection", emptyList()) {
            answer("act", "play", "collection", "my collection", "request")
        }
        check(scopedAction.intent?.optString("mode") == "navigate" && scopedAction.intent.optString("target").isEmpty())
        for ((command, action, item, scope) in listOf(
            listOf("play a song from my collection", "play", "song", "my collection"),
            listOf("open an item from my collection", "open", "item", "my collection"),
            listOf("open any document from my downloads", "open", "document", "my downloads"),
            listOf("open some reports from my archive", "open", "reports", "my archive"),
        )) {
            val section = resolve(command, emptyList()) { answer("act", action, item, scope, "request") }
            check(section.intent?.optString("mode") == "navigate" && section.intent.optString("target").isEmpty())
            check(validate(command, emptyList(), section.intent) == null)
        }
        val quotedCommand = "play a \"song\" from my collection"
        val literal = resolve(quotedCommand, emptyList()) { answer("act", "play", "song", "my collection", "request") }
        check(literal.intent?.optString("mode") == "act" && literal.intent.optString("target") == "song")
        check(validate(quotedCommand, emptyList(), literal.intent) == null)
        check(resolve("open my collection", emptyList()) { answer("act", "open", "collection", "my collection", "missing-task") }.intent == null)
        check(resolve("open a 7 from my collection", emptyList()) { answer("act", "open", "7", "my collection", "request").put("target", 7) }.intent == null)
        val navigation = resolve("open my collection", emptyList()) { answer("navigate", "open", "", "my collection", "request") }
        check(navigation.intent != null && validate("open my collection", emptyList(), navigation.intent) == null)
        check(resolve("open my collection", emptyList()) { answer("search", "open", "", "my collection", "request") }.intent == null)
        val search = resolve("find Quiet River", emptyList()) { answer("search", "find", "Quiet River", "", "request") }
        check(search.intent != null && validate("find Quiet River", emptyList(), search.intent) == null)
        val tampered = JSONObject(resolved.intent.toString())
        tampered.getJSONObject("proof").put("target_start", 0)
        check(validate(request, listOf(source, other), tampered) != null)
        check(validate(request, listOf(source.copy(text = source.text + "Changed"), other), resolved.intent) != null)
        var invoked = false
        check(resolve("x".repeat(2800), emptyList()) { invoked = true; answer() }.intent == null && !invoked)
        check(inputError(JSONObject().put("request", request).put("sources", JSONArray().put(JSONObject().put("id", source.id).put("text", source.text)))) == null)
        return "ok"
    }
}
