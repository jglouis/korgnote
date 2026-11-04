package com.korgnote.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.korgnote.app.ui.*
import com.korgnote.app.widgets.CalendarTarget

/**
 * A tapped link into the notes arrives here as a `korgnote-link:` URI, from a note, the
 * calendar or search alike, a link to a file outside them as a `korgnote-file:` one, and a
 * tapped planning date as a `korgnote-calendar:` one; anything else is the platform's to
 * open, as before. A link that leads nowhere says why, and leaves the screen as it was.
 */
@Composable
internal fun NotesAppState.rememberNoteLinkHandler(): UriHandler {
    val platformUriHandler = LocalUriHandler.current
    return remember(platformUriHandler, vm) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val day = calendarDayOf(uri)
                when {
                    uri.startsWith(NOTE_LINK_URI_PREFIX) -> {
                        val target = uri.removePrefix(NOTE_LINK_URI_PREFIX)
                        // A `doi:` or `#+LINK:` abbreviation that stands for a web page goes to the
                        // browser, as a web link does; anything else is the notes' to follow.
                        val web = vm.links.webAddressOf(target)
                        if (web != null) {
                            try {
                                platformUriHandler.openUri(web)
                            } catch (e: Exception) {
                                vm.links.onExternalLinkFailed(web)
                            }
                        } else if (vm.links.follow(target)) {
                            selectedScreen = BottomItem.FILE
                        }
                    }
                    uri.startsWith(FILE_LINK_URI_PREFIX) -> vm.files.open(uri.removePrefix(FILE_LINK_URI_PREFIX))
                    day != null -> openCalendar(CalendarTarget(CalendarMode.Day, day))
                    // Android throws when no app takes the link, and the tap would do nothing.
                    else -> try {
                        platformUriHandler.openUri(uri)
                    } catch (e: Exception) {
                        vm.links.onExternalLinkFailed(uri)
                    }
                }
            }
        }
    }
}
