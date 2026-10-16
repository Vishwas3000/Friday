package dev.layatest.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Status screen: is the accessibility service on, and how to drive it. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f; setPadding(48, 160, 48, 32) }
        val open = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(open)
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (AgentService.instance != null) {
            "Friday: ON\n\nDrive it from the computer:\nuv run python scripts/live_adb.py --app \"<goal>\"\n\n" +
                "Each step appears here with Run / Skip / Stop."
        } else {
            "Friday: OFF\n\nTurn on \"Friday\" under Accessibility → Downloaded apps."
        }
    }
}
