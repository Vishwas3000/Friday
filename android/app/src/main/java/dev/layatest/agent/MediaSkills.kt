package dev.layatest.agent

import android.app.SearchManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import java.util.Locale

/**
 * Music and video skills (research/10 §1-2): media keys for pause/next/resume, MEDIA_PLAY_FROM_SEARCH for
 * music, a YouTube results link for video. Apps play only "when possible", so AudioManager.isMusicActive
 * says whether something started, and Laya taps the first result when nothing did.
 */
fun mediaSkills(): List<Skill> = listOf(PlayMusic, PlayVideo)

/** "play some arijit singh songs", "put on jazz on Spotify", "pause the music", "next song". */
object PlayMusic : Skill {
    override val id = "play_music"

    override fun run(ctx: SkillContext): String {
        val named = ctx.apps.named(ctx.request)
        mediaKey(ctx.request, named?.label)?.let { return control(ctx, it) }
        // "play X on YouTube" routes here on the word "play"; naming a video app makes it a video
        if (named != null && ctx.apps.candidates("play_video").any { it.pkg == named.pkg }) return PlayVideo.run(ctx)
        val app = ctx.appFor(id) ?: return "No music app to play it on"
        val query = ctx.slots.quoted.firstOrNull() ?: mediaQuery(ctx.request, app.label)
        val what = query.ifEmpty { "music" }
        val (focus, extra) = musicFocus(ctx.request, query)
        pauseCurrent(ctx)
        // CLEAR_TOP: NEW_TASK alone can just resurface the app's old screen (research/10 §0)
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(app.pkg)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(SearchManager.QUERY, query).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focus)
        if (extra != null) search.putExtra(extra, query)
        if (!ctx.launch(search) && !ctx.open(app)) return "I couldn't open ${app.label}"
        if (ctx.waitUntil(5000) { playing(ctx) }) return "Playing $what on ${app.label}"
        // Spotify may only show its results for the query (research/10): Laya taps the first one
        ctx.runLaya("play $what", maxSteps = 6, typeText = query.ifEmpty { null })
        return started(ctx, what, app)
    }
}

/** "watch the boys" (Prime Video), "play despacito on youtube", "pause the video". */
object PlayVideo : Skill {
    override val id = "play_video"
    private const val YOUTUBE = "com.google.android.youtube"

    override fun run(ctx: SkillContext): String {
        mediaKey(ctx.request, ctx.apps.named(ctx.request)?.label)?.let { return control(ctx, it) }
        val app = ctx.appFor(id) ?: return "No video app to play it on"
        val query = ctx.slots.quoted.firstOrNull() ?: mediaQuery(ctx.request, app.label)
        if (query.isEmpty()) return if (ctx.open(app)) "Opened ${app.label}" else "I couldn't open ${app.label}"
        pauseCurrent(ctx)
        val youtube = app.pkg == YOUTUBE
        val opened = if (youtube) ctx.launch(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
            .setPackage(app.pkg).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) else ctx.open(app)
        if (!opened) return "I couldn't open ${app.label}"
        ctx.waitUntil(5000) { ctx.foreground() == app.pkg }
        // No intent plays YouTube's first result (research/10); other apps need their own search, and typing asks the owner
        if (youtube) ctx.runLaya("play the first video for $query", maxSteps = 4) else ctx.runLaya(ctx.request, typeText = query)
        return started(ctx, query, app)
    }
}

private fun playing(ctx: SkillContext) = ctx.android.getSystemService(AudioManager::class.java).isMusicActive

/** A media key reaches the session that last played; no permission needed (research/10 §1). */
private fun press(ctx: SkillContext, key: Int) {
    val am = ctx.android.getSystemService(AudioManager::class.java)
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
}

/** Pause what's already playing, so isMusicActive afterwards means the new pick started. */
private fun pauseCurrent(ctx: SkillContext) {
    if (playing(ctx)) {
        press(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
        ctx.waitUntil(2000) { !playing(ctx) }
    }
}

private fun control(ctx: SkillContext, key: Int): String {
    val was = playing(ctx)
    press(ctx, key)
    return when {
        key == KeyEvent.KEYCODE_MEDIA_PLAY -> if (ctx.waitUntil(3000) { playing(ctx) }) "Resumed" else "Nothing to resume"
        !was -> "Nothing is playing"
        key == KeyEvent.KEYCODE_MEDIA_PAUSE -> "Paused"
        key == KeyEvent.KEYCODE_MEDIA_STOP -> "Stopped playback"
        key == KeyEvent.KEYCODE_MEDIA_NEXT -> "Next track"
        else -> "Previous track"
    }
}

/** Laya's DONE is weak (research/05), so the audio system says whether it worked. */
private fun started(ctx: SkillContext, what: String, app: AppInfo) =
    if (ctx.waitUntil(3000) { playing(ctx) }) "Playing $what on ${app.label}"
    else "I couldn't start $what on ${app.label} — the screen is where I stopped"

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
