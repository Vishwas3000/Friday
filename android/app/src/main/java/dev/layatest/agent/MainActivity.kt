package dev.layatest.agent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.layatest.agent.coordinator.TaskCoordinator
import dev.layatest.agent.coordinator.TaskSnapshot
import dev.layatest.agent.coordinator.TaskStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Local conversation history and a composer, with device controls tucked behind Tools. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var goal: EditText
    private lateinit var listen: Switch
    private lateinit var bubble: Switch
    private lateinit var stats: TextView
    private lateinit var turns: LinearLayout
    private lateinit var transcript: ScrollView
    private lateinit var taskCard: LinearLayout
    private var shownTaskId: String? = null
    private val taskWorker = Executors.newSingleThreadExecutor { Thread(it, "friday-task-card") }
    private var taskStore: TaskStore? = null // worker thread only
    private var taskObserving = false // worker thread only
    private var taskGeneration = 0
    @Volatile private var destroyed = false
    private var pending: String? = null
    private var pendingAt = 0L
    private var pendingChatId: String? = null
    @Volatile private var resumed = false
    private val historyChanged: () -> Unit = { runOnUiThread { if (resumed) { renderHistory(); refreshTask() } } }
    private val taskChanged: () -> Unit = { runOnUiThread { if (resumed && !destroyed) { refreshServiceStatus(); refreshTask() } } }
    private val prewarm = Runnable {
        val text = goal.text.toString().trim()
        // Unknown phrases load on Run; recognized navigation can warm up while the owner finishes typing.
        val skill = ruleSkill(text)
        if (!Writer.isDraftPrefix(taskPart(text)) && skill != null && skill !in setOf("message", "calendar_event")) AgentService.instance?.prewarm()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        pending = savedInstanceState?.getString("pending")
        pendingAt = savedInstanceState?.getLong("pending_at") ?: 0L
        pendingChatId = savedInstanceState?.getString("pending_chat_id")
        if (pendingChatId != ChatHistory.activeId(this)) pending = null
        status = TextView(this).apply {
            textSize = 18f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener {
                if (AgentService.instance == null) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        stats = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        turns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        transcript = ScrollView(this).apply { isFillViewport = true }
        taskCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(0xFF202124.toInt()); cornerRadius = dp(12).toFloat() }
        }
        goal = EditText(this).apply {
            hint = "Ask Friday, or enter a topic to research…"
            minLines = 1; maxLines = 4
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setText(savedInstanceState?.getString("composer").orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { schedulePrewarm() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        fun submit(research: Boolean) {
            if (clearingChat) return toast("Wait for this chat to finish clearing")
            goal.removeCallbacks(prewarm)
            val svc = AgentService.instance
            val g = goal.text.toString().trim()
            val submittedAt = System.currentTimeMillis()
            when {
                svc == null -> toast("Turn on Friday in Accessibility first")
                g.isEmpty() -> toast(if (research) "Type a topic to research" else "Type a goal")
                svc.runGoal(g, research = research) -> {
                    pending = g; pendingAt = submittedAt
                    pendingChatId = ChatHistory.activeId(this@MainActivity)
                    goal.text.clear()
                    goal.removeCallbacks(prewarm)
                    renderHistory()
                    refreshTask()
                    toast(if (research) "Researching with Gemini…" else "Running — watch progress on screen")
                }
                else -> toast("Friday is busy — try again in a moment")
            }
        }
        val run = Button(this).apply {
            text = "Run"
            setOnClickListener { submit(false) }
        }
        val research = Button(this).apply {
            text = "Research"
            contentDescription = "Research with Gemini"
            tooltipText = "Research this topic with Gemini"
            setOnClickListener { submit(true) }
        }
        val teach = Button(this).apply {
            text = "Teach this (record me doing it)"
            setOnClickListener {
                if (clearingChat) return@setOnClickListener toast("Wait for this chat to finish clearing")
                goal.removeCallbacks(prewarm)
                val svc = AgentService.instance
                val g = goal.text.toString().trim()
                when {
                    svc == null -> toast("Turn on Friday in Accessibility first")
                    g.isEmpty() -> toast("First type what you'll do")
                    svc.startTeaching(g) -> toast("Recording — do the task, then tap Done on the red banner")
                    else -> toast("Friday is busy — try again in a moment")
                }
            }
        }
        val wipe = Button(this).apply {
            text = "Delete all learned data"
            setOnClickListener {
                val c = this@MainActivity
                toast("Deleted ${Teacher.wipe(c)} recordings, ${Routes.wipe(c)} remembered paths, ${Meanings.wipe(c)} element names, " +
                    "${IconNamer.wipe(c)} icon names from this phone")
            }
        }
        val clear = Button(this).apply {
            text = "Clear this chat"
            setOnClickListener {
                if (canChangeChat()) {
                    clearSelectedChat()
                }
            }
        }
        val open = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        // the wake word (D-035), opt-in; a click listener, so onResume can set the state without asking for the mic
        listen = Switch(this).apply {
            text = "Listen for “Hey Friday” while charging"
            setPadding(48, 32, 48, 16)
            setOnClickListener { if (isChecked && !micGranted()) askMic() else WakeWord.setOn(this@MainActivity, isChecked) }
        }
        bubble = Switch(this).apply {
            text = "Show the Friday bubble over other apps"
            setPadding(48, 16, 48, 16)
            setOnClickListener {
                Bubble.setOn(this@MainActivity, isChecked)
                AgentService.instance?.bubble?.let { b -> if (isChecked) b.show() else b.hide() }
            }
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(teach); addView(clear); addView(wipe); addView(open); addView(listen); addView(bubble); addView(stats)
        }
        val toolsToggle = Button(this).apply {
            text = "Show tools"
            setOnClickListener {
                val show = tools.visibility != View.VISIBLE
                tools.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Hide tools" else "Show tools"
                if (show) stats.text = runCatching { Stats.sample(this@MainActivity) }.getOrElse { "stats: ${it.message}" }
            }
        }
        val chatNavigation = LinearLayout(this).apply {
            addView(Button(this@MainActivity).apply {
                text = "New chat"
                setOnClickListener {
                    if (canChangeChat()) {
                        ChatHistory.newSession(this@MainActivity)
                        resetConversation()
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "History"
                setOnClickListener { if (canChangeChat()) showChatHistory() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(turns)
            addView(taskCard)
            addView(toolsToggle)
            addView(tools)
        }
        transcript.addView(content)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chatNavigation, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(transcript, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(goal, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(this@MainActivity).apply {
                    addView(run, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(research, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                })
            })
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
                view.setPadding(dp(12) + bars.left, bars.top, dp(12) + bars.right, maxOf(bars.bottom, keyboard))
                insets
            }
        }
        setContentView(root)
        root.requestApplyInsets()
        // every launch (icon, "Hey Google, open Friday", Quick Tap, the wake word) opens ready to type; the
        // manifest's stateAlwaysVisible brings the keyboard up whenever this window gets focus
        goal.requestFocus()
        askMicForBubble(intent)
        askCalendarAccess(intent)
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

    /** The bubble's first tap without the mic: ask for it here (only an activity can), for talking, not "Hey Friday". */
    private fun askMicForBubble(i: Intent?) {
        if (i?.getBooleanExtra(ASK_MIC, false) == true && !micGranted()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), BUBBLE_MIC)
    }

    private fun askCalendarAccess(i: Intent?) {
        if (i?.getBooleanExtra(ASK_CALENDAR, false) != true) return
        i.removeExtra(ASK_CALENDAR)
        if (CalendarEvents.hasAccess(this)) { CalendarEvents.permissionResult = true; return }
        requestPermissions(CalendarEvents.permissions, CALENDAR_ACCESS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        askMicForBubble(intent)
        askCalendarAccess(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == CALENDAR_ACCESS) {
            CalendarEvents.permissionResult = CalendarEvents.hasAccess(this)
            return
        }
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (requestCode == BUBBLE_MIC) return toast(if (ok) "Microphone allowed: tap the bubble and talk" else "Friday needs the microphone to listen")
        WakeWord.setOn(this, ok)  // a "no" leaves listening off
        listen.isChecked = ok
        if (!ok) toast("Friday needs the microphone to hear “Hey Friday”")
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ChatHistory.observe(historyChanged)
        renderHistory()
        refreshTask()
        schedulePrewarm()
        listen.isChecked = WakeWord.isOn(this)
        bubble.isChecked = Bubble.isOn(this)
        AgentService.instance?.wake?.update()  // picks up a mic grant made outside the app (setup_phone.py, Settings)
        refreshServiceStatus()
    }

    override fun onPause() {
        resumed = false
        ChatHistory.removeObserver(historyChanged)
        taskGeneration++
        taskWorker.execute {
            if (taskObserving) { taskStore?.removeObserver(taskChanged); taskObserving = false }
        }
        goal.removeCallbacks(prewarm)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        taskWorker.execute {
            if (taskObserving) { taskStore?.removeObserver(taskChanged); taskObserving = false }
        }
        taskWorker.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending", pending)
        outState.putLong("pending_at", pendingAt)
        outState.putString("pending_chat_id", pendingChatId)
        outState.putString("composer", goal.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun renderHistory() {
        val entries = runCatching { ChatHistory.entries(this) }.getOrElse { emptyList() }
        if (pendingChatId != ChatHistory.activeId(this)) pending = null
        if (entries.any { it.optString("request") == pending && it.optLong("time") >= pendingAt }) pending = null
        turns.removeAllViews()
        if (entries.isEmpty() && pending == null) turns.addView(TextView(this).apply {
            text = "Start a conversation with Friday. Use New chat for a separate task, or History to continue an earlier chat."
            textSize = 16f; setPadding(dp(12), dp(16), dp(12), dp(16))
        })
        val date = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
        for (entry in entries) {
            val time = entry.optLong("time").takeIf { it > 0 }?.let { " · ${date.format(Date(it))}" }.orEmpty()
            turn("You$time", entry.optString("request"), true)
            turn("Friday · ${durationText(entry.optLong("ms"))}", entry.optString("answer"), false)
        }
        pending?.let { turn("You", it, true); turn("Friday", "Working…", false) }
        scrollToLatest()
    }

    private fun canChangeChat(): Boolean {
        if (clearingChat) { toast("Wait for this chat to finish clearing"); return false }
        if (AgentService.instance?.busy == true) {
            toast("Wait for Friday to finish before changing chats")
            return false
        }
        return true
    }

    private fun resetConversation() {
        goal.removeCallbacks(prewarm)
        goal.text.clear()
        pending = null
        pendingAt = 0L
        pendingChatId = null
        AgentService.instance?.session = null
        renderHistory()
        taskCard.visibility = View.GONE
        shownTaskId = null
        refreshTask()
        goal.requestFocus()
    }

    private fun clearSelectedChat() {
        val chatId = ChatHistory.activeId(this)
        val app = applicationContext
        clearingChat = true
        taskGeneration++
        taskWorker.execute {
            val cleared = runCatching {
                val service = AgentService.instance
                if (service != null) service.clearChat(chatId) else TaskCoordinator.clearChat(app, chatId)
            }.getOrDefault(false)
            runOnUiThread {
                clearingChat = false
                if (!destroyed) {
                    if (cleared && ChatHistory.activeId(this) == chatId) resetConversation()
                    else if (resumed) refreshTask()
                    toast(if (cleared) "This chat cleared" else "Couldn't clear this chat")
                }
            }
        }
    }

    private fun refreshTask() {
        if (!resumed || destroyed || clearingChat) return
        val chatId = ChatHistory.activeId(this)
        val generation = ++taskGeneration
        val app = applicationContext
        taskWorker.execute {
            if (!resumed || destroyed) return@execute
            val snapshot = runCatching {
                val store = taskStore ?: TaskStore.get(app).also { taskStore = it }
                if (!taskObserving) { store.observe(taskChanged); taskObserving = true }
                store.latestForChat(chatId)
            }
            runOnUiThread {
                if (resumed && !destroyed && !clearingChat && generation == taskGeneration && ChatHistory.activeId(this) == chatId) {
                    refreshServiceStatus()
                    if (snapshot.isSuccess) renderTask(snapshot.getOrNull())
                    else {
                        taskCard.removeAllViews()
                        taskCard.visibility = View.VISIBLE
                        taskCard.addView(taskText("Couldn't load the saved task. Reopen Friday to retry."))
                    }
                }
            }
        }
    }

    private fun renderTask(snapshot: TaskSnapshot?) {
        val showLatest = snapshot?.task?.id != shownTaskId
        shownTaskId = snapshot?.task?.id
        taskCard.removeAllViews()
        taskCard.visibility = if (snapshot == null) View.GONE else View.VISIBLE
        if (snapshot == null) return
        val task = snapshot.task
        val state = task.state.toString()
        val terminal = state in setOf("COMPLETED", "FAILED", "CANCELLED", "UNSUPPORTED")
        if (pending == task.request && task.createdAt >= pendingAt &&
            state in setOf("BLOCKED", "WAITING_INPUT", "WAITING_APPROVAL", "OUTCOME_UNKNOWN", "COMPLETED", "FAILED", "CANCELLED", "UNSUPPORTED")) {
            pending = null
            renderHistory()
        }
        val title = taskText("Task · ${state.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }}").apply {
            textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        taskCard.addView(title)
        taskCard.addView(taskText(task.request))
        if (task.message.orEmpty().isNotBlank()) taskCard.addView(taskText(task.message.orEmpty()))
        for ((index, step) in snapshot.steps.sortedBy { it.position }.withIndex()) {
            taskCard.addView(taskText("${index + 1}. ${step.title} · ${step.state.toString().lowercase().replace('_', ' ')} · ${durationText(step.activeMs)}"))
        }
        if (!terminal && AgentService.instance?.busy != true && state in setOf("RUNNING", "VERIFYING", "WAITING_EXTERNAL", "OUTCOME_UNKNOWN"))
            taskCard.addView(taskText("Friday was interrupted. Resume checks the saved progress before continuing."))
        if (state == "WAITING_INPUT") taskCard.addView(taskText("Enter your full request with the missing or corrected details, then tap Run."))
        taskCard.addView(taskText("Recorded active time · ${durationText(task.activeMs)}"))
        if (!terminal) taskCard.addView(LinearLayout(this).apply {
            if (state !in setOf("WAITING_INPUT", "WAITING_APPROVAL")) addView(Button(this@MainActivity).apply {
                text = "Resume"
                isEnabled = AgentService.instance != null && AgentService.instance?.busy != true && !clearingChat
                setOnClickListener {
                    val svc = AgentService.instance
                    when {
                        clearingChat -> toast("Wait for this chat to finish clearing")
                        svc == null -> toast("Turn on Friday in Accessibility first")
                        svc.resumeTask(task.id) -> { refreshTask(); toast("Resuming saved task…") }
                        else -> toast("Friday is busy or this task can't resume yet")
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Stop"
                isEnabled = AgentService.instance != null && !clearingChat
                setOnClickListener {
                    AgentService.instance?.cancelTask(task.id)
                    refreshTask()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        if (showLatest) scrollToLatest()
    }

    private fun refreshServiceStatus() {
        val enabled = AgentService.instance != null
        status.text = if (enabled) "Friday · ON" else "Friday · OFF — tap to enable"
        status.contentDescription = if (enabled) "Friday accessibility service enabled" else "Friday disabled. Tap to open Accessibility settings."
    }

    private fun scrollToLatest() = transcript.post { transcript.scrollTo(0, transcript.getChildAt(0)?.height ?: 0) }

    private fun durationText(milliseconds: Long): String {
        val tenths = Math.round(milliseconds.coerceAtLeast(0) / 100.0)
        val minutes = tenths / 600
        val seconds = (tenths % 600) / 10.0
        return if (minutes == 0L) String.format(Locale.getDefault(), "%.1fs", seconds)
        else String.format(Locale.getDefault(), "%dm %.1fs", minutes, seconds)
    }

    private fun taskText(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(Color.WHITE)
        setPadding(0, dp(3), 0, dp(3)); setTextIsSelectable(true)
    }

    private fun showChatHistory() {
        val sessions = ChatHistory.sessions(this)
        val active = ChatHistory.activeId(this)
        val date = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
        val labels = sessions.map { chat ->
            (if (chat.id == active) "✓ " else "") + chat.title +
                (if (chat.time > 0) "\n${date.format(Date(chat.time))}" else "")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Conversations").setItems(labels) { _, which ->
            val chosen = sessions[which]
            if (chosen.id != ChatHistory.activeId(this) && canChangeChat() && ChatHistory.selectSession(this, chosen.id)) resetConversation()
        }.setNegativeButton("Close", null).show()
    }

    private fun turn(who: String, message: String, user: Boolean) {
        if (message.isBlank()) return
        turns.addView(TextView(this).apply {
            text = "$who\n\n$message"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(if (user) 0xFF174EA6.toInt() else 0xFF30363D.toInt()); cornerRadius = dp(14).toFloat() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(if (user) dp(24) else 0, dp(4), if (user) 0 else dp(24), dp(8))
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun schedulePrewarm() {
        goal.removeCallbacks(prewarm)
        if (resumed) goal.postDelayed(prewarm, 500)
    }

    companion object {
        @Volatile private var clearingChat = false // survives Activity recreation while its worker finishes clearing
        const val ASK_MIC = "ask_mic"  // from the bubble: ask for the microphone
        const val ASK_CALENDAR = "ask_calendar"
        private const val BUBBLE_MIC = 2
        private const val CALENDAR_ACCESS = 3
    }
}
