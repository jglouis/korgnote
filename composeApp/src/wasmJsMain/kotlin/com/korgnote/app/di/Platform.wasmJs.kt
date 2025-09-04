package com.korgnote.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

actual fun platformBuildHttpClient(): HttpClient = HttpClient(Js) {
    install(ContentNegotiation) { json() }
    expectSuccess = false
}
