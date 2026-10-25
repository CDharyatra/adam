package com.adam.assistant.executor

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import com.adam.assistant.accessibility.AdamAccessibilityService
import com.adam.assistant.core.AssistantCommand
import com.adam.assistant.core.AssistantCommand.Action
import com.adam.assistant.core.NumberWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommandExecutor(private val context: Context) {

    suspend fun execute(cmd: AssistantCommand): String = withContext(Dispatchers.Main) {
        when (cmd.action) {
            Action.OPEN_APP            -> openApp(cmd.target)
            Action.PLAY_MUSIC          -> playMusic(cmd)
            Action.SEND_MESSAGE        -> sendMessage(cmd)
            Action.MAKE_CALL           -> makeCall(cmd.target, cmd.params["phone"])
            Action.OPEN_URL            -> openUrl(cmd.target)
            Action.SEARCH_WEB          -> searchWeb(cmd.target)
            Action.SET_ALARM           -> setAlarm(cmd)
            Action.SET_TIMER           -> setTimer(cmd.target)
            Action.ADD_CALENDAR_EVENT  -> addCalendarEvent(cmd)
            Action.SEND_EMAIL          -> sendEmail(cmd)
            Action.TAKE_PHOTO          -> takePhoto()
            Action.NAVIGATE            -> navigate(cmd.target)
            Action.OPEN_SETTINGS       -> openSettings(cmd.target)
            Action.ADJUST_VOLUME       -> adjustVolume(cmd.params["direction"] ?: cmd.target)
            Action.READ_SCREEN         -> readScreen()
            Action.LONG_CLICK          -> longClick(cmd.target)
            Action.OPEN_NOTIFICATIONS  -> openNotifications()
            Action.CLOSE_NOTIFICATIONS -> closeNotifications()
            Action.SWIPE               -> swipe(cmd.params["direction"] ?: cmd.target)
            Action.CALCULATE           -> calculate(cmd.target)
            Action.NOTE                -> takeNote(cmd.target)
            Action.CLICK               -> clickElement(cmd.target)
            Action.SCROLL              -> scroll(cmd.params["direction"] ?: "down")
            Action.TYPE                -> typeText(cmd.target)
            Action.BACK                -> back()
            Action.HOME                -> home()
            Action.RECENTS             -> recents()
            Action.REPLY               -> {
                // Novelty hook: brand-new commands get noted for future learning.
                cmd.params["pendingLearn"]?.takeIf { it.isNotBlank() }?.let {
                    com.adam.assistant.core.MemoryStore.logNovel(context, it)
                }
                cmd.params["message"] ?: "Done."
            }
            Action.UNKNOWN             -> "Sorry, I didn't understand that command."
        }
    }

    private fun openApp(appName: String): String {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(resolvePackage(appName))
            ?: pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).firstOrNull { it.loadLabel(pm).toString().equals(appName, ignoreCase = true) }
             ?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }

        return if (intent != null) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opening $appName."
        } else {
            "$appName is not installed."
        }
    }

    private fun makeCall(contact: String, phone: String?): String {
        if (!phone.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return "Opening dialer for $contact."
            }
        }
        val svc = AdamAccessibilityService.instance.value
            ?: return "Please enable Accessibility Service to call by name."
        return if (svc.clickNodeWithText(contact)) "Calling $contact."
        else "Could not find $contact."
    }

    private fun openUrl(text: String): String {
        val url = extractUrl(text)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Opening $url."
        } catch (e: Exception) {
            "Could not open $url."
        }
    }

    private fun searchWeb(query: String): String {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Searching the web for $query."
        } catch (e: Exception) {
            openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
        }
    }

    private fun extractUrl(text: String): String {
        val t = text.trim()
        Regex("""https?://\S+""").find(t)?.value?.let { return it.trimEnd('.', ',', '!', '?') }
        Regex("""\b([a-z0-9-]+\.(com|org|net|io|co|ai|app|dev|me|info|biz|us|uk))\b""", RegexOption.IGNORE_CASE)
            .find(t)?.value?.let { return "https://$it" }
        val host = t.substringAfter("go to ", "").substringAfter("open ", "").trim()
        return if (host.isNotBlank() && !host.contains(' ')) "https://$host"
        else "https://www.google.com/search?q=${Uri.encode(t)}"
    }

    private fun setAlarm(cmd: AssistantCommand): String {
        val text = listOfNotNull(cmd.target.takeIf { it.isNotBlank() }, cmd.params["hour"], cmd.params["minute"]).joinToString(" ")
        val (h, m) = parseTime(text) ?: return "Could not understand the time. Say something like 'set alarm for 7 30 AM'."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_MESSAGE, cmd.target.ifBlank { "Alarm" })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Setting alarm for ${"%02d:%02d".format(h, m)}."
        } catch (e: Exception) {
            "No alarm app available."
        }
    }

    private fun setTimer(text: String): String {
        val seconds = parseDurationSeconds(text) ?: return "Could not understand duration. Say 'set timer for 5 minutes'."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, text.ifBlank { "Timer" })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Setting timer for ${formatDuration(seconds)}."
        } catch (e: Exception) {
            "No clock app available."
        }
    }

    private fun addCalendarEvent(cmd: AssistantCommand): String {
        val title = cmd.target.ifBlank { cmd.params["title"] ?: "New event" }
        val desc  = cmd.params["description"] ?: ""
        val start = cmd.params["start"]?.toLongOrNull() ?: (System.currentTimeMillis() + 60 * 60 * 1000L)
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, desc)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 60 * 60 * 1000L)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Creating event: $title."
        } catch (e: Exception) {
            "No calendar app available."
        }
    }

    private fun sendEmail(cmd: AssistantCommand): String {
        val to      = cmd.target
        val subject = cmd.params["subject"] ?: ""
        val body    = cmd.params["body"] ?: ""
        val uri = Uri.parse("mailto:${Uri.encode(to)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Composing email to $to."
        } catch (e: Exception) {
            "No email app available."
        }
    }

    private fun takePhoto(): String {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Opening camera."
        } catch (e: Exception) {
            "No camera app available."
        }
    }

    private fun navigate(query: String): String {
        if (query.isBlank()) return "Tell me where to navigate."
        val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Navigating to $query."
        } catch (e: Exception) {
            "No maps app available."
        }
    }

    private fun openSettings(target: String): String {
        val action = when (target.lowercase()) {
            "", "settings"        -> Settings.ACTION_SETTINGS
            "wifi"                -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth"           -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location"            -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display"             -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume"     -> Settings.ACTION_SOUND_SETTINGS
            "apps", "applications"-> Settings.ACTION_APPLICATION_SETTINGS
            "battery"             -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "storage"             -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "date", "time"        -> Settings.ACTION_DATE_SETTINGS
            "accessibility"       -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "developer", "dev"    -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "network"             -> Settings.ACTION_NETWORK_OPERATOR_SETTINGS
            "nfc"                 -> Settings.ACTION_NFC_SETTINGS
            "security"            -> Settings.ACTION_SECURITY_SETTINGS
            "language"            -> Settings.ACTION_LOCALE_SETTINGS
            "accounts"            -> Settings.ACTION_SYNC_SETTINGS
            else                  -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Opening ${target.ifBlank { "Settings" }}."
        } catch (e: Exception) {
            "Could not open that settings page."
        }
    }

    private fun adjustVolume(direction: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val flag = AudioManager.FLAG_SHOW_UI
        when (direction.lowercase()) {
            "up", "raise", "louder", "increase" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flag)
            "down", "lower", "quieter", "decrease" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flag)
            "mute"      -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flag)
            "unmute"    -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flag)
            "max", "full" -> {
                am.setStreamVolume(stream, am.getStreamMaxVolume(stream), flag)
                return "Volume at maximum."
            }
            else -> return "Unknown volume direction: $direction."
        }
        return "Volume $direction."
    }

    private fun readScreen(): String {
        val svc = AdamAccessibilityService.instance.value
            ?: return "Please enable Adam's Accessibility Service."
        val text = svc.readScreenText()
        return if (text.isBlank()) "I couldn't read anything on screen."
        else text.take(2000)
    }

    private fun longClick(label: String): String {
        val svc = AdamAccessibilityService.instance.value
            ?: return "Accessibility Service not connected."
        return if (svc.longClickNodeWithText(label)) "Long-pressed $label."
        else "Could not find '$label' on screen."
    }

    private fun openNotifications(): String {
        AdamAccessibilityService.instance.value?.openNotifications()
            ?: return "Accessibility Service not connected."
        return "Opening notifications."
    }

    private fun closeNotifications(): String {
        AdamAccessibilityService.instance.value?.closeNotifications()
            ?: return "Accessibility Service not connected."
        return "Closing notifications."
    }

    private fun swipe(direction: String): String {
        val svc = AdamAccessibilityService.instance.value
            ?: return "Accessibility Service not connected."
        return if (svc.swipe(direction)) "Swiped $direction."
        else "Could not swipe."
    }

    private suspend fun playMusic(cmd: AssistantCommand): String {
        val song   = cmd.target
        val artist = cmd.params["artist"] ?: ""
        val app    = cmd.params["app"] ?: "Spotify"

        val query = if (artist.isNotBlank()) "$song $artist" else song

        // Fast path: jump straight into Spotify's search results. Far more
        // robust than tapping through UI labels that change every release.
        if (app.equals("Spotify", ignoreCase = true) && query.isNotBlank()) {
            try {
                val uri = Uri.parse("spotify:search:${Uri.encode(query)}")
                val search = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (search.resolveActivity(context.packageManager) != null) {
                    context.startActivity(search)
                    return "Searching $query on Spotify."
                }
            } catch (_: Exception) {
                // Fall through to UI automation below.
            }
        }

        val openResult = openApp(app)
        if (openResult.contains("not installed")) return openResult

        val svc = AdamAccessibilityService.instance.value
            ?: return "Please enable Adam's Accessibility Service in Settings."

        waitForNode(svc, "Search") ?: return "Could not load $app."
        svc.clickNodeWithText("Search")

        waitForNode(svc, "Artists, songs, or podcasts")
            ?: waitForNode(svc, "Search songs")
            ?: return "Could not find search field in $app."
        svc.findEditableInput() ?: return "Could not find search field in $app."

        var typed = svc.typeText(query)
        if (!typed) {
            val input = svc.findEditableInput()
            typed = svc.typeInto(input, query)
        }
        if (!typed) return "Could not type into search field in $app."

        waitForNode(svc, "Songs") ?: waitForNode(svc, "SONGS")
            ?: return "No search results in $app."
        svc.clickNodeWithText("Songs").also { if (!it) svc.clickNodeWithText("SONGS") }

        waitForNode(svc, song) ?: return "Could not find \"$song\" in $app."
        svc.clickNodeWithText(song)

        return "Playing $song${if (artist.isNotBlank()) " by $artist" else ""} on $app."
    }

    private suspend fun sendMessage(cmd: AssistantCommand): String {
        val contact = cmd.target
        val body    = cmd.params["body"] ?: return "No message body provided."
        val app     = cmd.params["app"] ?: "WhatsApp"

        val phone = cmd.params["phone"]
        if (app.equals("WhatsApp", ignoreCase = true) && !phone.isNullOrBlank()) {
            val uri = Uri.parse("https://wa.me/$phone?text=${Uri.encode(body)}")
            val deepLink = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (deepLink.resolveActivity(context.packageManager) != null) {
                context.startActivity(deepLink)
                return "Opening WhatsApp chat with $contact."
            }
        }

        val openResult = openApp(app)
        if (openResult.contains("not installed")) return openResult

        val svc = AdamAccessibilityService.instance.value
            ?: return "Please enable Adam's Accessibility Service in Settings."

        waitForNode(svc, "Search") ?: waitForNode(svc, "New chat")
            ?: return "Could not load $app."
        svc.clickNodeWithText("Search").also { if (!it) svc.clickNodeWithText("New chat") }

        waitForNode(svc, contact) ?: run { svc.typeText(contact); waitForNode(svc, contact) }
        svc.clickNodeWithText(contact)

        waitForNode(svc, "Type a message") ?: waitForNode(svc, "Message")
            ?: return "Could not open chat with $contact."
        svc.clickNodeWithText("Type a message").also { if (!it) svc.clickNodeWithText("Message") }
        svc.typeText(body)

        waitForNode(svc, "Send") ?: return "Could not find Send button."
        svc.clickNodeWithText("Send")

        return "Message sent to $contact on $app."
    }

    private fun calculate(expression: String): String =
        // Pure logic lives in ArithmeticEvaluator (unit-tested); this stays
        // a thin delegate so device execution never duplicates parsing.
        ArithmeticEvaluator.evaluate(expression)

    private fun takeNote(text: String): String {
        if (text.isBlank()) return "Tell me what to note."
        return try {
            val dir = File(context.getExternalFilesDir(null), "notes").apply { mkdirs() }
            val file = File(dir, "notes.txt")
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("[$stamp] $text\n")
            "Note saved."
        } catch (e: Exception) {
            "Could not save note."
        }
    }

    private fun clickElement(label: String): String {
        val svc = AdamAccessibilityService.instance.value
            ?: return "Accessibility Service not connected."
        return if (svc.clickNodeWithText(label)) "Clicked $label."
        else "Could not find '$label' on screen."
    }

    private fun scroll(direction: String): String {
        val svc = AdamAccessibilityService.instance.value
            ?: return "Accessibility Service not connected."
        return if (svc.scroll(direction)) "Scrolled $direction."
        else "Nothing to scroll."
    }

    private fun typeText(text: String): String {
        val svc = AdamAccessibilityService.instance.value
            ?: return "Accessibility Service not connected."
        return if (svc.typeText(text)) "Typed: $text"
        else "No text field found."
    }

    private fun back(): String {
        AdamAccessibilityService.instance.value?.performBack()
        return "Going back."
    }

    private fun home(): String {
        AdamAccessibilityService.instance.value?.performHome()
        return "Going home."
    }

    private fun recents(): String {
        AdamAccessibilityService.instance.value?.performRecents()
        return "Opening recent apps."
    }

    private suspend fun waitForNode(
        svc: AdamAccessibilityService,
        label: String,
        timeoutMs: Long = 5_000,
        intervalMs: Long = 200
    ): String? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            if (svc.findNodeByText(svc.rootInActiveWindow, label) != null) return@withTimeoutOrNull label
            delay(intervalMs)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun parseTime(text: String): Pair<Int, Int>? {
        val t = text.lowercase()
        val match = Regex("""(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?""")
            .find(t) ?: return null
        var h = match.groupValues[1].toIntOrNull() ?: return null
        val m = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].replace(".", "")
        when (ampm) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
        }
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    private fun parseDurationSeconds(text: String): Int? {
        val t = text.lowercase()
        var total = 0; var found = false
        Regex("""(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)""")
            .findAll(t).forEach { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@forEach
                val u = m.groupValues[2]
                total += when {
                    u.startsWith("hour") || u.startsWith("hr") -> n * 3600
                    u.startsWith("min")                       -> n * 60
                    else                                      -> n
                }
                found = true
            }
        if (!found) {
            Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let {
                total = it * 60; found = true
            }
        }
        return if (found) total else null
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    private fun resolvePackage(appName: String): String {
        val static = mapOf(
            "spotify"       to "com.spotify.music",
            "whatsapp"      to "com.whatsapp",
            "youtube"       to "com.google.android.youtube",
            "chrome"        to "com.android.chrome",
            "maps"          to "com.google.android.apps.maps",
            "google maps"   to "com.google.android.apps.maps",
            "gmail"         to "com.google.android.gms",
            "camera"        to "com.android.camera2",
            "settings"      to "com.android.settings",
            "calculator"    to "com.android.calculator2",
            "clock"         to "com.android.deskclock",
            "contacts"      to "com.android.contacts",
            "phone"         to "com.android.dialer",
            "dialer"        to "com.android.dialer",
            "messages"      to "com.google.android.apps.messaging",
            "sms"           to "com.google.android.apps.messaging",
            "photos"        to "com.google.android.apps.photos",
            "drive"         to "com.google.android.apps.docs",
            "instagram"     to "com.instagram.android",
            "twitter"       to "com.twitter.android",
            "x"             to "com.twitter.android",
            "telegram"      to "org.telegram.messenger",
            "netflix"       to "com.netflix.mediaclient"
        )
        static[appName.lowercase()]?.let { return it }

        val pm = context.packageManager
        return pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).firstOrNull { it.loadLabel(pm).toString().equals(appName, ignoreCase = true) }
            ?.activityInfo?.packageName ?: appName
    }
}
