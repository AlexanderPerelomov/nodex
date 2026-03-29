package com.example.nodex.domain.service.ipfs

import android.util.Log
import com.example.nodex.data.ConnectionState
import com.example.nodex.domain.datastore.Store
import io.ipfs.cid.Cid
import io.ipfs.multiaddr.MultiAddress
import io.ipfs.multibase.Charsets
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.protocol.PingTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.peergos.BlockRequestAuthoriser
import org.peergos.EmbeddedIpfs
import org.peergos.HostBuilder
import org.peergos.Want
import org.peergos.blockstore.RamBlockstore
import org.peergos.config.IdentitySection
import org.peergos.protocol.dht.RamRecordStore
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext

class IPFSServiceImpl(
    scope: CoroutineScope,
    private val store: Store,
) : IPFSService, CoroutineScope {

    override val coroutineContext: CoroutineContext = scope.coroutineContext

    private val provideBlocks = true
    private val swarmAddresses = listOf(MultiAddress("/ip4/0.0.0.0/tcp/4001"))
    private val authoriser = BlockRequestAuthoriser { _, _, _ ->
        CompletableFuture.completedFuture(true)
    }
    private val addToLocal = false

    private var ipfs: EmbeddedIpfs? = null

    private var connectionJob: Job? = null
    private var listenJob: Job? = null
    private var pingJob: Job? = null

    private val _current = MutableStateFlow<Result<String>?>(null)
    override val current = _current.asStateFlow()

    private val _pingFlow = MutableStateFlow<Int?>(null)
    override val pingFlow = _pingFlow.asStateFlow()

    private val _connectionFlow = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionFlow = _connectionFlow.asStateFlow()

    val mutex = Mutex()

    override fun connect() {
        if (connectionFlow.value != ConnectionState.Disconnected) return
        Log.d(LOG_TAG, "Connect is called")
        connectionJob?.cancel()
        connectionJob = launch {
            try {
                mutex.withLock {
                    _connectionFlow.update { ConnectionState.Progress }
                    _current.update { null }
                    val bootstrapAddresses = listOf(MultiAddress(store.getAddress()))
                    val identity = createIdentity()

                    ipfs = EmbeddedIpfs.build(
                        RamRecordStore(),
                        RamBlockstore(),
                        provideBlocks,
                        swarmAddresses,
                        bootstrapAddresses,
                        identity,
                        authoriser,
                        Optional.empty(),
                    )
                }
                ipfs?.start()

                if (isActive) {
                    _connectionFlow.update { ConnectionState.Connected }
                    ping()
                    getBlocks()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error while connect", e)
                _current.update {
                    Result.failure(IPFSException.UnreachableException(e))
                }
                disconnect()
            }
        }
    }

    private fun createIdentity(): IdentitySection {
        val builder = HostBuilder().generateIdentity()
        val privateKey = builder.privateKey
        val peerId = builder.peerId
        val identity  = IdentitySection(privateKey.bytes(), peerId)
        return identity
    }

    override fun disconnect() {
        Log.d(LOG_TAG, "Disconnect is called")
        launch {
            mutex.withLock {
                _connectionFlow.update { ConnectionState.Progress }
                connectionJob?.cancel()
                listenJob?.cancel()
                pingJob?.cancel()
                ipfs?.stop()
                ipfs = null
                _pingFlow.update { null }
                _connectionFlow.update { ConnectionState.Disconnected }
            }
        }
    }

    override fun clear() {
        _current.update { null }
        _pingFlow.update { null }
    }

    override fun isConnected(): Boolean = _connectionFlow.value == ConnectionState.Connected

    private fun ping() {
        pingJob?.cancel()
        pingJob = launch {
            val address = Multiaddr(store.getAddress())
            var controller: PingController? = null
            while (isActive) {
                try {
                    if (controller == null) {
                        controller = getPingController(address)
                    }

                    val latency = controller!!.ping().await()
                    Log.d(LOG_TAG, "Ping is $latency ms")
                    _pingFlow.update {
                        latency.toInt()
                    }
                    delay(store.getPollingInterval())
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error while ping", e)
                    when {
                        e is PingTimeoutException || e is ConnectionClosedException -> {
                            controller = null
                            delay(INTERVAL_RESET_MS)
                        }
                        isActive -> {
                            _current.update {
                                Result.failure(IPFSException.Unknown(e))
                            }
                            disconnect()
                        }
                    }
                }
            }
        }
    }

    private suspend fun getPingController(address: Multiaddr): PingController? {
        val ipfs = ipfs ?: return null
        return ipfs.node.network
            .connect(address)
            .thenApply { it.muxerSession().createStream(Ping()) }
            .await()
            .controller
            .await()
    }

    private fun getBlocks() {
        listenJob?.cancel()
        listenJob = launch {
            try {
                val wants = listOf(Want(Cid.decode(store.getCid())))
                val blocks = ipfs?.getBlocks(wants, emptySet(), addToLocal)
                val result = blocks?.map { block ->
                    block
                        .block
                        .toString(Charsets.UTF_8)
                        .parseReadableMessage()
                }
                Log.d(LOG_TAG, "Received blocks: $result")
                _current.update {
                    Result.success(value = result?.joinToString().orEmpty())
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error while get blocks", e)
                when {
                    e is IllegalStateException -> {
                        _current.update {
                            Result.failure(IPFSException.WrongCIDException(e))
                        }
                    }
                    isActive -> {
                        _current.update {
                            Result.failure(IPFSException.Unknown(e))
                        }
                    }
                }
                disconnect()
            }

        }
    }

    private fun String.parseReadableMessage(): String {
        return filter { it.isLetterOrDigit() || it.isWhitespace() || it in COMMON_PUNCTUATION }
    }

    companion object {
        private const val LOG_TAG = "NodeX/IPFS"
        private const val COMMON_PUNCTUATION = ",.!?-()[]{}:;"
        private const val INTERVAL_RESET_MS = 1000L
    }

}
