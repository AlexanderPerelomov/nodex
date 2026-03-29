package com.example.nodex.domain.service.ipfs

import com.example.nodex.data.ConnectionState
import kotlinx.coroutines.flow.StateFlow

interface IPFSService {

    val current: StateFlow<Result<String>?>

    val pingFlow: StateFlow<Int?>

    val connectionFlow : StateFlow<ConnectionState>

    fun connect()

    fun disconnect()

    fun isConnected(): Boolean

    fun clear()

}
