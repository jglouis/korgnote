package com.korgnote.app.capture

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.korgnote.app.MainActivity
import com.korgnote.androidapp.R
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.chosenNotesSource
import com.korgnote.app.ui.CaptureSheet
import com.korgnote.app.ui.KorgNoteTheme
import com.korgnote.common.org.capture.SharedContent
import com.korgnote.common.org.capture.captureLinkOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

/**
 * Capturing from another app: what it shared, the text selected in it, a calendar file it opened, a
 * `korgnote://capture` link, or nothing — the launcher's shortcut. A sheet over that app, which
 * stays where it was: the capture is queued and [CaptureWorker] files it, so the sheet closes at once.
 */
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val koin = KoinPlatform.getKoin()
        val source = koin.get<SettingsRepository>().chosenNotesSource()
        if (source == null) {
            // Nowhere to put it yet: the welcome screens say where the notes are.
            Toast.makeText(this, R.string.capture_set_up_first, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val store = koin.notesStore(source)
        val filer = koin.get<CaptureFiler>()
        val queue = koin.get<CaptureQueue>()
        lifecycleScope.launch {
            val link = intent.dataString?.let(::captureLinkOf)
            val shared = link?.shared ?: withContext(Dispatchers.IO) { sharedContentOf(intent) }
            setContent {
                KorgNoteTheme {
                    CaptureSheet(
                        shared = shared,
                        store = store,
                        filer = filer,
                        calendar = koin.getOrNull<DeviceCalendar>(),
                        startWithCalendar = intent.action == CaptureShortcut.ACTION_EVENT,
                        onDismiss = ::finish,
                        onSave = { request ->
                            lifecycleScope.launch {
                                queue.add(request)
                                // The app, if it is open, files it at once; the worker otherwise.
                                CaptureSignals.queued.tryEmit(Unit)
                                CaptureWorker.start(applicationContext)
                                val target = (request.into ?: request.under)?.title ?: (request.file ?: filer.inbox()).substringAfterLast('/')
                                Toast.makeText(applicationContext, getString(R.string.capture_saved, target), Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }

    /** What [intent] carries to capture: shared text and its subject, selected text, or a calendar file. */
    private fun sharedContentOf(intent: Intent): SharedContent {
        val type = intent.type.orEmpty().lowercase()
        val calendar = type in CALENDAR_TYPES || intent.data?.path?.endsWith(".ics", ignoreCase = true) == true
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> SharedContent(text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            Intent.ACTION_SEND -> {
                val stream = streamOf(intent)
                SharedContent(
                    // Google Maps names the place only in the share sheet's title, beside a short link.
                    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                        ?: intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString(),
                    text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        ?: stream?.takeIf { !calendar }?.let(::readText),
                    calendar = stream?.takeIf { calendar }?.let(::readText),
                )
            }
            Intent.ACTION_VIEW -> SharedContent(calendar = intent.data?.takeIf { calendar }?.let(::readText))
            else -> SharedContent()
        }
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    /** The text at [uri], or null when it cannot be read or is too long to be what anyone captures. */
    private fun readText(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_SHARED_BYTES + 1)
            if (bytes.size > MAX_SHARED_BYTES) null else bytes.decodeToString()
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        val CALENDAR_TYPES = setOf("text/calendar", "text/x-vcalendar", "application/ics")

        /** A calendar file of one invitation is a few kilobytes; a whole exported calendar is not a capture. */
        const val MAX_SHARED_BYTES = 1 shl 20
    }
}
