package com.korgnote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Koin is started once in KorgNoteApplication; the Activity is recreated on rotation.
        setContent {
            // Edge-to-edge means the window no longer shrinks for the soft keyboard: without this
            // the keyboard is drawn over the editor and its save buttons. Android only — on iOS,
            // ComposeUIViewController already lifts the focused field above the keyboard.
            Box(Modifier.imePadding()) {
                App()
            }
        }
    }
}