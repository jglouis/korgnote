package com.korgnote.app.di

import com.korgnote.app.viewmodel.FileTreeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Simple Koin setup for Multiplatform Compose
val appModule = module {
    viewModel { FileTreeViewModel() }
}

// Expose an init function to start Koin from platform-specific entry points if needed
//fun initKoin(additionalModules: List<Module> = emptyList()): Koin =
//    startKoin {
//        modules(appModule + additionalModules)
//    }.koin
