package com.korgnote.app.di

import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.KtorSyncApi
import com.korgnote.app.data.SettingsRepository
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import com.korgnote.common.replica.SyncApi
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The WebDAV sync sits beside the server's, in the app's real wiring: its own replica and engine,
 * and the server's left as they were, so moving between the two never mixes their notes.
 */
class WebDavWiringTest {

    @BeforeTest
    fun setUp() {
        startKoin {
            modules(
                appModule,
                module {
                    single<SettingsRepository> { InMemorySettingsRepository() }
                    single<ReplicaStorage> { InMemoryReplicaStorage() }
                    single<ReplicaStorage>(WEBDAV) { InMemoryReplicaStorage() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun theWebDavNotesAreKeptApartFromTheServers() = runTest {
        val koin = KoinPlatform.getKoin()
        val server = koin.get<DocumentStore>()
        val webDav = koin.get<DocumentStore>(WEBDAV)
        webDav.create("from-webdav.org", "* WebDAV\n")
        server.create("from-server.org", "* Server\n")

        assertEquals(listOf("from-webdav.org"), webDav.paths())
        assertEquals(listOf("from-server.org"), server.paths())
        // Each on the storage its platform gave it: the server's notes where they always were.
        assertEquals(listOf("from-server.org"), koin.get<ReplicaStorage>().list())
        assertEquals(listOf("from-webdav.org"), koin.get<ReplicaStorage>(WEBDAV).list())
        assertNotSame(koin.get<SyncEngine>(), koin.get<SyncEngine>(WEBDAV))
    }

    @Test
    fun theServerKeepsItsOwnClient() {
        assertTrue(KoinPlatform.getKoin().get<SyncApi>() is KtorSyncApi)
    }
}
