package com.korgnote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.toOrgDocument

/** Sample document exercising every construct supported by the highlighter and the renderer. */
val OrgSampleDocument = """
    :PROPERTIES:
    :ID:       6d4a3005-6ac4-48b2-8751-0cdb35e4fd93
    :END:
    #+TITLE: KorgNote Syntax Sample
    #+AUTHOR: Jean-Guillaume Louis
    #+TODO: TODO(t) DOING(p!) WAIT(w@/!) | DONE(d!) CANCELED(c@/!)

    * DOING [#A] Write the Org viewer [2/4] :Dev:KorgNote:
    SCHEDULED: <2026-09-20 Sun 10:00> DEADLINE: <2026-09-30 Wed>
    :PROPERTIES:
    :EFFORT:   4:00
    :END:
    Inline markup: *bold*, /italic/, _underline_, +strike+, =verbatim= and ~code~.
    A link [[https://orgmode.org/manual/][to the manual]], a bare https://orgmode.org URL,
    a [[file:~/.mbsyncrc][file link]] and a footnote[fn:1]. Inactive stamp [2026-09-19 Sat].
    # This is a comment line.
    - [x] parse the syntax
    - [ ] render it :: with a description
    1. numbered item
    2. another one
    -----
    ** DONE Tables :Tables:
    | Student   | Theory | Code |  Total |
    |-----------+--------+------+--------|
    | Bob       |      4 |    2 |      6 |
    | Christine |     12 |  5.5 |   17.5 |
    |-----------+--------+------+--------|
    |           |      8 | 3.75 | 11.75 |
    *** WAIT Source blocks
    #+BEGIN_SRC kotlin
    fun main() {
        println("Hello, Org!")
    }
    #+END_SRC
    #+BEGIN_QUOTE
    Simplicity is prerequisite for reliability.
    #+END_QUOTE
    * CANCELED Old idea
    CLOSED: [2026-09-18 Fri 09:58]

    [fn:1] The footnote definition.
""".trimIndent()

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
            ) { content() }
        }
    }
}

@Preview
@Composable
fun OrgText_Preview() {
    PreviewSurface {
        OrgText(content = OrgSampleDocument.toOrgDocument(), onToggleCheckbox = { _, _ -> })
    }
}

@Preview
@Composable
fun OrgEditor_Preview() {
    PreviewSurface {
        var text by remember { mutableStateOf(OrgSampleDocument) }
        val headingColor = MaterialTheme.colorScheme.primary
        val transformation = remember(headingColor) { OrgSyntaxHighlightTransformation(headingColor) }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Content") },
            visualTransformation = transformation
        )
    }
}

@Preview
@Composable
fun OrgTable_Preview() {
    PreviewSurface {
        OrgBody(
            """
            | Name | Qty | Price |
            |-
            | Apples | 10 | 1.20 |
            | Pears with a long name | 3 | 0.95 |
            """.trimIndent()
        )
    }
}
