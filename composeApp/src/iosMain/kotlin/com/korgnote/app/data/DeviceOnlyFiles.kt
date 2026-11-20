package com.korgnote.app.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Where a secret goes when the Keychain will not keep it: a file in the app's container, in the
 * clear, but in a folder kept out of iCloud and computer backups, as the user defaults are not. A
 * pairing kept so is better than a pairing lost, and it stays on this iPhone.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object DeviceOnlyFiles {
    private val folder: String by lazy {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        "$support/device-only".also { path ->
            NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
            if (!NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)) {
                println("[DEBUG_LOG][Settings] Could not keep $path out of backups")
            }
        }
    }

    private fun fileOf(key: String) = "$folder/$key"

    fun read(key: String): String? = NSString.stringWithContentsOfFile(fileOf(key), encoding = NSUTF8StringEncoding, error = null)

    fun write(key: String, value: String) {
        NSString.create(string = value).writeToFile(fileOf(key), atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    fun remove(key: String) {
        NSFileManager.defaultManager.removeItemAtPath(fileOf(key), error = null)
    }
}
