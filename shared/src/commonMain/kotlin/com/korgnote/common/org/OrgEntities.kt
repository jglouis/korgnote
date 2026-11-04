package com.korgnote.common.org

/**
 * Org's named entities, `\alpha` or `\to{}`, as reading mode shows them: the character each stands
 * for. A subset of `org-entities` — Greek, arrows, maths, typography, currency and the common
 * accented letters — chosen for what notes actually write; a name not here stays as typed.
 */
private val ORG_ENTITIES: Map<String, String> = mapOf(
    // Greek
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π", "varpi" to "ϖ",
    "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "sigmaf" to "ς", "tau" to "τ",
    "upsilon" to "υ", "phi" to "φ", "varphi" to "ϕ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Alpha" to "Α", "Beta" to "Β", "Gamma" to "Γ", "Delta" to "Δ", "Epsilon" to "Ε", "Zeta" to "Ζ",
    "Eta" to "Η", "Theta" to "Θ", "Iota" to "Ι", "Kappa" to "Κ", "Lambda" to "Λ", "Mu" to "Μ",
    "Nu" to "Ν", "Xi" to "Ξ", "Omicron" to "Ο", "Pi" to "Π", "Rho" to "Ρ", "Sigma" to "Σ", "Tau" to "Τ",
    "Upsilon" to "Υ", "Phi" to "Φ", "Chi" to "Χ", "Psi" to "Ψ", "Omega" to "Ω",
    // Arrows
    "to" to "→", "rarr" to "→", "rightarrow" to "→", "larr" to "←", "leftarrow" to "←", "gets" to "←",
    "uarr" to "↑", "uparrow" to "↑", "darr" to "↓", "downarrow" to "↓", "harr" to "↔",
    "leftrightarrow" to "↔", "rArr" to "⇒", "Rightarrow" to "⇒", "lArr" to "⇐", "Leftarrow" to "⇐",
    "hArr" to "⇔", "Leftrightarrow" to "⇔", "uArr" to "⇑", "Uparrow" to "⇑", "dArr" to "⇓",
    "Downarrow" to "⇓", "mapsto" to "↦", "crarr" to "↵", "longrightarrow" to "⟶", "longleftarrow" to "⟵",
    "Longrightarrow" to "⟹", "Longleftarrow" to "⟸", "longleftrightarrow" to "⟷",
    // Maths
    "times" to "×", "div" to "÷", "pm" to "±", "plusmn" to "±", "mp" to "∓", "cdot" to "⋅",
    "middot" to "·", "minus" to "−", "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
    "neq" to "≠", "ne" to "≠", "approx" to "≈", "asymp" to "≈", "equiv" to "≡", "sim" to "∼",
    "simeq" to "≃", "cong" to "≅", "propto" to "∝", "infin" to "∞", "infty" to "∞", "sum" to "∑",
    "prod" to "∏", "int" to "∫", "oint" to "∮", "partial" to "∂", "nabla" to "∇", "forall" to "∀",
    "exist" to "∃", "exists" to "∃", "nexist" to "∄", "empty" to "∅", "emptyset" to "∅",
    "isin" to "∈", "in" to "∈", "notin" to "∉", "ni" to "∋", "sub" to "⊂", "subset" to "⊂",
    "sup" to "⊃", "supset" to "⊃", "sube" to "⊆", "subseteq" to "⊆", "supe" to "⊇", "supseteq" to "⊇",
    "cap" to "∩", "cup" to "∪", "and" to "∧", "wedge" to "∧", "or" to "∨", "vee" to "∨",
    "not" to "¬", "neg" to "¬", "sqrt" to "√", "radic" to "√", "deg" to "°", "prime" to "′",
    "Prime" to "″", "there4" to "∴", "therefore" to "∴", "because" to "∵", "ldots" to "…",
    "dots" to "…", "hellip" to "…", "cdots" to "⋯", "lowast" to "∗", "star" to "⋆", "oplus" to "⊕",
    "otimes" to "⊗", "perp" to "⊥", "angle" to "∠", "ang" to "∠", "ell" to "ℓ", "hbar" to "ℏ",
    "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ", "wp" to "℘", "lceil" to "⌈", "rceil" to "⌉",
    "lfloor" to "⌊", "rfloor" to "⌋", "langle" to "⟨", "rangle" to "⟩", "ll" to "≪", "gg" to "≫",
    "frac12" to "½", "half" to "½", "frac14" to "¼", "frac34" to "¾", "sup1" to "¹", "sup2" to "²",
    "sup3" to "³", "permil" to "‰", "micro" to "µ", "ordf" to "ª", "ordm" to "º",
    // Typography
    "nbsp" to "\u00A0", "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009", "shy" to "\u00AD",
    "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "bull" to "•", "bullet" to "•", "dagger" to "†", "dag" to "†", "Dagger" to "‡", "ddag" to "‡",
    "sect" to "§", "S" to "§", "para" to "¶", "P" to "¶", "copy" to "©", "copyright" to "©",
    "reg" to "®", "trade" to "™", "TM" to "™", "iexcl" to "¡", "iquest" to "¿", "vert" to "|",
    "vbar" to "|", "brvbar" to "¦", "backslash" to "\\", "checkmark" to "✓", "check" to "✓",
    "smiley" to "☺", "blacksmile" to "☻", "sad" to "☹", "frowny" to "☹", "heart" to "♥",
    "hearts" to "♥", "spades" to "♠", "clubs" to "♣", "diams" to "♦", "flat" to "♭", "sharp" to "♯",
    "natural" to "♮", "loz" to "◊", "ast" to "*", "under" to "_",
    // Currency
    "euro" to "€", "EUR" to "€", "pound" to "£", "GBP" to "£", "yen" to "¥", "cent" to "¢",
    "curren" to "¤", "dollar" to "$", "USD" to "$",
    // Accented letters
    "aacute" to "á", "agrave" to "à", "acirc" to "â", "auml" to "ä", "atilde" to "ã", "aring" to "å",
    "aelig" to "æ", "ccedil" to "ç", "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë",
    "iacute" to "í", "igrave" to "ì", "icirc" to "î", "iuml" to "ï", "ntilde" to "ñ", "oacute" to "ó",
    "ograve" to "ò", "ocirc" to "ô", "ouml" to "ö", "otilde" to "õ", "oslash" to "ø", "oelig" to "œ",
    "szlig" to "ß", "uacute" to "ú", "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü", "yacute" to "ý",
    "yuml" to "ÿ", "Aacute" to "Á", "Agrave" to "À", "Auml" to "Ä", "Aring" to "Å", "AElig" to "Æ",
    "Ccedil" to "Ç", "Eacute" to "É", "Egrave" to "È", "Ntilde" to "Ñ", "Ouml" to "Ö", "Oslash" to "Ø",
    "OElig" to "Œ", "Uuml" to "Ü",
)

/** An entity where one may start: `\name` then not a letter, or `\name{}`. */
private val ENTITY = Regex("""\\([A-Za-z]+[0-9]*)(\{\})?""")

/**
 * The entity at [index] of [text] as `(what it shows, how many characters it takes)`, or null when
 * none starts there or the name is not one Org knows.
 */
fun orgEntityAt(text: String, index: Int): Pair<String, Int>? {
    val m = ENTITY.matchAt(text, index) ?: return null
    val shown = ORG_ENTITIES[m.groupValues[1]] ?: return null
    // Without `{}`, the name must end at a non-letter, so `\alphabet` is no `\alpha` followed by `bet`.
    if (m.groupValues[2].isEmpty()) {
        val after = index + m.value.length
        if (after < text.length && text[after].isLetter()) return null
    }
    return shown to m.value.length
}

/**
 * A `_{…}` or `^{…}` script at [index] (on its `_` or `^`) as `(its text, how many characters it
 * takes)`, or null. Only the braced form: Org's bare `x_2` would turn every `snake_case` word in a
 * note into a subscript, which Org itself only does when exporting with `^:t`.
 */
fun orgScriptAt(text: String, index: Int): Pair<String, Int>? {
    if (index == 0 || text[index - 1].isWhitespace()) return null
    val m = SCRIPT.matchAt(text, index) ?: return null
    return m.groupValues[1] to m.value.length
}

private val SCRIPT = Regex("""[_^]\{([^{}\n]+)\}""")

/** A `<<target>>` or `<<<radio target>>>` at [index] as `(its text, how many characters it takes)`, or null. */
fun orgTargetAt(text: String, index: Int): Pair<String, Int>? {
    val m = OrgSyntax.target.matchAt(text, index) ?: return null
    if (m.groupValues[1].length != m.groupValues[3].length) return null
    return m.groupValues[2] to m.value.length
}
