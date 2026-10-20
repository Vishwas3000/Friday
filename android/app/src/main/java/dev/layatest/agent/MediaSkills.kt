package dev.layatest.agent

import android.app.SearchManager
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import java.util.Locale
import dev.layatest.agent.coordinator.CapabilityResult
import dev.layatest.agent.coordinator.DeviceCapabilities as Contracts
import org.json.JSONArray
import org.json.JSONObject

/** Compatibility entries cannot execute an unbound free-text playback request. */
fun mediaSkills(): List<Skill> = listOf(PlayMusic, PlayVideo)

object PlayMusic : Skill {
    override val id = "play_music"
    override fun run(ctx: SkillContext) = "Which exact item should I play, and in which app?"
}

object PlayVideo : Skill {
    override val id = "play_video"
    override fun run(ctx: SkillContext) = "Which exact item should I play, and in which app?"
}

/** A typed app.action adapter; the coordinator owns source validation, retries and the dispatch receipt. */
internal object MediaActions {
    val skills = setOf("play_music", "play_video")
    private val playActions = setOf("play", "listen", "listen to", "watch", "start", "put on", "hear", "stream")
    private val transportKeys = setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    private fun normalized(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    private fun scopeName(text: String) = normalized(text).replace(Regex("^(?:my|your|our|the)\\s+"), "")
    private fun plays(intent: JSONObject) = normalized(intent.optString("action")) in playActions
    private fun key(input: JSONObject) = mediaKey(input.optString("request"), input.optString("app_label").ifBlank { null })
    private fun digest(intent: JSONObject) = Contracts.digest(Contracts.canonical(intent))
    private fun cancelled() = CapabilityResult("CANCELLED", message = "Stopped before changing playback.")

    fun inputError(input: JSONObject): String? {
        val intent = input.optJSONObject("intent")
        if (intent == null) return if (key(input) in transportKeys) null else "Which exact media item or app section should I use?"
        if (intent.optString("mode") !in setOf("search", "navigate", "act") || intent.optString("action").isBlank() ||
            intent.optString("source_id").isBlank() || intent.optJSONObject("proof")?.optString("request_sha256") != Contracts.digest(input.optString("request")))
            return "The playback interpretation has no matching saved request and source binding."
        if (intent.optString("target").isBlank() && (intent.optString("mode") != "navigate" || intent.optString("scope").isBlank()))
            return "Which exact item should I use? A relative reference is not a playback target."
        if (intent.optString("mode") == "act" && !plays(intent) && key(input) !in transportKeys)
            return "This media action has no registered result check."
        return null
    }

    /** Keep action, scope and target distinct; no raw request is converted into search text. */
    fun goal(intent: JSONObject): String = "In the selected app, " + intent.getString("action") +
        (intent.optString("target").takeIf { it.isNotBlank() }?.let { " the exact item named " + JSONObject.quote(it) }
            ?: if (plays(intent)) " one actual visible playable item" else "") +
        intent.optString("scope").takeIf { it.isNotBlank() }?.let { " within the section or collection " + JSONObject.quote(it) }.orEmpty() +
        ". Do not substitute another item or leave the selected app."

    fun execute(ctx: SkillContext, input: JSONObject, beforeDispatch: () -> Boolean): CapabilityResult {
        inputError(input)?.let { return CapabilityResult("WAITING_INPUT", message = it) }
        if (ctx.stopped) return cancelled()
        val app = ctx.apps.installed.firstOrNull { it.pkg == input.optString("package") }
            ?: return CapabilityResult("UNSUPPORTED", message = "The selected media app isn't installed.")
        val intent = input.optJSONObject("intent")
        val scope = intent?.optString("scope").orEmpty()
        val transport = if (intent == null) key(input) else null
        // One durable authorization, with Stop checked again before every subsequent mutation.
        var tried = false
        var allowed = false
        val gate = {
            !ctx.stopped && if (tried) allowed else {
                tried = true
                beforeDispatch().also { allowed = it }
            }
        }
        if (scope.isBlank() && transport !in setOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            inspect(ctx, input)?.let { return it.copy(output = JSONObject(it.output.toString()).put("reused", true)) }
        val before = playback(ctx, input.optString("package"))
        var scopeProof: JSONObject? = null
        if (transport != null) {
            val controller = controllers(ctx).firstOrNull { it.packageName == app.pkg }
            if (controller != null) {
                if (!gate()) return cancelled()
                controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, transport))
                if (ctx.stopped) return cancelled()
                controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, transport))
            } else {
                if (!gate()) return cancelled()
                if (ctx.foreground() != app.pkg && !ctx.open(app)) return CapabilityResult("BLOCKED", message = "I couldn't open the selected media app.")
                if (!ctx.waitUntil(4000) { ctx.foreground() == app.pkg }) return CapabilityResult("BLOCKED", message = "The selected media app isn't in the foreground.")
                if (!gate()) return cancelled()
                ctx.runLaya("In the selected app only, " + transportAction(transport) + " the current playback. Do not search for content.", maxSteps = 4)
            }
        } else {
            val saved = intent!!
            if (scope.isNotBlank()) {
                if (!gate()) return cancelled()
                if (ctx.foreground() != app.pkg && !ctx.open(app)) return CapabilityResult("BLOCKED", message = "I couldn't open the selected media app.")
                if (!ctx.waitUntil(4000) { ctx.foreground() == app.pkg }) return CapabilityResult("BLOCKED", message = "The selected media app isn't in the foreground.")
                scopeProof = scopeObservation(ctx.screen(), scope)
                if (scopeProof == null) {
                    if (!gate()) return cancelled()
                    ctx.runLaya("Open the section or collection " + JSONObject.quote(scope) +
                        " in this app; stop there before playback. Do not enter the section name into a search field.", maxSteps = 5)
                    if (ctx.stopped) return cancelled()
                    scopeProof = scopeObservation(ctx.screen(), scope)
                }
                if (scopeProof == null) return CapabilityResult("BLOCKED", message = "I couldn't verify the requested app section. Playback wasn't started.")
            }
            if (saved.optString("mode") == "navigate" && !plays(saved)) {
                if (scopeProof == null) {
                    if (!gate()) return cancelled()
                    if (ctx.foreground() != app.pkg && !ctx.open(app)) return CapabilityResult("BLOCKED", message = "The selected media app couldn't be opened.")
                    if (!gate()) return cancelled()
                    ctx.runLaya(goal(saved), maxSteps = 6)
                }
            } else if (scope.isNotBlank()) {
                if (!gate()) return cancelled()
                ctx.runLaya(goal(saved), maxSteps = 7, typeText = saved.optString("target").ifBlank { null })
            } else {
                val target = saved.getString("target")
                val action = if (plays(saved)) MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH else Intent.ACTION_SEARCH
                val launch = Intent(action).setPackage(app.pkg).putExtra(SearchManager.QUERY, target)
                if (!gate()) return cancelled()
                val launched = ctx.launch(launch)
                var early: CapabilityResult? = null
                if (launched) ctx.waitUntil(2500) {
                    if (ctx.stopped) true else observe(ctx, input, null, before).also { early = it } != null
                }
                if (ctx.stopped) return cancelled()
                early?.let { return it }
                if (!launched && ctx.foreground() != app.pkg) {
                    if (!gate()) return cancelled()
                    if (!ctx.open(app)) return CapabilityResult("BLOCKED", message = "The selected media app couldn't be opened.")
                }
                if (!ctx.waitUntil(4000) { ctx.foreground() == app.pkg }) return CapabilityResult("BLOCKED", message = "The selected media app isn't in the foreground.")
                if (!gate()) return cancelled()
                ctx.runLaya(goal(saved), maxSteps = 7, typeText = target)
            }
        }
        if (ctx.stopped) return cancelled()
        var result: CapabilityResult? = null
        ctx.waitUntil(3500) { if (ctx.stopped) true else observe(ctx, input, scopeProof, before).also { result = it } != null }
        return if (ctx.stopped) cancelled() else result ?: CapabilityResult("BLOCKED",
            message = "The requested media outcome couldn't be verified in the selected app. I haven't marked it complete.")
    }

    fun inspect(ctx: SkillContext, input: JSONObject): CapabilityResult? {
        if (inputError(input) != null) return null
        val intent = input.optJSONObject("intent")
        val scope = intent?.optString("scope").orEmpty()
        // A prior scope-only selection needs its original visible-item receipt; do not guess on recovery.
        if (scope.isNotBlank() && (intent?.optString("target").isNullOrBlank() && intent?.let(::plays) == true)) return null
        val proof = if (scope.isBlank()) null else scopeObservation(ctx.screen(), scope) ?: return null
        return observe(ctx, input, proof, null)
    }

    private fun observe(ctx: SkillContext, input: JSONObject, scope: JSONObject?, before: JSONObject?): CapabilityResult? {
        val intent = input.optJSONObject("intent")
        val screen = ctx.screen()
        if (intent != null && !plays(intent) && intent.optString("mode") in setOf("navigate", "search")) {
            if (screen.pkg != input.optString("package")) return null
            val target = intent.optString("target").ifBlank { intent.optString("scope") }
            val nodes = nodes(screen)
            val observation = if (intent.getString("mode") == "navigate") {
                val node = nodes.firstOrNull { destination(it, target) } ?: return null
                JSONObject().put("capture_method", "media_destination").put("destination", node).put("title", target)
            } else {
                val query = nodes.firstOrNull { it.optBoolean("editable") && it.optString("text") == target } ?: return null
                val result = nodes.firstOrNull { !it.optBoolean("editable") && normalized(it.optString("text")) == normalized(target) } ?: return null
                JSONObject().put("capture_method", "media_search").put("query_node", query).put("result_node", result).put("title", target)
            }
            val text = screen.texts.joinToString("\n") { it.text }
            observation.put("source_package", screen.pkg).put("screen_text", text).put("source_text_sha256", Contracts.digest(text))
            return receipt(input, observation, scope, before)
        }
        val frame = playback(ctx, input.optString("package")) ?: return null
        return receipt(input, frame, scope, before)
    }

    private fun controllers(ctx: SkillContext): List<MediaController> = runCatching {
        ctx.android.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(null).orEmpty()
    }.getOrDefault(emptyList())

    private fun playback(ctx: SkillContext, pkg: String): JSONObject? {
        controllers(ctx).filter { it.packageName == pkg }.forEach { controller ->
            val state = controller.playbackState ?: return@forEach
            val metadata = controller.metadata ?: return@forEach
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            if (!title.isNullOrBlank()) return JSONObject().put("capture_method", "media_session")
                .put("source_package", controller.packageName).put("controller_package", controller.packageName)
                .put("title", title).put("playback_state", state.state).put("position", state.position)
        }
        val screen = ctx.screen()
        if (screen.pkg != pkg) return null
        val visible = nodes(screen)
        val player = visible.firstOrNull { node ->
            playerContainer(node) && descendants(node, pkg).any { controlLabel(it).let { label ->
                label == "pause" || label.startsWith("pause ") || label == "play" || label == "resume"
            } }
        } ?: return null
        val nodes = descendants(player, pkg)
        val title = nodes.firstOrNull { !it.optBoolean("editable") &&
            Regex("(?:track|song|media|player|now.?playing).*title|title.*(?:track|song|media|player)|^title$", RegexOption.IGNORE_CASE)
                .containsMatchIn(it.optString("resource_id").substringAfterLast('/')) && it.optString("text").isNotBlank() } ?: return null
        val pause = nodes.firstOrNull { controlLabel(it).let { name -> name == "pause" || name.startsWith("pause ") } }
        val play = nodes.firstOrNull { controlLabel(it).let { name -> name == "play" || name.startsWith("play ") || name == "resume" } }
        val audio = ctx.android.getSystemService(AudioManager::class.java).isMusicActive
        val state = if (pause != null && audio) PlaybackState.STATE_PLAYING
            else if (play != null && !audio) PlaybackState.STATE_PAUSED else return null
        val text = screen.texts.joinToString("\n") { it.text }
        return JSONObject().put("capture_method", "media_ui").put("source_package", pkg)
            .put("title", title.getString("text")).put("title_node", title).put("control_node", pause ?: play).put("player_node", player)
            .put("playback_state", state).put("audio_active", audio).put("screen_text", text)
            .put("source_text_sha256", Contracts.digest(text))
    }

    private fun playerContainer(node: JSONObject) = Regex("now.?playing|mini.?player|playback|player|media.?controls", RegexOption.IGNORE_CASE)
        .containsMatchIn(node.optString("resource_id").substringAfterLast('/'))
    private fun descendants(root: JSONObject, pkg: String): List<JSONObject> {
        val found = mutableListOf<JSONObject>()
        fun visit(node: JSONObject) {
            if (!node.optBoolean("visible") || node.optString("package") != pkg) return
            found += node
            node.optJSONArray("children")?.let { children -> for (i in 0 until children.length()) children.optJSONObject(i)?.let(::visit) }
        }
        visit(root)
        return found
    }

    private fun controlLabel(node: JSONObject) = normalized(node.optString("content_desc").ifBlank { node.optString("text") })
    private fun destination(node: JSONObject, target: String): Boolean = scopeName(node.optString("text").ifBlank { node.optString("content_desc") }) == scopeName(target) &&
        (node.optBoolean("selected") || Regex("toolbar|header|title", RegexOption.IGNORE_CASE).containsMatchIn(node.optString("resource_id")))

    private fun scopeObservation(screen: Screen, scope: String): JSONObject? {
        val visible = nodes(screen)
        val selected = visible.firstOrNull { destination(it, scope) } ?: return null
        val text = screen.texts.joinToString("\n") { it.text }
        val players = visible.filter(::playerContainer).flatMap { descendants(it, screen.pkg.orEmpty()) }
        val items = visible.filter { it !== selected && it !in players && !it.optBoolean("editable") && !it.optBoolean("selected") &&
            !Regex("toolbar|header|tab|navigation", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("resource_id")) && it.optString("text").isNotBlank() }
        return JSONObject().put("source_package", screen.pkg).put("scope", scope).put("destination", selected)
            .put("items", JSONArray(items)).put("screen_text", text).put("source_text_sha256", Contracts.digest(text))
            .put("captured_at", System.currentTimeMillis())
    }

    private fun nodes(screen: Screen): List<JSONObject> {
        val found = mutableListOf<JSONObject>()
        fun visit(node: JSONObject) {
            if (!node.optBoolean("visible") || node.optString("package") != screen.pkg) return
            found += node
            node.optJSONArray("children")?.let { children -> for (i in 0 until children.length()) children.optJSONObject(i)?.let(::visit) }
        }
        screen.state?.optJSONArray("windows")?.let { windows ->
            for (i in 0 until windows.length()) windows.optJSONObject(i)?.takeIf { it.optInt("type") == 1 }?.optJSONObject("root")?.let(::visit)
        }
        return found
    }

    private fun receipt(input: JSONObject, observation: JSONObject, scope: JSONObject?, before: JSONObject?): CapabilityResult? {
        val intent = input.optJSONObject("intent")
        val proof = JSONObject(observation.toString()).put("verified", true).put("captured_at", System.currentTimeMillis())
            .put("input_bind", input.optString("_input_bind"))
        intent?.let { proof.put("intent_sha256", digest(it)) }
        scope?.let { proof.put("scope_observation", it) }
        before?.let { proof.put("before_title", it.optString("title")) }
        val title = observation.optString("title")
        val answer = when (observation.optString("capture_method")) {
            "media_destination" -> "Opened " + title + " in the selected app."
            "media_search" -> "Search results verified for “" + title + "” in the selected app."
            else -> if (observation.optInt("playback_state") == PlaybackState.STATE_PLAYING)
                "Playing “" + title + "” in the selected app." else "Playback state verified in the selected app."
        }
        val result = CapabilityResult(output = JSONObject().put("package", input.getString("package")).put("target", title).put("answer", answer), evidence = proof)
        return result.takeIf { verificationError(input, it) == null }
    }

    fun verificationError(input: JSONObject, result: CapabilityResult): String? {
        inputError(input)?.let { return it }
        val proof = result.evidence
        val pkg = input.optString("package")
        val intent = input.optJSONObject("intent")
        if (result.output.optString("package") != pkg || proof.optString("source_package") != pkg ||
            result.output.optString("answer").isBlank()) return "The selected media app wasn't verified."
        if (intent != null && proof.optString("intent_sha256") != digest(intent)) return "Media evidence belongs to a different saved interpretation."
        val scope = intent?.optString("scope").orEmpty()
        val scopeProof = proof.optJSONObject("scope_observation")
        if (scope.isNotBlank() && (scopeProof == null || scopeProof.optString("source_package") != pkg ||
                scopeProof.optString("scope") != scope || !destination(scopeProof.optJSONObject("destination") ?: JSONObject(), scope) ||
                scopeProof.optString("source_text_sha256") != Contracts.digest(scopeProof.optString("screen_text"))))
            return "The requested app section wasn't observed."
        val method = proof.optString("capture_method")
        val title = proof.optString("title")
        if (title.isBlank() || result.output.optString("target") != title) return "No actual media item or destination was observed."
        if (method == "media_destination") return if (intent != null && !plays(intent) && intent.optString("mode") == "navigate" &&
            destination(proof.optJSONObject("destination") ?: JSONObject(), intent.optString("target").ifBlank { scope })) null else "A selected tab isn't playback evidence."
        if (method == "media_search") return if (intent != null && !plays(intent) && intent.optString("mode") == "search" &&
            proof.optJSONObject("query_node")?.optString("text") == intent.optString("target") &&
            normalized(proof.optJSONObject("result_node")?.optString("text").orEmpty()) == normalized(intent.optString("target"))) null else "Exact media search results weren't verified."
        if (method !in setOf("media_session", "media_ui")) return "No playback observation was supplied."
        if (method == "media_session" && proof.optString("controller_package") != pkg) return "Playback belongs to another app."
        if (method == "media_ui" && (proof.optString("source_text_sha256") != Contracts.digest(proof.optString("screen_text")) ||
                proof.optJSONObject("title_node")?.optString("text") != title ||
                proof.optJSONObject("title_node")?.optString("package") != pkg ||
                proof.optJSONObject("control_node")?.optString("package") != pkg ||
                !playerContainer(proof.optJSONObject("player_node") ?: JSONObject()) ||
                descendants(proof.optJSONObject("player_node") ?: JSONObject(), pkg).none { it.toString() == proof.optJSONObject("title_node")?.toString() } ||
                descendants(proof.optJSONObject("player_node") ?: JSONObject(), pkg).none { it.toString() == proof.optJSONObject("control_node")?.toString() })) return "Now-playing UI evidence is missing."
        val expected = intent?.optString("target").orEmpty()
        val transport = if (intent == null) key(input) else null
        val state = proof.optInt("playback_state", -1)
        if (transport == KeyEvent.KEYCODE_MEDIA_PAUSE) return if (state == PlaybackState.STATE_PAUSED &&
            (method != "media_ui" || (!proof.optBoolean("audio_active") && controlLabel(proof.optJSONObject("control_node") ?: JSONObject()).let { it == "play" || it.startsWith("play ") || it == "resume" }))) null else "Pause wasn't verified."
        if (transport == KeyEvent.KEYCODE_MEDIA_STOP) return if (state == PlaybackState.STATE_STOPPED) null else "Stop wasn't verified."
        if (state != PlaybackState.STATE_PLAYING) return "The selected item isn't playing."
        if (method == "media_ui" && (!proof.optBoolean("audio_active") || !controlLabel(proof.optJSONObject("control_node") ?: JSONObject()).startsWith("pause")))
            return "Active audio alone doesn't verify selected-app playback."
        if (transport in setOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS) &&
            (proof.optString("before_title").isBlank() || normalized(proof.getString("before_title")) == normalized(title))) return "The requested track change wasn't observed."
        if (expected.isNotBlank() && normalized(title) != normalized(expected)) return "The playing item differs from the exact requested item."
        if (scope.isNotBlank() && scopeProof != null) {
            val items = scopeProof.optJSONArray("items") ?: return "No visible scope items were captured."
            if ((0 until items.length()).none { normalized(items.optJSONObject(it)?.optString("text").orEmpty()) == normalized(title) })
                return "The playing item wasn't observed in the requested section."
        } else if (intent != null && expected.isBlank()) return "No exact playback target was grounded."
        return null
    }

    fun selfCheck(): String {
        val pkg = "synthetic.media"
        fun input(target: String = "Amber Horizon", scope: String = "", mode: String = "act"): JSONObject {
            val request = if (scope.isBlank()) "play " + target else "play in " + scope
            val intent = JSONObject().put("mode", mode).put("action", "play").put("target", target).put("scope", scope)
                .put("source_id", "request").put("proof", JSONObject().put("request_sha256", Contracts.digest(request)))
            return JSONObject().put("request", request).put("package", pkg).put("skill", "play_music").put("_input_bind", "synthetic")
                .put("intent", intent)
        }
        fun frame(title: String = "Amber Horizon", state: Int = PlaybackState.STATE_PLAYING) = JSONObject()
            .put("capture_method", "media_session").put("source_package", pkg).put("controller_package", pkg)
            .put("title", title).put("playback_state", state)
        fun node(text: String, role: String, selected: Boolean = false) = JSONObject().put("text", text).put("package", pkg)
            .put("resource_id", role).put("visible", true).put("selected", selected).put("children", JSONArray())
        val scope = JSONObject().put("source_package", pkg).put("scope", "my collection")
            .put("destination", node("Collection", "collection_title", true))
            .put("items", JSONArray().put(node("Amber Horizon", "item_title")))
            .put("screen_text", "Collection\nAmber Horizon").put("source_text_sha256", Contracts.digest("Collection\nAmber Horizon"))
        val exact = input()
        check(inputError(exact) == null)
        check(receipt(exact, frame(), null, null) != null)
        check(receipt(exact, frame("Other item"), null, null) == null)
        check(receipt(exact, frame(state = PlaybackState.STATE_PAUSED), null, null) == null)
        check(receipt(exact, frame().put("source_package", "synthetic.other"), null, null) == null)
        check(receipt(exact, frame().put("controller_package", "synthetic.other"), null, null) == null)
        check(receipt(exact, frame().put("capture_method", "media_ui").put("audio_active", true), null, null) == null)
        val scoped = input("", "my collection", "navigate")
        check(inputError(scoped) == null)
        check(goal(scoped.getJSONObject("intent")).contains("my collection"))
        check(goal(scoped.getJSONObject("intent")).contains("one actual visible playable item"))
        check(receipt(scoped, frame(), scope, null) != null)
        check(receipt(scoped, frame(), null, null) == null)
        check(receipt(scoped, frame("Unobserved song"), scope, null) == null)
        check(receipt(scoped, JSONObject().put("capture_method", "media_destination").put("source_package", pkg)
            .put("title", "my collection").put("destination", scope.getJSONObject("destination")), scope, null) == null)
        check(receipt(scoped, frame(state = PlaybackState.STATE_PAUSED), scope, null) == null)
        val changed = JSONObject(scoped.toString()).put("request", "a different request")
        check(inputError(changed) != null)
        check(inputError(JSONObject().put("request", "play any one of above").put("package", pkg)) != null)
        val pause = JSONObject().put("request", "pause the music").put("package", pkg).put("_input_bind", "synthetic")
        check(inputError(pause) == null)
        check(receipt(pause, frame(state = PlaybackState.STATE_PAUSED), null, null) != null)
        check(receipt(pause, frame(), null, null) == null)
        val next = JSONObject().put("request", "next song").put("package", pkg).put("_input_bind", "synthetic")
        check(receipt(next, frame(), null, frame("Quiet River")) != null)
        check(receipt(next, frame(), null, frame()) == null)
        check(receipt(next, frame(), null, null) == null)
        return "ok"
    }

    private fun transportAction(key: Int) = when (key) {
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
        KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "skip to the next item"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "go to the previous item"
        else -> "resume"
    }
}

/** A transport request ("pause the music", "skip this song") -> its media key; null if it names something to play. */
fun mediaKey(request: String, app: String? = null): Int? {
    val l = words(request, app).map { it.lowercase(Locale.ROOT) }
    val key = l.firstNotNullOfOrNull { KEYS[it] } ?: return null
    return key.takeIf { l.all { it in KEYS || it in POLITE || it in CONTROL } }
}

/** What to search for: the request minus its leading command/filler words and trailing fillers ("play some jazz music" -> "jazz"). */
fun mediaQuery(request: String, app: String? = null): String {
    val w = words(taskPart(request), app)  // "open youtube and play despacito" -> "despacito", not "open and play despacito"
    val l = w.map { it.lowercase(Locale.ROOT) }
    var a = 0
    while (a < l.size && l[a] in POLITE) a++
    if (a < l.size && l[a] in VERBS) a++
    // an article goes only with a filler ("the latest album by X"); "the weeknd" keeps it
    while (a < l.size && (l[a] in FILLER || (l[a] in ARTICLES && l.getOrNull(a + 1) in FILLER))) a++
    var b = l.size
    while (b > a && l[b - 1] in TRAIL) b--
    return w.subList(a, b).joinToString(" ")
}

/** MEDIA_PLAY_FROM_SEARCH focus and the extra that names it: a known genre, an artist ("songs by X"), else unstructured. */
fun musicFocus(request: String, query: String): Pair<String, String?> = when {
    query.lowercase(Locale.ROOT) in GENRES -> MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE to MediaStore.EXTRA_MEDIA_GENRE
    query.isNotEmpty() && Regex("\\bby\\s+${Regex.escape(query)}", RegexOption.IGNORE_CASE).containsMatchIn(request) ->
        MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE to MediaStore.EXTRA_MEDIA_ARTIST
    else -> "vnd.android.cursor.item/*" to null  // the app searches the query; "" means play anything
}

/** The request's words without the named app ("play jazz on Spotify" -> play, jazz) or edge punctuation. */
private fun words(request: String, app: String?): List<String> {
    val s = if (app == null) request
        else request.replace(Regex("\\b((on|in|using|via|from|with)\\s+)?${Regex.escape(app)}\\b", RegexOption.IGNORE_CASE), " ")
    return s.replace('’', '\'').split(Regex("\\s+")).map { it.trim(',', '.', '!', '?') }.filter { it.isNotEmpty() }
}

private val POLITE = setOf("hey", "friday", "please", "just", "can", "could", "would", "will", "you", "i", "i'd",
    "want", "wanna", "like", "love", "to", "let's")
private val VERBS = setOf("play", "put", "throw", "listen", "hear", "watch", "stream", "show", "start", "continue", "resume",
    "find", "search", "look")
private val FILLER = setOf("on", "to", "me", "for", "playing", "watching", "some", "few", "something", "anything", "latest",
    "by", "song", "songs", "music", "track", "tracks", "album", "albums", "video", "videos")
private val ARTICLES = setOf("a", "an", "the", "my")
private val TRAIL = setOf("song", "songs", "music", "track", "tracks", "album", "albums", "video", "videos", "please")
private val GENRES = setOf("jazz", "blues", "rock", "pop", "classical", "hip hop", "rap", "metal", "country", "edm", "lofi",
    "lo-fi", "reggae", "indie", "folk", "bollywood", "punjabi", "carnatic", "ghazal", "sufi", "devotional", "instrumental")
private val KEYS = mapOf(
    "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE, "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
    "resume" to KeyEvent.KEYCODE_MEDIA_PLAY, "continue" to KeyEvent.KEYCODE_MEDIA_PLAY,
    "next" to KeyEvent.KEYCODE_MEDIA_NEXT, "skip" to KeyEvent.KEYCODE_MEDIA_NEXT,
    "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
)
/** Words a transport request may carry besides its key: "go back to the previous song". */
private val CONTROL = setOf("the", "this", "that", "it", "a", "one", "song", "track", "music", "video", "playback",
    "playing", "play", "go", "back", "now")

/** Asserts for the media rules; the "selfcheck" bridge command runs it on the phone. */
fun mediaSelfCheck(): String {
    check(MediaActions.selfCheck() == "ok")
    check(mediaQuery("play jazz music") == "jazz")
    check(mediaQuery("open youtube and play despacito", "YouTube") == "despacito")
    check(mediaQuery("I want to hear some Taylor Swift") == "Taylor Swift")
    check(mediaQuery("play some arijit singh songs") == "arijit singh")
    check(mediaQuery("play the latest album by Taylor Swift") == "Taylor Swift")
    check(mediaQuery("play the weeknd") == "the weeknd")
    check(mediaQuery("play stand by me") == "stand by me")
    check(mediaQuery("can you play some music") == "")
    check(mediaQuery("play arijit singh on Spotify", "Spotify") == "arijit singh")
    check(mediaQuery("watch the boys on prime video", "Prime Video") == "the boys")
    check(musicFocus("play jazz music", "jazz").second == MediaStore.EXTRA_MEDIA_GENRE)
    check(musicFocus("play songs by Arijit Singh", "Arijit Singh").second == MediaStore.EXTRA_MEDIA_ARTIST)
    check(musicFocus("play yellow by coldplay", "yellow by coldplay").second == null)
    check(mediaKey("pause the music") == KeyEvent.KEYCODE_MEDIA_PAUSE)
    check(mediaKey("go back to the previous song") == KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    check(mediaKey("pause Spotify", "Spotify") == KeyEvent.KEYCODE_MEDIA_PAUSE)
    check(mediaKey("play next to me") == null)
    check(mediaKey("play stop and stare") == null)
    return "ok"
}
