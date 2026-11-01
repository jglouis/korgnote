package com.korgnote.common.org

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

/**
 * A heading's priority, the `A` of `[#A]` or the `10` of `[#10]`, as Org holds it
 * (`org-priority-to-value`): a letter `A`–`Z` is its character code, 65–90, and a number is itself,
 * 0–64, so the two never meet. A smaller value is a higher priority, `A` before `B` and `1` before `2`.
 */
@Serializable(with = OrgPrioritySerializer::class)
@JvmInline
value class OrgPriority(val value: Int) : Comparable<OrgPriority> {
    /** As the cookie writes it: `A`, or `10`. */
    val text: String get() = if (value in LETTERS) value.toChar().toString() else value.toString()

    override fun compareTo(other: OrgPriority): Int = value.compareTo(other.value)

    override fun toString(): String = text

    companion object {
        private val LETTERS = 'A'.code..'Z'.code
        private val NUMBERS = 0..64

        /**
         * The priority [text] names, as the spec allows them: a single uppercase Latin letter, or a
         * non-negative integer up to 64. Null for anything else, a lower-case letter included.
         */
        fun of(text: String): OrgPriority? = when {
            text.length == 1 && text[0] in 'A'..'Z' -> OrgPriority(text[0].code)
            text.isNotEmpty() && text.length <= 2 && text.all { it in '0'..'9' } -> text.toInt().takeIf { it in NUMBERS }?.let(::OrgPriority)
            else -> null
        }
    }
}

/** Written as the cookie writes it, `"A"` or `"10"`, which is also how a one-character priority was stored before. */
internal object OrgPrioritySerializer : KSerializer<OrgPriority> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.korgnote.OrgPriority", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OrgPriority) = encoder.encodeString(value.text)
    override fun deserialize(decoder: Decoder): OrgPriority =
        decoder.decodeString().let { OrgPriority.of(it) ?: OrgPriority(it.firstOrNull()?.code ?: 'B'.code) }
}

/**
 * The priorities a file's headings can have, and the one a heading without a cookie counts as:
 * Org's `org-priority-highest`, `-lowest` and `-default` — `A`, `C` and `B` unless the file's
 * `#+PRIORITIES:` line says otherwise.
 */
data class OrgPriorities(val highest: OrgPriority, val lowest: OrgPriority, val default: OrgPriority) {
    /** Every priority, highest first: `A`, `B`, `C`; or `1` to `10`. */
    val range: List<OrgPriority> get() = (highest.value..lowest.value).map(::OrgPriority)

    /**
     * Where [priority] stands against the default, for the agenda's sorting: negative for higher,
     * zero for the default and for none — "an entry without a cookie is treated as if it had" the
     * default — positive for lower. Measured from the default, it compares the headings of files
     * with different ranges sensibly.
     */
    fun orderOf(priority: OrgPriority?): Int = (priority ?: default).value - default.value
}

/** Org's own: three priorities, `A` the highest, and `B` for a heading without a cookie. */
val DEFAULT_PRIORITIES = OrgPriorities(OrgPriority('A'.code), OrgPriority('C'.code), OrgPriority('B'.code))

/**
 * The priorities [text] sets with `#+PRIORITIES: highest lowest default`, such as `A C B` or
 * `1 10 5`, or Org's own when it sets none — or none it can: the three must be letters, or numbers
 * from 0 to 64, all alike; the highest must come before the lowest, earlier in the alphabet or a
 * smaller number; and the default must lie between the two.
 */
fun prioritiesOf(text: String): OrgPriorities {
    val value = PRIORITIES_KEYWORD.find(text)?.groupValues?.get(1) ?: return DEFAULT_PRIORITIES
    val words = value.trim().split(Regex("\\s+"))
    if (words.size != 3) return DEFAULT_PRIORITIES
    val (highest, lowest, default) = words.map { OrgPriority.of(it) ?: return DEFAULT_PRIORITIES }
    val letters = listOf(highest, lowest, default).map { it.value >= 'A'.code }
    if (letters.distinct().size != 1 || highest > lowest || default !in highest..lowest) return DEFAULT_PRIORITIES
    return OrgPriorities(highest, lowest, default)
}

private val PRIORITIES_KEYWORD = Regex("(?im)^[ \\t]*#\\+PRIORITIES:[ \\t]*(.*)$")
