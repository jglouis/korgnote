package com.korgnote.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

actual fun platformBuildHttpClient(): HttpClient = HttpClient(Java) {
    install(ContentNegotiation) { json() }
    expectSuccess = false
}
