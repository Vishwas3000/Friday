package dev.layatest.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Status + type a goal and run it on this phone (Phase 2d: no computer). */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f; setPadding(48, 140, 48, 16) }
        val goal = EditText(this).apply {
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
        val open = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(goal); addView(run); addView(open)
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (AgentService.instance != null) {
            "Friday: ON\n\nType a goal below and tap Run — Laya decides on this phone and asks before each step."
        } else {
            "Friday: OFF\n\nTurn on \"Friday\" under Accessibility → Downloaded apps."
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
