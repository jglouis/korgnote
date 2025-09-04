package com.korgnote.app.data

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val serverHostFlow: Flow<String>
    val serverPortFlow: Flow<Int>

    fun getServerHost(): String
    fun getServerPort(): Int

    fun setServerHost(host: String)
    fun setServerPort(port: Int)
}

