package com.example.nodex.domain.service.ipfs

import android.content.Context
import android.util.Log
import com.example.nodex.data.ConnectionState
import com.example.nodex.domain.datastore.Store
import io.ipfs.cid.Cid
import io.ipfs.multiaddr.MultiAddress
import io.ipfs.multibase.Charsets
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Host
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.peergos.BlockRequestAuthoriser
import org.peergos.EmbeddedIpfs
import org.peergos.HostBuilder
import org.peergos.Want
import org.peergos.blockstore.FileBlockstore
import org.peergos.config.IdentitySection
import org.peergos.protocol.dht.RamRecordStore
import org.peergos.protocol.http.HttpProtocol
import java.net.InetSocketAddress
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.Path

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
    private var listenJob: Job? = null

    private var pingHost: Host? = null
    private var pingJob: Job? = null

    private val _current = MutableStateFlow<Result<String>?>(null)
    override val current = _current.asStateFlow()

    private val _pingFlow = MutableStateFlow<Int?>(null)
    override val pingFlow = _pingFlow.asStateFlow()

    private val _connectionFlow = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionFlow = _connectionFlow.asStateFlow()

    val ipfsMutex = Mutex()
    val pingMutex = Mutex()

    override fun connect(context: Context) {
        Log.d(LOG_TAG, "Connect is called")
        launch {
            ipfsMutex.withLock {
                try {
                    _connectionFlow.update { ConnectionState.Progress }
                    _current.update { null }
                    val fileBlockStore = getFileBlockStore(context)
                    val bootstrapAddresses = listOf(MultiAddress(store.getAddress()))
                    val identity = createIdentity()
                    val httpProxyTarget = createHttpProxyTarget()

                    ipfs = EmbeddedIpfs.build(
                        RamRecordStore(),
                        fileBlockStore,
                        provideBlocks,
                        swarmAddresses,
                        bootstrapAddresses,
                        identity,
                        authoriser,
                        httpProxyTarget,
                    )
                    ipfs?.start()

                    _connectionFlow.update { ConnectionState.Connected }

                    ping()
                    listenBlocks()
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error while connect", e)
                    _current.update {
                        Result.failure(IPFSException.UnreachableException(e))
                    }
                    disconnect()
                }
            }
        }
    }

    override fun disconnect(clearResult: Boolean) {
        Log.d(LOG_TAG, "Disconnect is called")
        launch {
            _connectionFlow.update { ConnectionState.Progress }
            listenJob?.cancel()
            pingJob?.cancel()
            resetPingHostWithLock()
            resetIPFSWithLock()
            if (clearResult) {
                _current.update { null }
            }
            _pingFlow.update { null }
            _connectionFlow.update { ConnectionState.Disconnected }
        }
    }

    private suspend fun resetIPFSWithLock() {
        ipfsMutex.withLock {
            ipfs?.stop()
            ipfs = null
        }
    }

    override fun isConnected(): Boolean = _connectionFlow.value == ConnectionState.Connected

    private fun ping() {
        pingJob?.cancel()
        pingJob = launch {
            var controller = setPingController()
            while (isActive) {
                try {
                    val latency = controller!!.ping().get()
                    Log.d(LOG_TAG, "Ping is $latency ms")
                    _pingFlow.update {
                        latency.toInt()
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error while ping", e)
                    if (e is ExecutionException && e.cause is ConnectionClosedException) {
                        if (isActive) {
                            controller = setPingController()
                        }
                    } else {
                        if (e !is CancellationException) {
                            _current.update {
                                Result.failure(IPFSException.Unknown(e))
                            }
                        }
                        disconnect()
                    }
                }
                delay(store.getPollingInterval())
            }
        }
    }

    private suspend fun setPingController(): PingController? {
        pingMutex.withLock {
            try {
                resetPingHost()
                val ping = Ping()
                pingHost = host {
                    identity { random() }
                    protocols { add(ping) }
                    transports { add(::TcpTransport) }
                    secureChannels { add(::NoiseXXSecureChannel) }
                    muxers { add(StreamMuxerProtocol.getYamux()) }
                }
                pingHost?.start()?.join()
                val address = Multiaddr(store.getAddress())
                val controller = pingHost?.let {
                    ping.dial(it, address).controller.get()
                }
                return controller
            } catch (e: Exception) {
                Log.d(LOG_TAG, "Error while set ping host", e)
                return null
            }
        }
    }

    private suspend fun resetPingHostWithLock() {
        pingMutex.withLock {
            resetPingHost()
        }
    }

    private suspend fun resetPingHost() = withContext(Dispatchers.IO) {
        try {
            pingHost?.stop()
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Error while reset ping host", e)
        } finally {
            pingHost = null
        }
    }

    private fun listenBlocks() {
        listenJob?.cancel()
        listenJob = launch {
            while (isActive) {
                try {
                    val wants = listOf(Want(Cid.decode(store.getCid())))
                    val blocks = ipfsMutex.withLock {
                        ipfs?.getBlocks(wants, emptySet(), addToLocal)
                    }
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
                    when (e) {
                        is IllegalStateException -> {
                            _current.update {
                                Result.failure(IPFSException.WrongCIDException(e))
                            }
                        }
                        !is CancellationException -> {
                            _current.update {
                                Result.failure(IPFSException.Unknown(e))
                            }
                        }
                    }
                    disconnect()
                }
            }
        }
    }

    private fun getFileBlockStore(context: Context): FileBlockstore {
        return FileBlockstore(Path(context.getExternalFilesDir(null)!!.path))
    }

    private fun createIdentity(): IdentitySection {
        val builder = HostBuilder().generateIdentity()
        val privateKey = builder.privateKey
        val peerId = builder.peerId
        val identity  = IdentitySection(privateKey.bytes(), peerId)
        return identity
    }

    private fun createHttpProxyTarget(): Optional<HttpProtocol.HttpRequestProcessor> {
        val httpTarget = InetSocketAddress("localhost", 10000)
        val httpProxyTarget = Optional.of(
            HttpProtocol.HttpRequestProcessor { _, request, handler ->
                HttpProtocol.proxyRequest(request, httpTarget, handler)
            }
        )
        return httpProxyTarget
    }

    private fun String.parseReadableMessage(): String {
        return filter { it.isLetterOrDigit() || it.isWhitespace() || it in COMMON_PUNCTUATION }
    }

    companion object {
        private const val LOG_TAG = "NodeX/IPFS"
        private const val COMMON_PUNCTUATION = ",.!?-()[]{}:;"
    }

}
