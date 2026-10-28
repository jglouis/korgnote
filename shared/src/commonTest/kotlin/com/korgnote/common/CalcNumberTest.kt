package com.korgnote.common

import com.korgnote.common.org.table.CalcNumber
import com.korgnote.common.org.table.formatLikeEmacs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every expected string here is what Emacs 31.1's Calc printed for the same computation, with the
 * modes Org sets for table formulas (`org-calc-default-modes`).
 */
class CalcNumberTest {

    private fun n(text: String) = CalcNumber.parse(text)!!

    @Test
    fun integersStayExactAndFloatsStayFloats() {
        assertEquals("2420.", (n("2000") * (n("1") + n("0.21"))).toCalcString())
        assertEquals("320", (n("320") * (n("1") + n("0"))).toCalcString())
        assertEquals("167.7", (n("167.70") * (n("1") + n("0"))).toCalcString())
        assertEquals("3", (n("6") / n("2"))!!.toCalcString())
        assertEquals("3.5", (n("7") / n("2"))!!.toCalcString())
        assertEquals("0.", (n("1") - n("1.0")).toCalcString())
        assertEquals("3.", (n("1.5") * n("2")).toCalcString())
        assertEquals("1000.", n("1e3").toCalcString())
    }

    @Test
    fun decimalsAddUpAsDecimals() {
        assertEquals("0.3", (n("0.1") + n("0.2")).toCalcString())
        assertEquals("0.6", (n("0.1") + n("0.2") + n("0.3")).toCalcString())
        assertEquals("154.01", (n("136") + n("-117.99") + n("136")).toCalcString())
        assertEquals("1.21", (n("1.1") * n("1.1")).toCalcString())
    }

    @Test
    fun floatsShowEightDigitsAndKeepWhatRoundingGave() {
        assertEquals("7.3846154", (n("96") / n("13"))!!.toCalcString())
        assertEquals("0.33333333", (n("1") / n("3"))!!.toCalcString())
        assertEquals("1.2345679", n("1.23456785").toCalcString())
        assertEquals("-1.2345679", n("-1.23456785").toCalcString())
        assertEquals("1.2000000", n("1.2000000001").toCalcString())
        assertEquals("10.0000000", n("9.99999996").toCalcString())
        assertEquals("123456790.", n("123456789.5").toCalcString())
        assertEquals("100000000.", n("99999999.6").toCalcString())
    }

    @Test
    fun veryLargeAndSmallFloatsUseAnExponent() {
        assertEquals("0.01", n("0.01").toCalcString())
        assertEquals("0.012345679", n("0.0123456789").toCalcString())
        assertEquals("9.9e-3", n("0.0099").toCalcString())
        assertEquals("1e-3", n("0.001").toCalcString())
        assertEquals("-1.234e-3", n("-0.001234").toCalcString())
        assertEquals("1.00000000e-3", n("0.000999999996").toCalcString())
        assertEquals("100000000000.", (n("1e11") * n("1.0")).toCalcString())
        assertEquals("1e12", (n("1e12") * n("1.0")).toCalcString())
        assertEquals("1.00000000e12", (n("999999999999") * n("1.0")).toCalcString())
        assertEquals("123456789012345", n("123456789012345").toCalcString())
    }

    @Test
    fun cellsReadAsCalcReadsThem() {
        assertEquals(CalcNumber.Whole(2000), n("2000"))
        assertEquals(CalcNumber.Decimal(1677, -1), n("167.70"))
        assertEquals("0.12", n("12%").toCalcString())
        assertNull(CalcNumber.parse("Proximus"))
        assertNull(CalcNumber.parse("[2024-07-01 Mon]"))
        assertNull(CalcNumber.parse("1,5"))
        assertEquals(CalcNumber.Whole(12), CalcNumber.leadingNumber("12abc"))
        assertEquals(CalcNumber.Whole(0), CalcNumber.leadingNumber("none"))
        assertEquals(CalcNumber.Whole(2420), CalcNumber.leadingNumber("2420."))
    }

    @Test
    fun roundingFunctionsAsInCalc() {
        assertEquals("3", n("2.5").round().toCalcString())
        assertEquals("-3", n("-2.5").round().toCalcString())
        assertEquals("2.57", n("2.567").round(2).toCalcString())
        assertEquals("1200", n("1234.5678").round(-2).toCalcString())
        assertEquals("2", n("2.5").floor().toCalcString())
        assertEquals("-3", n("-2.5").floor().toCalcString())
        assertEquals("3", n("2.1").ceil().toCalcString())
        assertEquals("-2", n("-2.7").truncate().toCalcString())
    }

    @Test
    fun divisionsAndRemaindersRoundDown() {
        assertEquals("-2", n("7").mod(n("-3"))!!.toCalcString())
        assertEquals("2", n("-7").mod(n("3"))!!.toCalcString())
        assertEquals("-3", n("-7").intDiv(n("3"))!!.toCalcString())
        assertNull(n("1") / n("0"))
        assertEquals("1024", n("2").pow(n("10"))!!.toCalcString())
        assertEquals("0.5", n("2").pow(n("-1"))!!.toCalcString())
        assertEquals("1.4142136", n("2").pow(n("0.5"))!!.toCalcString())
    }

    @Test
    fun formatsAsEmacsFormatDoes() {
        assertEquals("2420.00", formatLikeEmacs("%.2f", "2420."))
        assertEquals("320.00", formatLikeEmacs("%.2f", "320"))
        // The double's exact value decides, and a tie goes to the even digit, as in C.
        assertEquals("0.12", formatLikeEmacs("%.2f", "0.125"))
        assertEquals("0.38", formatLikeEmacs("%.2f", "0.375"))
        assertEquals("2.67", formatLikeEmacs("%.2f", "2.675"))
        assertEquals("1.00", formatLikeEmacs("%.2f", "1.005"))
        assertEquals("2", formatLikeEmacs("%.0f", "2.5"))
        assertEquals("4", formatLikeEmacs("%.0f", "3.5"))
        assertEquals("-0.12", formatLikeEmacs("%.2f", "-0.125"))
        assertEquals("2", formatLikeEmacs("%d", "2.675"))
        assertEquals("13288.87 €", formatLikeEmacs("%.2f €", "13288.866"))
        assertEquals("  3.14", formatLikeEmacs("%6.2f", "3.14159"))
        assertNull(formatLikeEmacs("%s", "1"))
        assertNull(formatLikeEmacs("%.2f and %.2f", "1"))
    }
}
