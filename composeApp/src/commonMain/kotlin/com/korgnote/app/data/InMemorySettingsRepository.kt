package com.korgnote.app.data

import com.korgnote.common.SERVER_PORT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow

class InMemorySettingsRepository(
    initialHost: String = defaultServerBaseUrl()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore(":"),
    initialPort: Int = SERVER_PORT,
) : SettingsRepository {
    private val host = MutableStateFlow(initialHost)
    private val port = MutableStateFlow(initialPort)

    override val serverHostFlow: Flow<String> = host
    override val serverPortFlow: Flow<Int> = port

    override fun getServerHost(): String = host.value
    override fun getServerPort(): Int = port.value

    override fun setServerHost(host: String) {
        this.host.value = host
    }

    override fun setServerPort(port: Int) {
        this.port.value = port
    }
}
