package com.korgnote.app.di

import android.content.Context
import com.korgnote.common.replica.FileSystemReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import org.koin.core.module.Module
import java.io.File

/**
 * Binds the Android replica, so the app module does not need to depend on `shared` just to name a
 * storage type.
 *
 * `filesDir` is private to the app and included in Android's backup, which is what you want for
 * notes that may hold unsynced edits.
 */
fun Module.androidReplica(context: Context) {
    single<ReplicaStorage> { FileSystemReplicaStorage(File(context.filesDir, "KorgNote")) }
}
