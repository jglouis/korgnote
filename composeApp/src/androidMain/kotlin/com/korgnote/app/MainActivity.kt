package com.korgnote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val androidModule = org.koin.dsl.module {
                single<com.korgnote.app.data.SettingsRepository> { com.korgnote.app.data.PersistentSettingsRepository(applicationContext) }
            }
            App(extraModules = listOf(androidModule))
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}