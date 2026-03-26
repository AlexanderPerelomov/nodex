package com.example.nodex.ui.main

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nodex.data.ConnectionState
import com.example.nodex.domain.datastore.Store
import com.example.nodex.domain.service.ipfs.IPFSService
import com.example.nodex.utils.combine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val ipfsService: IPFSService,
    private val store: Store,
) : ViewModel() {

    private val address = MutableStateFlow("")
    private val cid = MutableStateFlow("")
    private val pollingInterval = MutableStateFlow("")

    private val inputEnabled = ipfsService.connectionFlow.map { it == ConnectionState.Disconnected }
    private val isProgress = ipfsService.connectionFlow.map { it == ConnectionState.Progress }
    private val actionIconRes = ipfsService.connectionFlow.map {
        if (it == ConnectionState.Connected) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
    }

    val state = combine(
        address,
        cid,
        pollingInterval,
        inputEnabled,
        ipfsService.pingFlow,
        ipfsService.current,
        isProgress,
        actionIconRes,
        ::MainState,
    ).stateIn(viewModelScope, SharingStarted.Lazily, MainState.defaultInstance())

    init {
        listenInput()
        initCid()
    }

    fun dispatch(action: Action) {
        when (action) {
            is OnAddressChanged -> {
                address.value = action.value
            }
            is OnCIDChanged -> {
                cid.value = action.value
            }
            is OnPollingIntervalChanged -> {
                if (action.value.isBlank()) {
                    pollingInterval.value = ""
                } else {
                    action.value.toIntOrNull()?.let {
                        if (it in POLLING_INTERVAL_RANGE) {
                            pollingInterval.value = action.value
                        }
                    }
                }
            }

            is ActionButton -> {
                if (ipfsService.isConnected()) {
                    ipfsService.disconnect(clearResult = true)
                } else {
                    ipfsService.connect(action.context)
                }
            }
        }
    }

    private fun initCid() {
        viewModelScope.launch {
            launch {
                address.value = store.getAddress()
            }
            launch {
                cid.value = store.getCid()
            }
            launch {
                pollingInterval.value = (store.getPollingInterval() / 1000).toInt().toString()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun listenInput() {
        address
            .debounce(INTERVAL_CID_DEBOUNCE_MS)
            .filter { it.isNotBlank() }
            .onEach { store.updateAddress(it) }
            .launchIn(viewModelScope)
        cid
            .debounce(INTERVAL_CID_DEBOUNCE_MS)
            .filter { it.isNotBlank() }
            .onEach { store.updateCid(it) }
            .launchIn(viewModelScope)
        cid
            .debounce(INTERVAL_CID_DEBOUNCE_MS)
            .filter { it.isNotBlank() }
            .onEach { value ->
                value.toIntOrNull()?.let {
                    store.updatePollingInterval(it * 1000L)
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        ipfsService.disconnect()
        super.onCleared()
    }

    sealed interface Action

    class OnAddressChanged(val value: String) : Action
    class OnCIDChanged(val value: String) : Action
    class OnPollingIntervalChanged(val value: String) : Action

    class ActionButton(val context: Context) : Action

    @Immutable
    data class MainState(
        val multiAddress: String,
        val cid: String,
        val pollingInterval: String,
        val inputEnabled: Boolean,
        val latency: Int?,
        val content: Result<String>?,
        val isProgress: Boolean,
        val actionIconRes: Int,
    ) {
        companion object {
            fun defaultInstance() = MainState(
                multiAddress = "",
                cid = "",
                pollingInterval = "",
                inputEnabled = true,
                latency = null,
                content = null,
                isProgress = false,
                actionIconRes = android.R.drawable.ic_media_play,
            )
        }
    }

    companion object {
        private const val INTERVAL_CID_DEBOUNCE_MS = 300L
        private val POLLING_INTERVAL_RANGE = 1..3
    }
}