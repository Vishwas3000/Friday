package dev.layatest.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Status + type a goal and run it on this phone (Phase 2d: no computer). */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var goal: EditText
    private lateinit var listen: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f; setPadding(48, 140, 48, 16) }
        goal = EditText(this).apply {
            hint = "Goal, e.g. Open Settings and turn on Wi-Fi"
            setPadding(48, 16, 48, 16)
        }
        val run = Button(this).apply {
            text = "Run on this phone"
            setOnClickListener {
                val svc = AgentService.instance
                val g = goal.text.toString().trim()
                when {
                    svc == null -> toast("Turn on Friday in Accessibility first")
                    g.isEmpty() -> toast("Type a goal")
                    else -> { svc.runGoal(g); toast("Running — approve each step on screen") }
                }
            }
        }
        val teach = Button(this).apply {
            text = "Teach this (record me doing it)"
            setOnClickListener {
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
                val log = java.io.File(c.filesDir, Assistant.REQUEST_LOG).let { it.exists() && it.delete() }
                toast("Deleted ${Teacher.wipe(c)} recordings, ${Routes.wipe(c)} remembered paths" +
                    (if (log) " and the request log" else "") + " from this phone")
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
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(goal); addView(run); addView(teach); addView(wipe); addView(open); addView(listen)
        })
        // every launch (icon, "Hey Google, open Friday", Quick Tap, the wake word) opens ready to type; the
        // manifest's stateAlwaysVisible brings the keyboard up whenever this window gets focus
        goal.requestFocus()
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        WakeWord.setOn(this, ok)  // a "no" leaves listening off
        listen.isChecked = ok
        if (!ok) toast("Friday needs the microphone to hear “Hey Friday”")
    }

    override fun onResume() {
        super.onResume()
        AgentService.instance?.prewarm() // load Laya while the owner types (~10 s), so Run doesn't wait for it
        listen.isChecked = WakeWord.isOn(this)
        AgentService.instance?.wake?.update()  // picks up a mic grant made outside the app (setup_phone.py, Settings)
        status.text = if (AgentService.instance != null) {
            "Friday: ON\n\nType a goal below and tap Run — Laya decides on this phone and asks before each step."
        } else {
            "Friday: OFF\n\nTurn on \"Friday\" under Accessibility → Downloaded apps."
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
