package com.korgnote.common.org.table

/**
 * A table's formulas kept pointing at the same cells while its rows, columns and rules move.
 *
 * A formula names cells by number — `$3`, `@2`, `@II` — so inserting a column before the third
 * makes `$3` read the wrong one, silently. Emacs renumbers rows and columns when its own commands
 * change the table (`org-table-fix-formulas`); these do the same, as text, so that what is not
 * renumbered is left exactly as it was written. They renumber rules too, which Emacs does not.
 *
 * What names a deleted row or column becomes `INVALID`, which no formula can compute: a formula
 * that read it has to be looked at again. A formula that filled it is removed with it, as in Emacs —
 * and also one that filled the deleted column's last row, `@>$3`, which Emacs leaves pointing nowhere.
 */
internal object FormulaReferences {

    private val column = Regex("""\$([0-9]+)""")
    private val row = Regex("""@([0-9]+)""")

    // An absolute rule, `@II` or the end of a range written `..II`: not `@-I`, which counts back
    // from the row being computed and so does not move.
    // Not the I of `@INVALID`.
    private val rule = Regex("""(@|\.\.)(I+)(?![A-Za-z])""")

    /** [formulas] once a column is inserted as the [inserted]th (from 1). */
    fun columnInserted(formulas: List<String>, inserted: Int): List<String> =
        formulas.map { renumber(it, column, "$") { n -> if (n >= inserted) n + 1 else n } }

    /** [formulas] once the [removed]th column (from 1) is deleted. */
    fun columnRemoved(formulas: List<String>, removed: Int): List<String> =
        formulas.filterNot { targetOf(it).matches(Regex("""(@[^$]*)?\$$removed""")) }
            .map { renumber(it, column, "$") { n -> if (n == removed) null else if (n > removed) n - 1 else n } }

    /** [formulas] once columns [a] and [b] (from 1) change places. */
    fun columnsSwapped(formulas: List<String>, a: Int, b: Int): List<String> =
        formulas.map { renumber(it, column, "$") { n -> if (n == a) b else if (n == b) a else n } }

    /** [formulas] once a row is inserted as row [inserted] (`@n`, from 1). */
    fun rowInserted(formulas: List<String>, inserted: Int): List<String> =
        formulas.map { renumber(it, row, "@") { n -> if (n >= inserted) n + 1 else n } }

    /** [formulas] once row [removed] (`@n`) is deleted. */
    fun rowRemoved(formulas: List<String>, removed: Int): List<String> =
        formulas.filterNot { targetOf(it).matches(Regex("""@$removed\$[0-9]+""")) }
            .map { renumber(it, row, "@") { n -> if (n == removed) null else if (n > removed) n - 1 else n } }

    /** [formulas] once rows [a] and [b] (`@n`) change places. */
    fun rowsSwapped(formulas: List<String>, a: Int, b: Int): List<String> =
        formulas.map { renumber(it, row, "@") { n -> if (n == a) b else if (n == b) a else n } }

    /** [formulas] once a rule is inserted as the [inserted]th (`@I` is the first). */
    fun ruleInserted(formulas: List<String>, inserted: Int): List<String> =
        formulas.map { renumberRules(it) { n -> if (n >= inserted) n + 1 else n } }

    /** [formulas] once the [removed]th rule is deleted. */
    fun ruleRemoved(formulas: List<String>, removed: Int): List<String> =
        formulas.map { renumberRules(it) { n -> if (n == removed) null else if (n > removed) n - 1 else n } }

    private fun targetOf(formula: String): String = formula.substringBefore('=').trim()

    /** [formula] with every number [pattern] finds replaced as [map] says; null marks it `INVALID`. */
    private fun renumber(formula: String, pattern: Regex, key: String, map: (Int) -> Int?): String =
        pattern.replace(formula) { match ->
            val n = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            key + (map(n)?.toString() ?: "INVALID")
        }

    private fun renumberRules(formula: String, map: (Int) -> Int?): String =
        rule.replace(formula) { match ->
            val prefix = match.groupValues[1]
            val n = match.groupValues[2].length
            // `@II+1`'s sign belongs to the offset, but `@-I` and `..-I` never get here: the sign
            // stands between the prefix and the I's, which the pattern does not allow.
            prefix + (map(n)?.let { "I".repeat(it) } ?: "INVALID")
        }
}
