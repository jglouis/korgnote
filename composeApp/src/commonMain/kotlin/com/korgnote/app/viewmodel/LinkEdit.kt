package com.korgnote.app.viewmodel

import com.korgnote.common.org.edit.DraftLink

/**
 * A link open in the link form: the one the caret was in, or — [isNew] — the place a new one goes,
 * its description the words that were selected there.
 */
data class LinkEdit(val link: DraftLink, val isNew: Boolean)
