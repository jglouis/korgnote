package com.korgnote.common.org.table

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Calc's functions beyond arithmetic, computed as Calc computes them: an exact result stays a whole
 * number — `log10(1000)` is `3`, `vvar([1, 3])` is `2` — and anything else is a float at twelve
 * digits. What Calc would leave unevaluated, as `ln(0)` or the deviation of a single value, is a
 * [FormulaError]: no cell should receive it.
 */
internal object CalcFunctions {

    /** `vprod`: the product of [items]; 1 when there is none. */
    fun product(items: List<CalcNumber>): CalcNumber = items.fold(CalcNumber.Whole(1) as CalcNumber) { p, n -> p * n }

    /**
     * `vvar` and `vpvar`: how far [items] spread around their mean, as the sample variance — over
     * n - 1 — or, for the [population], over n.
     */
    fun variance(name: String, items: List<CalcNumber>, population: Boolean): CalcNumber {
        val needed = if (population) 1 else 2
        if (items.size < needed) {
            throw FormulaError("$name(…) needs at least $needed number${if (needed > 1) "s" else ""}; it has ${items.size}.")
        }
        val count = CalcNumber.Whole(items.size.toLong())
        val mean = (items.reduce { a, b -> a + b } / count)!!
        val squares = items.fold(CalcNumber.Whole(0) as CalcNumber) { sum, x -> (x - mean).let { sum + it * it } }
        return (squares / (if (population) count else CalcNumber.Whole(items.size - 1L)))!!
    }

    /** `vsdev` and `vpsdev`: the square root of the variance. */
    fun deviation(name: String, items: List<CalcNumber>, population: Boolean): CalcNumber =
        squareRoot(variance(name, items, population))

    fun squareRoot(n: CalcNumber): CalcNumber {
        if (n.signum() < 0) throw FormulaError("sqrt($n) is not a real number.")
        if (n is CalcNumber.Whole) {
            val root = sqrt(n.value.toDouble()).toLong()
            for (r in root - 1..root + 1) if (r >= 0 && r * r == n.value) return CalcNumber.Whole(r)
        }
        return CalcNumber.fromDouble(sqrt(n.toDouble()))
    }

    /** `exp`: e to the power [n]; exactly 1 for a whole 0. */
    fun exp(n: CalcNumber): CalcNumber =
        if (n is CalcNumber.Whole && n.value == 0L) CalcNumber.Whole(1) else CalcNumber.fromDouble(kotlin.math.exp(n.toDouble()))

    /** `ln`: the natural logarithm; exactly 0 for a whole 1. */
    fun ln(n: CalcNumber): CalcNumber {
        positive("ln", n)
        return if (n is CalcNumber.Whole && n.value == 1L) CalcNumber.Whole(0) else CalcNumber.fromDouble(ln(n.toDouble()))
    }

    /** `log10`: whole for a whole power of ten. */
    fun log10(n: CalcNumber): CalcNumber = log("log10", n, CalcNumber.Whole(10))

    /** `log(n, base)`: whole when [n] is a whole power of a whole [base]. */
    fun log(name: String, n: CalcNumber, base: CalcNumber): CalcNumber {
        positive(name, n)
        positive(name, base)
        if (n is CalcNumber.Whole && base is CalcNumber.Whole && base.value > 1) {
            var power = 1L
            var exponent = 0L
            while (power < n.value && power <= Long.MAX_VALUE / base.value) {
                power *= base.value
                exponent++
            }
            if (power == n.value) return CalcNumber.Whole(exponent)
        }
        if (base.toDouble() == 1.0) throw FormulaError("$name(…) has no logarithm in base 1.")
        return CalcNumber.fromDouble(ln(n.toDouble()) / ln(base.toDouble()))
    }

    fun sign(n: CalcNumber): CalcNumber = CalcNumber.Whole(n.signum().toLong())

    /** `fact`: n! for a whole n, up to 20! — beyond, Calc's exact result outgrows what is kept here. */
    fun factorial(n: CalcNumber): CalcNumber {
        val whole = n as? CalcNumber.Whole ?: throw FormulaError("fact(…) needs a whole number here, not $n.")
        if (whole.value !in 0..20) throw FormulaError("fact(…) is only computed here from 0 to 20, not for ${whole.value}.")
        return CalcNumber.Whole((1..whole.value).fold(1L) { p, k -> p * k })
    }

    fun gcd(a: CalcNumber, b: CalcNumber): CalcNumber = CalcNumber.Whole(gcd(integral("gcd", a), integral("gcd", b)))

    fun lcm(a: CalcNumber, b: CalcNumber): CalcNumber {
        val x = integral("lcm", a)
        val y = integral("lcm", b)
        if (x == 0L || y == 0L) return CalcNumber.Whole(0)
        return CalcNumber.Whole(kotlin.math.abs(x / gcd(x, y) * y))
    }

    /**
     * `pv(rate, n, payment)`: what [n] payments of [payment], one per period at [rate] a period, are
     * worth today.
     */
    fun presentValue(rate: CalcNumber, n: CalcNumber, payment: CalcNumber): CalcNumber {
        nonZero("pv", rate)
        val discount = (CalcNumber.Whole(1) + rate).pow(-n) ?: throw FormulaError("pv(…) cannot be computed for these values.")
        return (payment * (CalcNumber.Whole(1) - discount) / rate)!!
    }

    /** `fv(rate, n, payment)`: what [n] payments of [payment] at [rate] a period are worth at the end. */
    fun futureValue(rate: CalcNumber, n: CalcNumber, payment: CalcNumber): CalcNumber {
        nonZero("fv", rate)
        val growth = (CalcNumber.Whole(1) + rate).pow(n) ?: throw FormulaError("fv(…) cannot be computed for these values.")
        return (payment * (growth - CalcNumber.Whole(1)) / rate)!!
    }

    /** `pmt(rate, n, amount)`: the payment, each period, that repays [amount] in [n] periods at [rate]. */
    fun payment(rate: CalcNumber, n: CalcNumber, amount: CalcNumber): CalcNumber {
        nonZero("pmt", rate)
        val discount = (CalcNumber.Whole(1) + rate).pow(-n) ?: throw FormulaError("pmt(…) cannot be computed for these values.")
        return (amount * rate / (CalcNumber.Whole(1) - discount))
            ?: throw FormulaError("pmt(…) cannot be computed for these values.")
    }

    /** `npv(rate, values…)`: [values], one a period from the next one on, as worth today at [rate]. */
    fun netPresentValue(rate: CalcNumber, values: List<CalcNumber>): CalcNumber {
        val factor = CalcNumber.Whole(1) + rate
        if (factor.isZero) throw FormulaError("npv(…) cannot be computed at a rate of -1.")
        var discount: CalcNumber = CalcNumber.Whole(1)
        var total: CalcNumber = CalcNumber.Whole(0)
        for (value in values) {
            discount *= factor
            total += (value / discount)!!
        }
        return total
    }

    private fun positive(name: String, n: CalcNumber) {
        if (n.signum() <= 0) throw FormulaError("$name(…) needs a number above zero, not $n.")
    }

    private fun nonZero(name: String, rate: CalcNumber) {
        if (rate.isZero) throw FormulaError("$name(…) is not defined at a rate of zero.")
    }

    private fun integral(name: String, n: CalcNumber): Long = when (n) {
        is CalcNumber.Whole -> n.value
        is CalcNumber.Decimal -> (n.truncate() as? CalcNumber.Whole)?.takeIf { (n - it).isZero }?.value
            ?: throw FormulaError("$name(…) needs whole numbers, not $n.")
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) kotlin.math.abs(a) else gcd(b, a % b)
}
