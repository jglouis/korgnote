package com.korgnote.app.di

import android.content.Context
import com.korgnote.app.files.NoteFolderAccess
import com.korgnote.app.files.SafNoteFolder
import com.korgnote.common.replica.FileSystemReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import org.koin.core.module.Module
import java.io.File

/**
 * Binds the Android replica, so the app module does not need to depend on `shared` just to name a
 * storage type.
 *
 * `filesDir` is private to the app. Neither replica is in Android's backups, nor in the move to a
 * new phone: see `data_extraction_rules.xml` in the app. The notes are kept where the user chose,
 * and a new phone syncs them again.
 */
fun Module.androidReplica(context: Context) {
    single<ReplicaStorage> { FileSystemReplicaStorage(File(context.filesDir, "KorgNote")) }
    // The WebDAV sync's, beside it and never mixed with it.
    single<ReplicaStorage>(WEBDAV) { FileSystemReplicaStorage(File(context.filesDir, "KorgNote-webdav")) }
}

/** Binds how a notes folder in shared storage is reached, for the same reason as [androidReplica]. */
fun Module.androidNotesFolders(context: Context) {
    single<NoteFolderAccess> { NoteFolderAccess { SafNoteFolder(context, it) } }
}
