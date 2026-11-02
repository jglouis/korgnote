package com.korgnote.app.capture

import com.korgnote.common.org.capture.SharedContent
import com.korgnote.common.org.capture.isGoogleMapsPage
import com.korgnote.common.org.capture.mapShortLinkIn
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Looks up where a Google Maps short link leads, for the place's address: Google Maps shares only
 * the place's name and `https://maps.app.goo.gl/…`, and the page that link redirects to is
 * `…/maps/place/Eiffel+Tower,+Av.+Gustave+Eiffel,+75007+Paris,+France/…`.
 *
 * One request, to the short link only, and its redirect is read rather than followed: Google learns
 * no more than that the link it made a moment ago for this share was opened. Nothing else that is
 * shared goes anywhere.
 *
 * [client] must not follow redirects.
 */
class MapLinks(private val client: HttpClient, private val timeoutMillis: Long = TIMEOUT_MILLIS) {

    /** [shared] with where its map's short link leads, when it has one and that could be found in time; as it was otherwise. */
    suspend fun expanded(shared: SharedContent): SharedContent {
        val link = mapShortLinkIn(shared) ?: return shared
        val target = try {
            withTimeoutOrNull(timeoutMillis) { client.get(link).headers[HttpHeaders.Location] }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline, or Google not answering: the place is its name and link, as without this.
            null
        }
        return target?.takeIf(::isGoogleMapsPage)?.let { shared.copy(expanded = it) } ?: shared
    }

    private companion object {
        /** The capture sheet waits for this before it shows; a place without its address is better than a wait. */
        const val TIMEOUT_MILLIS = 3_000L
    }
}
