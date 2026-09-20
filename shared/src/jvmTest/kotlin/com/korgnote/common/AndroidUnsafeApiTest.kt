package com.korgnote.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards against JDK APIs that compile everywhere and then crash on Android.
 *
 * `MutableList.removeLast()` and `removeFirst()` are the ones that have bitten this project:
 * Kotlin resolves them to Java 21's `SequencedCollection` methods, which Android's `java.util.List`
 * does not have and which are not desugared. The result compiles for every target, passes every JVM
 * test, and throws `NoSuchMethodError` on a device — so neither the compiler nor the suite can catch
 * it. A source scan can.
 *
 * Use `removeAt(lastIndex)` / `removeAt(0)` instead.
 */
class AndroidUnsafeApiTest {

    private val forbidden = listOf(".removeLast()", ".removeFirst()")

    /** Source that is compiled into the Android app. */
    private val sourceRoots = listOf(
        File("src/commonMain/kotlin"),
        File("../composeApp/src/commonMain/kotlin"),
        File("../composeApp/src/androidMain/kotlin"),
    )

    @Test
    fun thereIsSourceToScan() {
        val existing = sourceRoots.filter { it.isDirectory }
        assertTrue(existing.isNotEmpty(), "no source roots found from ${File(".").absolutePath}")
    }

    @Test
    fun noAndroidUnsafeSequencedCollectionCalls() {
        val offences = mutableListOf<String>()
        for (root in sourceRoots.filter { it.isDirectory }) {
            for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                file.readLines().forEachIndexed { index, line ->
                    if (line.trimStart().startsWith("//")) return@forEachIndexed
                    forbidden.filter { line.contains(it) }.forEach { call ->
                        offences.add("${file.path}:${index + 1} uses $call")
                    }
                }
            }
        }
        assertTrue(
            offences.isEmpty(),
            "These crash on Android with NoSuchMethodError; use removeAt(index) instead:\n" +
                offences.joinToString("\n"),
        )
    }
}
