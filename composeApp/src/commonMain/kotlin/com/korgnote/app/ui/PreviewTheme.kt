package com.korgnote.app.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable

/**
 * What every preview is drawn in: the app's own theme, light or dark as asked, on a surface of its
 * background colour.
 *
 * Each composable gets two previews, one per theme, rather than one `@PreviewLightDark`, so the
 * source shows both side by side. The theme is passed outright, not read from the preview's night
 * mode, because [KorgNoteTheme] also sets [OrgColors], and not every preview renderer sets the mode.
 * The pattern:
 *
 * ```
 * @Composable
 * private fun ThingPreview(dark: Boolean) = PreviewTheme(dark) { Thing(...) }
 *
 * @Preview(name = "Light")
 * @Composable
 * private fun ThingLightPreview() = ThingPreview(dark = false)
 *
 * @Preview(name = "Dark")
 * @Composable
 * private fun ThingDarkPreview() = ThingPreview(dark = true)
 * ```
 */
@Composable
internal fun PreviewTheme(dark: Boolean, content: @Composable () -> Unit) {
    KorgNoteTheme(dark = dark) {
        Surface { content() }
    }
}
