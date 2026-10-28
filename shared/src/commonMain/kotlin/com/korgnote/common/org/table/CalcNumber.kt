package com.korgnote.common.org.table

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * A number as Emacs Calc holds one while it computes a table formula.
 *
 * Whatever KorgNote writes into a computed cell has to be what Emacs would have written: the same
 * notes are recalculated on both sides, and any difference would come back on every sync. So the
 * arithmetic follows Calc's, not Kotlin's. Calc keeps integers exact — `6/2` is `3` — and every other
 * number as a decimal float of twelve significant digits, the precision Org asks of it. A float stays
 * a float even when it is whole, which is why Emacs writes `2420.` for `2000 * 1.21`.
 */
sealed class CalcNumber : Comparable<CalcNumber> {

    data class Whole(val value: Long) : CalcNumber()

    /** [mantissa] × 10^[exponent], with no trailing zero in [mantissa] and twelve digits at most. */
    data class Decimal(val mantissa: Long, val exponent: Int) : CalcNumber()

    val isZero: Boolean get() = signum() == 0

    fun signum(): Int = when (this) {
        is Whole -> value.compareTo(0L)
        is Decimal -> mantissa.compareTo(0L)
    }

    fun toDouble(): Double = when (this) {
        is Whole -> value.toDouble()
        // One rounding only: the mantissa and a power of ten up to 10^22 are exact doubles.
        is Decimal -> if (exponent >= 0) mantissa * 10.0.pow(exponent) else mantissa / 10.0.pow(-exponent)
    }

    operator fun plus(other: CalcNumber): CalcNumber =
        exactly(other, ::addExact) ?: fromDouble(toDouble() + other.toDouble())

    operator fun minus(other: CalcNumber): CalcNumber = this + -other

    operator fun times(other: CalcNumber): CalcNumber =
        exactly(other, ::multiplyExact) ?: fromDouble(toDouble() * other.toDouble())

    operator fun unaryMinus(): CalcNumber = when (this) {
        is Whole -> if (value == Long.MIN_VALUE) fromDouble(-value.toDouble()) else Whole(-value)
        is Decimal -> Decimal(-mantissa, exponent)
    }

    /** Null when [other] is zero: Calc leaves `1/0` as it is, which no cell should receive. */
    operator fun div(other: CalcNumber): CalcNumber? {
        if (other.isZero) return null
        if (this is Whole && other is Whole && value % other.value == 0L) return Whole(value / other.value)
        return fromDouble(toDouble() / other.toDouble())
    }

    /** Calc's `%`: what is left of a division rounded down, so it takes the sign of [other]. */
    fun mod(other: CalcNumber): CalcNumber? {
        if (other.isZero) return null
        if (this is Whole && other is Whole) {
            val r = value % other.value
            return Whole(if (r != 0L && (r xor other.value) < 0) r + other.value else r)
        }
        val a = toDouble()
        val b = other.toDouble()
        return fromDouble(a - b * floor(a / b))
    }

    /** Calc's `\`: a division rounded down to a whole number. */
    fun intDiv(other: CalcNumber): CalcNumber? = div(other)?.floor()

    /** Null where Calc would answer with something that is not a real number, as `(-8)^0.5`. */
    fun pow(other: CalcNumber): CalcNumber? {
        if (this is Whole && other is Whole && other.value >= 0) powExact(value, other.value)?.let { return Whole(it) }
        if (isZero && other.signum() < 0) return null
        val power = toDouble().pow(other.toDouble())
        return if (power.isNaN() || power.isInfinite()) null else fromDouble(power)
    }

    fun abs(): CalcNumber = if (signum() < 0) -this else this

    fun floor(): CalcNumber = rounded(0) { cut, negative -> negative && cut.any { it != '0' } }

    fun ceil(): CalcNumber = rounded(0) { cut, negative -> !negative && cut.any { it != '0' } }

    fun truncate(): CalcNumber = rounded(0) { _, _ -> false }

    /**
     * Calc's `round`: halves away from zero, to [places] decimals. A whole number comes back when no
     * decimal is kept — `round(2.5)` is `3`, `round(1234.5, -2)` is `1200` — and a float otherwise.
     */
    fun round(places: Int = 0): CalcNumber = rounded(places) { cut, _ -> cut.first() >= '5' }

    /**
     * This number cut to [places] decimals, its magnitude one more in the last place kept when [up]
     * says so, given the digits cut off and whether the number is negative.
     */
    private fun rounded(places: Int, up: (cut: String, negative: Boolean) -> Boolean): CalcNumber {
        val (mantissa, exponent) = when (this) {
            is Whole -> if (places >= 0) return this else value to 0
            is Decimal -> mantissa to exponent
        }
        val negative = mantissa < 0
        val cut = -places - exponent // how many of the mantissa's digits fall below the last place kept
        if (cut <= 0) return if (places > 0) this else Whole(mantissa * pow10(exponent))
        val digits = mantissa.absDigits().padStart(cut + 1, '0')
        val kept = digits.dropLast(cut).toLong() + if (up(digits.takeLast(cut), negative)) 1 else 0
        val signed = if (negative) -kept else kept
        return if (places <= 0) Whole(signed * pow10(-places)) else decimalOf(signed.absDigits(), -places, negative)
    }

    override fun compareTo(other: CalcNumber): Int =
        if (this is Whole && other is Whole) value.compareTo(other.value)
        else toDouble().compareTo(other.toDouble())

    private inline fun exactly(other: CalcNumber, op: (Long, Long) -> Long?): CalcNumber? =
        if (this is Whole && other is Whole) op(value, other.value)?.let { Whole(it) } else null

    /**
     * The number as Calc displays it in a table, at [significant] digits (`calc-float-format`, 8 in
     * Org): `17`, `2420.`, `7.3846154`, `1.234e-3`. A float of more digits than that is rounded, half
     * away from zero, and keeps every digit the rounding gave — `1.2000000`, not `1.2` — as Calc does.
     */
    fun toCalcString(significant: Int = DISPLAY_DIGITS): String {
        val decimal = when (this) {
            is Whole -> return value.toString()
            is Decimal -> this
        }
        if (decimal.mantissa == 0L) return "0."
        var digits = decimal.mantissa.absDigits()
        var exponent = decimal.exponent
        if (digits.length > significant) {
            exponent += digits.length - significant
            digits = roundDigits(digits, significant, halfEven = false)
        }
        val sign = if (decimal.mantissa < 0) "-" else ""
        val integerDigits = digits.length + exponent
        if (integerDigits > INTERNAL_DIGITS || integerDigits < -1) {
            val fraction = if (digits.length > 1) "." + digits.substring(1) else ""
            return "$sign${digits[0]}${fraction}e${integerDigits - 1}"
        }
        return sign + when {
            exponent >= 0 -> digits + "0".repeat(exponent) + "."
            integerDigits > 0 -> digits.substring(0, integerDigits) + "." + digits.substring(integerDigits)
            else -> "0." + "0".repeat(-integerDigits) + digits
        }
    }

    override fun toString(): String = toCalcString()

    companion object {
        /** `calc-internal-prec` as Org sets it. */
        const val INTERNAL_DIGITS = 12

        /** `calc-float-format` as Org sets it, `(float 8)`. */
        const val DISPLAY_DIGITS = 8

        private val literal = Regex("""([-+]?)(\d*)(?:\.(\d*))?(?:[eE]([-+]?\d+))?(%?)""")
        private val leading = Regex("""^\s*[-+]?(\d+(\.\d*)?|\.\d+)([eE][-+]?\d+)?""")

        /**
         * The number a cell holds, read as Calc reads it: `2000` is whole, while `167.70`, `2420.`
         * and `1e3` are floats, and `12%` is `0.12`. Null for anything else — text, a date, a link —
         * which Calc would take for a variable, and Emacs would leave in the result as a name.
         */
        fun parse(text: String): CalcNumber? {
            val match = literal.matchEntire(text.trim()) ?: return null
            val (sign, whole, fraction, exponentText, percent) = match.destructured
            if (whole.isEmpty() && fraction.isEmpty()) return null
            val negative = sign == "-"
            val isFloat = match.groups[3] != null || match.groups[4] != null || percent.isNotEmpty()
            if (!isFloat) whole.toLongOrNull()?.let { return Whole(if (negative) -it else it) }
            val exponent = (exponentText.toIntOrNull() ?: 0) - fraction.length - if (percent.isNotEmpty()) 2 else 0
            return decimalOf(whole + fraction, exponent, negative)
        }

        /**
         * The number Emacs's `string-to-number` reads from [text], as a formula in `N` mode reads
         * every cell: the number the text starts with, or zero. Only a fraction or an exponent makes
         * a float of it; `2420.` is the integer 2420.
         */
        fun leadingNumber(text: String): CalcNumber {
            val number = leading.find(text)?.value?.trim() ?: return Whole(0)
            val float = number.contains('e', ignoreCase = true) || Regex("""\.\d""").containsMatchIn(number)
            return parse(if (float) number else number.removeSuffix(".")) ?: Whole(0)
        }

        /**
         * The float nearest [x], at Calc's twelve digits. The double is first read back to fifteen
         * digits — exact for any sum or product of the short decimals tables hold — so that only the
         * second rounding, Calc's own, decides a half.
         */
        fun fromDouble(x: Double): CalcNumber {
            require(!x.isNaN() && !x.isInfinite()) { "not a number: $x" }
            if (x == 0.0) return Decimal(0, 0)
            val (digits, scale) = exactDecimal(abs(x))
            val shift = (digits.length - 15).coerceAtLeast(0)
            return decimalOf(roundDigits(digits, 15, halfEven = true), scale + shift, x < 0)
        }

        /** [digits] × 10^[exponent], rounded half away from zero to twelve digits. */
        internal fun decimalOf(digits: String, exponent: Int, negative: Boolean): Decimal {
            var kept = digits.trimStart('0')
            if (kept.isEmpty()) return Decimal(0, 0)
            var shift = 0
            if (kept.length > INTERNAL_DIGITS) {
                shift = kept.length - INTERNAL_DIGITS
                kept = roundDigits(kept, INTERNAL_DIGITS, halfEven = false)
            }
            val trimmed = kept.trimEnd('0')
            shift += kept.length - trimmed.length
            val mantissa = trimmed.toLong()
            return Decimal(if (negative) -mantissa else mantissa, exponent + shift)
        }
    }
}

/**
 * [value] written by a C `printf` directive, as Emacs's `format` writes a formula's result when the
 * formula ends in `;%.2f` and the like. [value] is Calc's own text for the result, which Emacs reads
 * back with `string-to-number` first. Null when [format] is not one `%f` or `%d` directive — with a
 * width, a precision and the `-` and `0` flags — amid plain text.
 */
fun formatLikeEmacs(format: String, value: String): String? {
    var start = -1
    var i = 0
    while (i < format.length) {
        if (format[i] == '%') {
            if (format.getOrNull(i + 1) == '%') { i += 2; continue }
            if (start >= 0) return null
            start = i
        }
        i++
    }
    if (start < 0) return null
    val match = directive.find(format, start)?.takeIf { it.range.first == start } ?: return null
    val (flags, widthText, precisionText, conversion) = match.destructured
    val number = CalcNumber.leadingNumber(value)
    val body = when (conversion) {
        "d" -> number.truncate().toCalcString()
        else -> fixed(number.toDouble(), precisionText.toIntOrNull() ?: 6)
    }
    val width = widthText.toIntOrNull() ?: 0
    val padded = when {
        body.length >= width -> body
        '-' in flags -> body.padEnd(width)
        '0' in flags -> if (body.startsWith("-")) "-" + body.drop(1).padStart(width - 1, '0') else body.padStart(width, '0')
        else -> body.padStart(width)
    }
    return format.substring(0, start).replace("%%", "%") + padded + format.substring(match.range.last + 1).replace("%%", "%")
}

private val directive = Regex("""%([-0]*)(\d*)(?:\.(\d+))?([fd])""")

/** `%.<places>f` as C writes it: from the double's exact value, a tie going to the even digit. */
private fun fixed(number: Double, places: Int): String {
    val negative = number < 0 || (number == 0.0 && 1.0 / number < 0)
    val (digits, scale) = exactDecimal(abs(number))
    // The value times 10^places, rounded to a whole number.
    val scaled = if (scale >= -places) {
        digits + "0".repeat(scale + places)
    } else {
        val cut = -scale - places
        val padded = digits.padStart(cut + 1, '0')
        roundDigits(padded, padded.length - cut, halfEven = true)
    }
    val all = scaled.padStart(places + 1, '0')
    val integer = all.dropLast(places).trimStart('0').ifEmpty { "0" }
    val text = if (places == 0) integer else integer + "." + all.takeLast(places)
    return if (negative) "-$text" else text
}

/**
 * [digits], a natural number's, cut to [keep] digits and rounded on what was cut: a half up (away
 * from zero), or to even when [halfEven]. A carry makes the result one digit longer: `999` kept to
 * two digits is `100`, standing for 100 × 10.
 */
internal fun roundDigits(digits: String, keep: Int, halfEven: Boolean): String {
    if (digits.length <= keep) return digits
    val head = digits.substring(0, keep)
    val rest = digits.substring(keep)
    val up = when {
        rest[0] > '5' -> true
        rest[0] < '5' -> false
        rest.drop(1).any { it != '0' } -> true
        halfEven -> head.isNotEmpty() && (head.last() - '0') % 2 == 1
        else -> true
    }
    if (!up) return head
    val chars = head.toCharArray()
    var i = chars.lastIndex
    while (i >= 0) {
        if (chars[i] != '9') { chars[i] = chars[i] + 1; return chars.concatToString() }
        chars[i--] = '0'
    }
    return "1" + chars.concatToString()
}

/**
 * The exact value of [x], finite and not negative, as decimal digits and the power of ten of the
 * last one: a double is a whole number times a power of two, and 2^-n is 5^n × 10^-n.
 */
internal fun exactDecimal(x: Double): Pair<String, Int> {
    val bits = x.toRawBits()
    val biased = ((bits ushr 52) and 0x7ff).toInt()
    val fraction = bits and ((1L shl 52) - 1)
    val n = Natural.of(if (biased == 0) fraction else fraction or (1L shl 52))
    val binaryExponent = if (biased == 0) -1074 else biased - 1075
    if (binaryExponent >= 0) {
        repeat(binaryExponent) { n.multiply(2) }
        return n.toString() to 0
    }
    var fives = -binaryExponent
    while (fives >= 13) { n.multiply(1_220_703_125); fives -= 13 } // 5^13, the largest that fits a limb product
    repeat(fives) { n.multiply(5) }
    return n.toString() to binaryExponent
}

/** Just enough of an arbitrary-precision natural number to write a double out exactly. */
private class Natural private constructor(private var limbs: IntArray, private var size: Int) {

    fun multiply(factor: Int) {
        var carry = 0L
        for (i in 0 until size) {
            val product = limbs[i].toLong() * factor + carry
            limbs[i] = (product % BASE).toInt()
            carry = product / BASE
        }
        while (carry > 0) {
            if (size == limbs.size) limbs = limbs.copyOf(size * 2)
            limbs[size++] = (carry % BASE).toInt()
            carry /= BASE
        }
    }

    override fun toString(): String = buildString {
        append(limbs[size - 1])
        for (i in size - 2 downTo 0) append(limbs[i].toString().padStart(9, '0'))
    }

    companion object {
        private const val BASE = 1_000_000_000L

        fun of(value: Long): Natural {
            val limbs = IntArray(8)
            var v = value
            var size = 0
            do { limbs[size++] = (v % BASE).toInt(); v /= BASE } while (v > 0)
            return Natural(limbs, size)
        }
    }
}

private fun Long.absDigits(): String = if (this == Long.MIN_VALUE) "9223372036854775808" else abs(this).toString()

private fun pow10(n: Int): Long {
    var result = 1L
    repeat(n) { result *= 10 }
    return result
}

private fun addExact(a: Long, b: Long): Long? {
    val r = a + b
    return if (((a xor r) and (b xor r)) < 0) null else r
}

private fun multiplyExact(a: Long, b: Long): Long? {
    if (a == 0L || b == 0L) return 0L
    if ((a == -1L && b == Long.MIN_VALUE) || (b == -1L && a == Long.MIN_VALUE)) return null
    val r = a * b
    return if (r / b != a) null else r
}

private fun powExact(base: Long, exponent: Long): Long? {
    var result = 1L
    var b = base
    var e = exponent
    while (e > 0) {
        if (e and 1L == 1L) result = multiplyExact(result, b) ?: return null
        e = e shr 1
        if (e > 0) b = multiplyExact(b, b) ?: return null
    }
    return result
}
