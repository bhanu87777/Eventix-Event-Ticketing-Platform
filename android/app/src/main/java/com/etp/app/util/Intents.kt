package com.etp.app.util

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import com.etp.app.data.Event
import java.io.File
import java.time.Instant

/** Pre-filled "add to calendar" insert — no calendar permissions needed. */
fun addToCalendar(context: Context, event: Event) {
    val startMillis = runCatching { Instant.parse(event.startsAt).toEpochMilli() }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 2 * 60 * 60 * 1000)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, event.venue)
        .putExtra(CalendarContract.Events.DESCRIPTION, event.description)
    runCatching { context.startActivity(intent) }
}

fun shareEvent(context: Context, event: Event) {
    val text = "${event.title}\n${event.venue}\n${formatEventDateTime(event.startsAt)}"
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, "Share event"))
}

/** Writes CSV text into cacheDir/share and opens the system share sheet. */
fun shareCsv(context: Context, fileName: String, csv: String) {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, fileName).apply { writeText(csv) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Export attendees"))
}
