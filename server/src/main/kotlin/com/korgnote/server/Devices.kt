package com.korgnote.server

import com.korgnote.common.pairing.PairRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** A device paired with this server. Only a hash of its token is kept: the token stays on the device. */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val tokenHash: String,
    /** Epoch milliseconds. */
    val pairedAt: Long,
    val lastSeen: Long? = null,
)

/**
 * The devices that may use the sync API over the network, kept in `devices.json` in the server's
 * state directory; and the one-time codes a pairing QR code offers, kept in memory only, since a
 * restart is no reason to honour a code shown before it.
 *
 * Revoking a device removes it, and its token stops working on its next request.
 */
class DeviceRegistry(
    private val file: File?,
    private val now: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = { randomHex(32) },
    private val newCode: () -> String = { randomHex(16) },
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val devices = mutableListOf<PairedDevice>()
    private val codes = mutableMapOf<String, Long>()

    init {
        file?.takeIf { it.isFile }?.let { devices += json.decodeFromString<List<PairedDevice>>(it.readText()) }
    }

    @Synchronized
    fun all(): List<PairedDevice> = devices.toList()

    /** A code for one pairing, good for [CODE_LIFETIME_MS]; every newer one leaves it valid too. */
    @Synchronized
    fun newPairingCode(): String {
        val code = newCode()
        codes.values.removeAll { it < now() }
        codes[code] = now() + CODE_LIFETIME_MS
        return code
    }

    /**
     * Pairs the device [request] describes when its code is one this registry handed out and has
     * not been used or expired, and returns it with the token to give it; null otherwise.
     */
    @Synchronized
    fun pair(request: PairRequest): Pair<PairedDevice, String>? {
        val expires = codes.entries.firstOrNull { constantTimeEquals(it.key, request.code) }?.also { codes.remove(it.key) }?.value
        if (expires == null || expires < now()) return null
        val replaced = request.replaces?.let { id -> devices.firstOrNull { it.id == id } }
        val replacedToken = request.replacesToken
        if (replaced != null && replacedToken != null && constantTimeEquals(replaced.tokenHash, hash(replacedToken))) {
            devices.remove(replaced)
        }
        val token = newToken()
        val name = request.deviceName.trim().take(80).ifEmpty { "A device" }
        val device = PairedDevice(UUID.randomUUID().toString(), name, hash(token), pairedAt = now())
        devices += device
        save()
        return device to token
    }

    /** The device [token] belongs to, its last-seen time brought up to date; null for any other token. */
    @Synchronized
    fun authenticate(token: String): PairedDevice? {
        val hashed = hash(token)
        val index = devices.indexOfFirst { constantTimeEquals(it.tokenHash, hashed) }
        if (index < 0) return null
        val device = devices[index]
        val seen = now()
        // Written now and then, not on every request: a phone syncing all day is not worth a disk write each time.
        if (device.lastSeen == null || seen - device.lastSeen >= LAST_SEEN_RESOLUTION_MS) {
            devices[index] = device.copy(lastSeen = seen)
            save()
        }
        return devices[index]
    }

    /** Whether there was such a device to revoke. */
    @Synchronized
    fun revoke(id: String): Boolean {
        val removed = devices.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    private fun save() {
        val target = file ?: return
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(json.encodeToString(devices.toList()))
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val CODE_LIFETIME_MS = 10 * 60 * 1000L
        const val LAST_SEEN_RESOLUTION_MS = 60 * 1000L

        private fun hash(token: String) = sha256Hex(token.encodeToByteArray())

        private fun constantTimeEquals(a: String, b: String) =
            MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
    }
}
