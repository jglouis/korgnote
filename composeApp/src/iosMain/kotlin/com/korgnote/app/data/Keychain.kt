package com.korgnote.app.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Secrets in the Keychain — the paired server's token, the WebDAV password — rather than in the
 * user defaults, which are a plain file in every backup.
 *
 * Readable once the device has been unlocked after a start, so a sync in the background still
 * can; and only on this device, so neither a backup nor a move to a new iPhone takes them along.
 * There the app asks for them again, by pairing again or for the password. Not yet tried on an
 * iPhone.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object Keychain {
    private const val SERVICE = "com.korgnote.settings"

    fun read(account: String): String? = memScoped {
        val query = query(account) {
            CFDictionaryAddValue(it, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(it, kSecMatchLimit, kSecMatchLimitOne)
        }
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data, NSUTF8StringEncoding)?.toString()
    }

    /** Keeps [value] for [account], or forgets it when null. False when the Keychain refused. */
    fun write(account: String, value: String?): Boolean {
        val match = query(account) {}
        SecItemDelete(match)
        CFRelease(match)
        if (value == null) return true
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        val dataRef = CFBridgingRetain(data)
        val item = query(account) {
            CFDictionaryAddValue(it, kSecValueData, dataRef)
            CFDictionaryAddValue(it, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        }
        val status = SecItemAdd(item, null)
        CFRelease(item)
        CFRelease(dataRef)
        return status == errSecSuccess
    }

    /** The item for [account] under this app's service, with whatever [more] adds; released by the caller. */
    private fun query(account: String, more: (CFMutableDictionaryRef?) -> Unit): CFMutableDictionaryRef? {
        val dictionary = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val service = CFBridgingRetain(SERVICE)
        val accountRef = CFBridgingRetain(account)
        CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dictionary, kSecAttrService, service)
        CFDictionaryAddValue(dictionary, kSecAttrAccount, accountRef)
        // The dictionary holds its own references now.
        CFRelease(service)
        CFRelease(accountRef)
        more(dictionary)
        return dictionary
    }
}
