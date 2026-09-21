package com.korgnote.app

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.ui.*
import com.korgnote.app.viewmodel.FileTreeViewModel
import com.korgnote.common.org.OrgSection
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        var selectedScreen by remember { mutableStateOf(BottomItem.HOME) }
        var isEditing by remember { mutableStateOf(false) }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val vm = koinViewModel<FileTreeViewModel>()
        LaunchedEffect(Unit) {
            vm.loadFromServer()
            // Then follow the server's change stream for the rest of the session.
            vm.watchServer()
        }
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = selectedScreen != BottomItem.CALENDAR,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Files", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = vm.filterQuery,
                            onValueChange = { vm.filterQuery = it },
                            label = { Text("Filter files") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        FileTree(
                            root = vm.filteredTree,
                            onFileSelected = { name ->
                                scope.launch {
                                    vm.onFileSelected(name)
                                    isEditing = false
                                    drawerState.close()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(selectedScreen.getLabel()) },
                        navigationIcon = {
                            if (selectedScreen != BottomItem.CALENDAR) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Open drawer")
                                }
                            }
                        },
                        actions = {
                            if (selectedScreen == BottomItem.HOME && vm.selectedFileLabel != "No file selected") {
                                if (isEditing) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            vm.saveCurrentFile()
                                            isEditing = false
                                        }
                                    }) {
                                        Icon(Icons.Filled.Save, contentDescription = "Save file")
                                    }
                                } else {
                                    IconButton(onClick = { isEditing = true }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Edit file")
                                    }
                                }
                            }
                        }
                    )
                },
                floatingActionButton = {
                    // Saving an element is explicit: edits stay local until this is pressed.
                    if (vm.editingElementIndex != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallFloatingActionButton(onClick = { vm.cancelElementEdit() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Discard changes to this element")
                            }
                            FloatingActionButton(onClick = { scope.launch { vm.commitElementEdit() } }) {
                                Icon(Icons.Filled.Save, contentDescription = "Save this element")
                            }
                        }
                    } else if (
                        selectedScreen == BottomItem.HOME &&
                        !isEditing &&
                        vm.selectedFileLabel != "No file selected"
                    ) {
                        FloatingActionButton(onClick = { vm.addElementAtEnd() }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add a heading at the end of the file")
                        }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        BottomItem.entries.forEach { item ->
                            NavigationBarItem(
                                selected = selectedScreen == item,
                                onClick = { selectedScreen = item },
                                icon = {
                                    Icon(
                                        item.getIconRes(),
                                        contentDescription = item.getLabel()
                                    )
                                },
                                label = { Text(item.getLabel()) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            ) { paddingValues ->
                // Reading mode scrolls itself, one element at a time, so that opening a large file
                // does not have to lay out every element first. A lazy list cannot be nested in a
                // vertically scrolling parent, so this Column yields its scroll while reading; every
                // other screen, and the whole-file editor, still scroll here as before.
                val readingDocument = selectedScreen == BottomItem.HOME &&
                    !isEditing &&
                    vm.selectedFileContent.isNotBlank()
                val screenScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp)
                        .then(
                            if (readingDocument) Modifier else Modifier.verticalScroll(screenScrollState)
                        ),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (selectedScreen) {
                        BottomItem.HOME -> {
                            Text(
                                text = selectedScreen.getLabel(),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            // Error banner if server unreachable
                            if (vm.lastErrorMessage.isNotBlank()) {
                                Text(vm.lastErrorMessage, color = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            // Reflect selection from ViewModel in UI
                            Text(text = vm.selectedFileLabel)
                            Spacer(modifier = Modifier.height(16.dp))

                            if (isEditing) {
                                val headingColor = MaterialTheme.colorScheme.primary
                                val orgHighlighting = remember(headingColor) {
                                    OrgSyntaxHighlightTransformation(headingColor)
                                }
                                // Styling is limited to the characters on screen: span count, not
                                // span production, is what drives layout cost in a large document.
                                val viewport = remember { EditorViewport() }
                                // Seeded rather than null: null means "style everything", which
                                // would make the first frame after entering edit mode pay for the
                                // whole document. The real range replaces this once the field has
                                // been laid out.
                                var styledRange by remember {
                                    mutableStateOf<IntRange?>(0..EditorViewport.INITIAL_STYLED_CHARS)
                                }
                                orgHighlighting.visibleChars = styledRange
                                val interactionSource = remember { MutableInteractionSource() }
                                val editorTextStyle = LocalTextStyle.current.merge(
                                    TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                )
                                // No fillMaxSize/weight here: the field grows to its natural height so
                                // the surrounding screen Column's own scroll (shared with reading mode
                                // below) is what scrolls it, keeping the scroll position when toggling
                                // between reading and editing.
                                // BasicTextField rather than OutlinedTextField: only it exposes
                                // onTextLayout, which is how the visible character range is found.
                                // The Material look is preserved by the decoration box below.
                                BasicTextField(
                                    value = vm.selectedFileContent,
                                    onValueChange = { vm.onContentChanged(it) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onGloballyPositioned { coordinates ->
                                            viewport.onPositioned(coordinates)
                                            viewport.widenIfNeeded(styledRange)?.let { styledRange = it }
                                        },
                                    textStyle = editorTextStyle,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    interactionSource = interactionSource,
                                    onTextLayout = { layout ->
                                        viewport.onTextLayout(layout)
                                        viewport.widenIfNeeded(styledRange)?.let { styledRange = it }
                                    },
                                    visualTransformation = orgHighlighting,
                                    decorationBox = { innerTextField ->
                                        OutlinedTextFieldDefaults.DecorationBox(
                                            value = vm.selectedFileContent,
                                            innerTextField = innerTextField,
                                            enabled = true,
                                            singleLine = false,
                                            // None on purpose: the decoration box only needs the
                                            // text, and passing the real transformation would run
                                            // the highlighter a second time on every keystroke.
                                            visualTransformation = VisualTransformation.None,
                                            interactionSource = interactionSource,
                                            label = { Text("Content") },
                                        )
                                    }
                                )
                            } else if (vm.selectedFileContent.isBlank()) {
                                Text("Select an .org file to view its content")
                            } else {
                                OrgDocumentView(
                                    // weight, not wrap: the lazy list needs a bounded height to
                                    // know what is on screen, and fills what the header leaves.
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    text = vm.selectedFileContent,
                                    editingElementIndex = vm.editingElementIndex,
                                    elementDraft = vm.elementDraft,
                                    elementDraftCaret = vm.elementDraftCaret,
                                    onElementDraftChange = { vm.onElementDraftChanged(it) },
                                    onEditElement = { vm.beginElementEdit(it) },
                                    onAddElementAfter = { vm.addElementAfter(it) },
                                    scrollToElementIndex = vm.scrollToElementIndex,
                                    onScrolledToElement = { vm.onScrolledToElement() },
                                    onToggleCheckbox = { offset -> vm.toggleCheckboxAt(offset, scope) }
                                )
                            }
                        }

                        BottomItem.CALENDAR -> {
                            CalendarScreen(
                                modifier = Modifier.fillMaxSize(),
                                scheduledByDate = vm.scheduledByDate,
                                onOpenScheduled = { pair ->
                                    // The heading is resolved by the view model once the file's text
                                    // has loaded: that text arrives asynchronously, so working it out
                                    // here would search the previously open file.
                                    vm.onFileSelected(pair.first, scrollToHeading = pair.second as? OrgSection)
                                    isEditing = false
                                    selectedScreen = BottomItem.HOME
                                }
                            )
                        }

                        BottomItem.SETTINGS -> {
                            val settingsRepo = koinInject<SettingsRepository>()
                            SettingsScreen(repo = settingsRepo, onSaved = { scope.launch { vm.loadFromServer() } })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tracks which characters of the editor are on screen, so the highlighter can style only those.
 *
 * The field has no height of its own — it grows to fit the document inside the screen's scroll
 * container — so "visible" is derived from where the field currently sits in the window rather
 * than from a scroll offset, which keeps this independent of who owns the scrolling.
 */
private class EditorViewport {
    private var layout: TextLayoutResult? = null
    private var topInRoot = 0f
    private var rootHeight = 0f

    fun onPositioned(coordinates: LayoutCoordinates) {
        topInRoot = coordinates.positionInRoot().y
        rootHeight = coordinates.findRootCoordinates().size.height.toFloat()
    }

    fun onTextLayout(result: TextLayoutResult) {
        layout = result
    }

    /**
     * Returns a new range to style when [current] no longer covers what is on screen, else null.
     *
     * The returned range is padded by a screenful on each side, so ordinary scrolling stays inside
     * it and does not re-trigger. Widening only when the visible region escapes the current window
     * is also what stops layout → range → re-layout from looping.
     */
    fun widenIfNeeded(current: IntRange?): IntRange? {
        val result = layout ?: return null
        if (rootHeight <= 0f) return null

        val topInField = (-topInRoot).coerceAtLeast(0f)
        val firstLine = result.getLineForVerticalPosition(topInField)
        val lastLine = result.getLineForVerticalPosition(topInField + rootHeight)
        val start = result.getLineStart(firstLine)
        val end = result.getLineEnd(lastLine, visibleEnd = true)
        if (current != null && start >= current.first && end <= current.last) return null

        val padding = (end - start).coerceAtLeast(MIN_PADDING_CHARS)
        return (start - padding).coerceAtLeast(0)..(end + padding)
    }

    companion object {
        private const val MIN_PADDING_CHARS = 1_000

        /** Styled up front, before the first layout tells us what is actually on screen. */
        const val INITIAL_STYLED_CHARS = 4_000
    }
}

private enum class BottomItem {
    HOME, CALENDAR, SETTINGS;

    @Composable
    fun getLabel() = when (this) {
        HOME -> "Home"
        CALENDAR -> "Calendar"
        SETTINGS -> "Settings"
    }

    fun getIconRes() = when (this) {
        HOME -> Icons.Filled.Home
        CALENDAR -> Icons.Filled.CalendarMonth
        SETTINGS -> Icons.Filled.Settings
    }
}