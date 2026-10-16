package dev.layatest.agent

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import java.text.DateFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * Phone & time skills, in the D-028 order: answer directly > intent/API > the narrowest settings
 * screen + Laya. Mechanisms from research/10.
 */
fun phoneSkills(): List<Skill> = listOf(DeviceStatus, AlarmTimer, DeviceSetting)

/** "what's my battery level", "how much storage is left", "when's my next alarm" -- answered, no UI. */
object DeviceStatus : Skill {
    override val id = "device_status"

    override fun run(ctx: SkillContext): String {
        val r = ctx.request.lowercase(Locale.ROOT)
        val parts = ArrayList<String>()
        if (Regex("batter|charg|power").containsMatchIn(r)) parts += battery(ctx.android)
        if (Regex("storage|space|memory").containsMatchIn(r)) parts += storage()
        if (Regex("alarm").containsMatchIn(r)) parts += nextAlarm(ctx.android)
        if (parts.isEmpty()) parts += listOf(battery(ctx.android), storage())
        return parts.joinToString("\n")
    }

    private fun battery(c: Context): String {
        val bm = c.getSystemService(BatteryManager::class.java)
        return "Battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" + if (bm.isCharging) ", charging" else ""
    }

    private fun storage(): String {
        val s = StatFs(Environment.getDataDirectory().path)
        return "Storage: %.1f GB free of %.0f GB".format(s.availableBytes / 1e9, s.totalBytes / 1e9)
    }

    private fun nextAlarm(c: Context): String {
        val next = c.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return "No alarm set"
        return "Next alarm: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(next.triggerTime))
    }
}

/** The alarm time [sec] seconds after [now], to the nearest minute (alarms are minute-granular). */
internal fun alarmAfter(now: LocalTime, sec: Int): Pair<Int, Int> =
    now.plusSeconds(sec + 30L).truncatedTo(ChronoUnit.MINUTES).let { it.hour to it.minute }

/** "remind me to call mom in 10 minutes" -> "call mom": the label the timer/alarm shows when it rings. */
internal fun reminderText(s: Slots): String? = s.after("remind me to", "remind me about", "remind me of", "reminder to",
    "reminder for", "reminder about", cutAt = setOf("in", "at", "after", "tomorrow", "tonight", "by", "for", "every"))

/**
 * "keep an alarm for 6", "wake me up at 7", "remind me to call mom in 10 mins", "remind me in few mins" --
 * AlarmClock intents with SKIP_UI; a reminder is a labelled timer or alarm. No time given: offer a few.
 */
object AlarmTimer : Skill {
    override val id = "alarm_timer"
    private val IN_MIN = listOf(5, 15, 30, 60)
    private val AT = listOf(6 to 0, 6 to 30, 7 to 0, 7 to 30, 8 to 0)

    override fun run(ctx: SkillContext): String {
        val note = reminderText(ctx.slots)
        var sec = ctx.slots.durationSec
        var clock = ctx.slots.clock
        if (sec == null && clock == null) {
            if (note != null || Regex("remind|timer", RegexOption.IGNORE_CASE).containsMatchIn(ctx.request)) {
                val i = ctx.ask("When should I remind you?", IN_MIN.map { if (it < 60) "In $it minutes" else "In 1 hour" }) ?: return "Cancelled"
                sec = IN_MIN[i] * 60
            } else {
                val i = ctx.ask("What time should the alarm ring?", AT.map { "%d:%02d AM".format(it.first, it.second) }) ?: return "Cancelled"
                clock = AT[i]
            }
        }
        val about = note?.let { " — \"$it\"" } ?: ""
        // The owner's word decides: only "timer" makes a timer (Clock › Timer). "Set an alarm for 8 mins from
        // now" is an alarm at now+8 -- the owner looks for it in the alarm list (2026-10-17 bug report).
        val inSec = sec
        if (inSec != null && !Regex("\\btimer\\b", RegexOption.IGNORE_CASE).containsMatchIn(ctx.request)) {
            clock = alarmAfter(LocalTime.now(), inSec)
            sec = null
        }
        sec?.let {
            // Int extras only: String extras are silently ignored (research/10)
            val ok = ctx.launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, it)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply { note?.let { n -> putExtra(AlarmClock.EXTRA_MESSAGE, n) } })
            // a timer can't be read back through a public API, so say what was asked and where to look
            return if (ok) "Started a ${human(it)} timer$about — Clock › Timer" else "No clock app took the timer"
        }
        val (h, m) = clock!!
        val am = ctx.android.getSystemService(AlarmManager::class.java)
        val before = am.nextAlarmClock?.triggerTime
        if (!ctx.launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { note?.let { n -> putExtra(AlarmClock.EXTRA_MESSAGE, n) } })) return "No clock app took the alarm"
        val time = LocalTime.of(h, m).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val inText = inSec?.let { " (in ${human(it)})" } ?: ""
        // Verify before claiming: Android's next-alarm record must now be this alarm.
        val confirmed = ctx.waitUntil(3000) {
            val next = am.nextAlarmClock ?: return@waitUntil false
            val at = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault()).toLocalTime()
            next.triggerTime != before && at.hour == h && at.minute == m
        }
        return if (confirmed) "Alarm set for $time$inText$about — Clock › Alarms"
        else "Asked the Clock app for a $time alarm$inText, but couldn't confirm it (an earlier alarm may be next) — check Clock › Alarms"
    }

    private fun human(sec: Int) = listOf(sec / 3600 to "h", sec % 3600 / 60 to "min", sec % 60 to "s")
        .filter { it.first > 0 }.joinToString(" ") { "${it.first} ${it.second}" }
}

/** Wi-Fi, flashlight, brightness directly; everything else via the narrowest settings screen + Laya. */
object DeviceSetting : Skill {
    override val id = "device_setting"

    override fun run(ctx: SkillContext): String {
        val r = ctx.request.lowercase(Locale.ROOT)
        val off = Regex("\\b(off|disable|stop|deactivate)\\b").containsMatchIn(r)
        return when {
            Regex("torch|flash ?light").containsMatchIn(r) -> torch(ctx, !off)
            Regex("wi-?fi").containsMatchIn(r) -> wifi(ctx, off)
            Regex("bright|dim\\b|darker|lighter").containsMatchIn(r) && !Regex("dark (mode|theme)").containsMatchIn(r) ->
                brightness(ctx, r)
            else -> viaSettings(ctx, r)
        }
    }

    private fun torch(ctx: SkillContext, on: Boolean): String {
        val cm = ctx.android.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight"
        cm.setTorchMode(id, on)
        return "Flashlight ${if (on) "on" else "off"}"
    }

    /** The system's own one-tap Allow dialog; the owner taps it (Friday never accepts system dialogs). */
    private fun wifi(ctx: SkillContext, off: Boolean): String {
        val action = if (off) "android.net.wifi.action.REQUEST_DISABLE" else "android.net.wifi.action.REQUEST_ENABLE"
        return if (ctx.launch(Intent(action))) "Tap Allow to turn Wi-Fi ${if (off) "off" else "on"}"
        else viaSettings(ctx, ctx.request.lowercase(Locale.ROOT))
    }

    private fun brightness(ctx: SkillContext, r: String): String {
        val c = ctx.android
        if (!Settings.System.canWrite(c)) {
            ctx.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${c.packageName}")))
            return "Allow Friday to modify system settings (one time), then ask again"
        }
        val cur = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val down = Regex("dim|darker|lower|decrease|reduce|down").containsMatchIn(r)
        // shortcut: linear 0-255 from a percent; the slider is perceptual (50% ~ 22), so "50%" looks brighter than the slider's 50%
        val target = Regex("(\\d{1,3}) ?%").find(r)?.let { it.groupValues[1].toInt().coerceIn(1, 100) * 255 / 100 }
            ?: if (down) maxOf(1, cur / 2) else minOf(255, cur * 2 + 16)
        Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
        return "Brightness ${if (target < cur) "lowered" else "raised"}"
    }

    /** No API for it (dark mode, Bluetooth, data, DND...): open the narrowest screen, Laya flips the switch. */
    private fun viaSettings(ctx: SkillContext, r: String): String {
        val actions = when {
            Regex("dark (mode|theme)|night mode").containsMatchIn(r) -> listOf("android.settings.DARK_THEME_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS)
            "bluetooth" in r -> listOf(Settings.ACTION_BLUETOOTH_SETTINGS)
            Regex("wi-?fi").containsMatchIn(r) -> listOf(Settings.ACTION_WIFI_SETTINGS)
            Regex("mobile data|\\bdata\\b|internet").containsMatchIn(r) -> listOf(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            Regex("airplane|flight mode").containsMatchIn(r) -> listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            Regex("location|gps").containsMatchIn(r) -> listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            Regex("do not disturb|\\bdnd\\b").containsMatchIn(r) -> listOf("android.settings.ZEN_MODE_SETTINGS")
            else -> emptyList()
        } + Settings.ACTION_SETTINGS
        actions.firstOrNull { ctx.launch(Intent(it)) } ?: return "I couldn't open Settings"
        ctx.waitUntil(4000) { ctx.foreground() == "com.android.settings" }
        return when (ctx.runLaya(ctx.request, maxSteps = 6)) {
            Outcome.DONE -> "Done"
            Outcome.STOPPED -> "Stopped"
            else -> "I opened the right screen — please flip the switch yourself"
        }
    }
}
