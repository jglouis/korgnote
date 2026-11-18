package com.korgnote.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every preview in the app, drawn on the desktop runtime: one that throws fails here instead of
 * only showing an error in the IDE's preview pane.
 *
 * `@Preview` isn't kept at run time, so the previews are found by the naming the app uses for them
 * (see [PreviewTheme]): a parameterless composable whose name ends in `LightPreview` or
 * `DarkPreview`, both of which must exist. Each is saved as a PNG under
 * `build/previews/`, to look at all of them at once.
 */
@OptIn(ExperimentalTestApi::class)
class PreviewRenderTest {
    private val outDir = File("build/previews").apply { mkdirs() }

    @Test
    fun everyPreviewRendersInBothThemes() {
        val previews = previewMethods()
        assertTrue(previews.size >= 162, "Found only ${previews.size} previews; is the class scan broken?")

        val names = previews.map { it.name }.toSet()
        val unpaired = names.filter {
            val twin = if (it.endsWith("LightPreview")) it.replace("LightPreview", "DarkPreview")
            else it.replace("DarkPreview", "LightPreview")
            twin !in names
        }
        assertTrue(unpaired.isEmpty(), "Previews without their other theme: $unpaired")

        val failures = mutableListOf<String>()
        for (method in previews) {
            method.isAccessible = true
            try {
                runComposeUiTest {
                    setContent {
                        Box(Modifier.width(411.dp).heightIn(max = 891.dp)) {
                            method.invoke(null, currentComposer, 0)
                        }
                    }
                    // A dialog or popup is a root of its own, drawn here over the preview behind a scrim.
                    val roots = onAllNodes(isRoot())
                    // A preview that is only a dialog leaves the window's own root empty, with nothing to capture.
                    val images = roots.fetchSemanticsNodes().indices
                        .filter { roots.fetchSemanticsNodes()[it].size.let { size -> size.width > 0 && size.height > 0 } }
                        .map { roots[it].captureToImage().toAwtImage() }
                    val image = BufferedImage(images.maxOf { it.width }, images.maxOf { it.height }, BufferedImage.TYPE_INT_ARGB)
                    image.createGraphics().apply {
                        for ((index, overlay) in images.withIndex()) {
                            if (index == 0) { drawImage(overlay, 0, 0, null); continue }
                            color = java.awt.Color(0, 0, 0, 0x66)
                            fillRect(0, 0, image.width, image.height)
                            drawImage(overlay, (image.width - overlay.width) / 2, (image.height - overlay.height) / 2, null)
                        }
                        dispose()
                    }
                    ImageIO.write(image, "png", File(outDir, "${method.declaringClass.simpleName}.${method.name}.png"))
                }
            } catch (e: Throwable) {
                val cause = generateSequence(e) { it.cause }.last()
                failures += "${method.declaringClass.simpleName}.${method.name}: $cause"
            }
        }
        if (failures.isNotEmpty()) fail("Previews that failed to render:\n" + failures.joinToString("\n"))
    }

    /** The preview functions compiled into the app's classes, by their names. */
    private fun previewMethods(): List<Method> {
        val packagePath = "com/korgnote/app"
        val root = javaClass.classLoader.getResources(packagePath).toList()
            .map { File(it.toURI()) }
            .first { File(it, "ui/PreviewThemeKt.class").exists() }
            .parentFile.parentFile.parentFile
        return root.walk()
            .filter { it.isFile && it.name.endsWith("Kt.class") }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .flatMap { Class.forName(it).declaredMethods.asSequence() }
            .filter { JavaModifier.isStatic(it.modifiers) }
            .filter { it.name.endsWith("LightPreview") || it.name.endsWith("DarkPreview") }
            .filter { it.parameterTypes.contentEquals(arrayOf(Composer::class.java, Int::class.javaPrimitiveType)) }
            .sortedBy { "${it.declaringClass.name}.${it.name}" }
            .toList()
    }
}
