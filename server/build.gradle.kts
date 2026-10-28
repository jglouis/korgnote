plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    application
}

group = "com.korgnote.server"
version = "1.1.0"
application {
    mainClass.set("com.korgnote.server.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(projects.shared)
    implementation(libs.logback)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.serialization.kotlinx.json)
    // A self-signed certificate made on first start, which paired devices pin.
    implementation(libs.ktor.network.tls.certificates)
    // The pairing QR code, drawn on the Devices page and in a terminal.
    implementation(libs.qrcodegen)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    // A real TLS client, pinning the certificate as the apps do, against a real TLS connector.
    testImplementation(libs.ktor.client.java)
    testImplementation(libs.ktor.client.content.negotiation)
}