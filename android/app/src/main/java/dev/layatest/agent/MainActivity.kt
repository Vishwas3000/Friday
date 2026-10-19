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
import android.text.TextWatcher
import android.view.Gravity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local conversation history and a composer, with device controls tucked behind Tools. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var goal: EditText
    private lateinit var listen: Switch
    private lateinit var bubble: Switch
    private lateinit var stats: TextView
    private lateinit var turns: LinearLayout
    private lateinit var transcript: ScrollView
    private var pending: String? = null
    private var pendingAt = 0L
    private var pendingChatId: String? = null
    private var resumed = false
    private val historyChanged: () -> Unit = { runOnUiThread { if (resumed) renderHistory() } }
    private val prewarm = Runnable {
        val text = goal.text.toString().trim()
        // Unknown phrases load on Run; recognized navigation can warm up while the owner finishes typing.
        val skill = ruleSkill(text)
        if (!Writer.isDraftPrefix(taskPart(text)) && skill != null && skill != "message") AgentService.instance?.prewarm()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        pending = savedInstanceState?.getString("pending")
        pendingAt = savedInstanceState?.getLong("pending_at") ?: 0L
        pendingChatId = savedInstanceState?.getString("pending_chat_id")
        if (pendingChatId != ChatHistory.activeId(this)) pending = null
        status = TextView(this).apply { textSize = 18f; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        stats = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        turns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        transcript = ScrollView(this).apply { isFillViewport = true; addView(turns) }
        goal = EditText(this).apply {
            hint = "Ask Friday or continue a task…"
            minLines = 1; maxLines = 4
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setText(savedInstanceState?.getString("composer").orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { schedulePrewarm() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val run = Button(this).apply {
            text = "Run"
            setOnClickListener {
                goal.removeCallbacks(prewarm)
                val svc = AgentService.instance
                val g = goal.text.toString().trim()
                val submittedAt = System.currentTimeMillis()
                when {
                    svc == null -> toast("Turn on Friday in Accessibility first")
                    g.isEmpty() -> toast("Type a goal")
                    svc.runGoal(g) -> {
                        pending = g; pendingAt = submittedAt
                        pendingChatId = ChatHistory.activeId(this@MainActivity)
                        goal.text.clear()
                        renderHistory()
                        toast("Running — watch progress on screen")
                    }
                    else -> toast("Friday is busy — try again in a moment")
                }
            }
        }
        val teach = Button(this).apply {
            text = "Teach this (record me doing it)"
            setOnClickListener {
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
                    val cleared = ChatHistory.clear(this@MainActivity)
                    if (cleared) resetConversation()
                    toast(if (cleared) "This chat cleared" else "Couldn't clear this chat")
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
        val tools = ScrollView(this).apply {
            visibility = View.GONE
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(teach); addView(clear); addView(wipe); addView(open); addView(listen); addView(bubble); addView(stats)
            })
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(LinearLayout(this@MainActivity).apply {
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
            })
            addView(transcript, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(toolsToggle)
            addView(tools, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.BOTTOM
                addView(goal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(run)
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
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

    /** The bubble's first tap without the mic: ask for it here (only an activity can), for talking, not "Hey Friday". */
    private fun askMicForBubble(i: Intent?) {
        if (i?.getBooleanExtra(ASK_MIC, false) == true && !micGranted()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), BUBBLE_MIC)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        askMicForBubble(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
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
        schedulePrewarm()
        listen.isChecked = WakeWord.isOn(this)
        bubble.isChecked = Bubble.isOn(this)
        AgentService.instance?.wake?.update()  // picks up a mic grant made outside the app (setup_phone.py, Settings)
        status.text = if (AgentService.instance != null) {
            "Friday · ON"
        } else {
            "Friday: OFF\n\nTurn on \"Friday\" under Accessibility → Downloaded apps."
        }
    }

    override fun onPause() {
        resumed = false
        ChatHistory.removeObserver(historyChanged)
        goal.removeCallbacks(prewarm)
        super.onPause()
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
            turn("Friday", entry.optString("answer"), false)
        }
        pending?.let { turn("You", it, true); turn("Friday", "Working…", false) }
        transcript.post { transcript.scrollTo(0, turns.height) }
    }

    private fun canChangeChat(): Boolean {
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
        goal.requestFocus()
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
        const val ASK_MIC = "ask_mic"  // from the bubble: ask for the microphone
        private const val BUBBLE_MIC = 2
    }
}
