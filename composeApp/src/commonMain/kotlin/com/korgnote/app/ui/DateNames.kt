package com.korgnote.app.ui

import androidx.compose.runtime.Composable
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.month_names
import korgnote.composeapp.generated.resources.month_short_names
import korgnote.composeapp.generated.resources.weekday_initials
import korgnote.composeapp.generated.resources.weekday_names
import korgnote.composeapp.generated.resources.weekday_short_names
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import org.jetbrains.compose.resources.getStringArray
import org.jetbrains.compose.resources.stringArrayResource

/*
 * Month and weekday names in the language the app is shown in. As that language writes them inside
 * a date: French in lower case, so a title capitalises them with [capitalised].
 *
 * On the web the arrays load asynchronously: the first composition gets an empty list, and the
 * name is recomposed once it arrives. Until then each falls back to the English name.
 */

/** `October`, `octobre`. */
@Composable
fun Month.displayName(): String =
    stringArrayResource(Res.array.month_names).getOrNull(ordinal) ?: englishName()

/** `Oct`, `oct.`. */
@Composable
fun Month.shortName(): String =
    stringArrayResource(Res.array.month_short_names).getOrNull(ordinal) ?: englishName().take(3)

/** `Friday`, `vendredi`. */
@Composable
fun DayOfWeek.displayName(): String =
    stringArrayResource(Res.array.weekday_names).getOrNull(ordinal) ?: englishName()

/** `Fri`, `ven.`. */
@Composable
fun DayOfWeek.shortName(): String =
    stringArrayResource(Res.array.weekday_short_names).getOrNull(ordinal) ?: englishName().take(3)

/** `F`, `V`: a column's head in a month grid. */
@Composable
fun DayOfWeek.initial(): String =
    stringArrayResource(Res.array.weekday_initials).getOrNull(ordinal) ?: englishName().take(1)

private fun Enum<*>.englishName(): String = name.lowercase().capitalised()

/** The same names, away from composition: for a notification, say. */
suspend fun monthNames(): List<String> = getStringArray(Res.array.month_names)

suspend fun monthShortNames(): List<String> = getStringArray(Res.array.month_short_names)

suspend fun weekdayNames(): List<String> = getStringArray(Res.array.weekday_names)

suspend fun weekdayShortNames(): List<String> = getStringArray(Res.array.weekday_short_names)

/** `octobre 2026` as a title: `Octobre 2026`. */
fun String.capitalised(): String = replaceFirstChar { it.titlecase() }
