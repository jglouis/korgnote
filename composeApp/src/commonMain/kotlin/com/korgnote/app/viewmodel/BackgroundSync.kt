package com.korgnote.app.viewmodel

/**
 * Syncing with the app closed, where the platform can: WorkManager on Android. Elsewhere nothing,
 * and the next sync with the app open does the work.
 */
interface BackgroundSync {
    /**
     * Syncs once the network is there, the app open or not: an edit could not be sent, and would
     * otherwise wait in the replica until the app is opened again.
     */
    fun whenOnline()
}

/** No sync with the app closed: desktop, web, iOS for now, and the tests. */
object NoBackgroundSync : BackgroundSync {
    override fun whenOnline() = Unit
}
