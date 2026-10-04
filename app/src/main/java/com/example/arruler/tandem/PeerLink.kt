package com.example.arruler.tandem

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Where the pairing is. [Confirming] carries the digits both screens must show and the user must compare. */
sealed interface PeerPhase {
    data object Idle : PeerPhase
    data object Advertising : PeerPhase
    data object Discovering : PeerPhase
    data class Found(val endpointId: String, val name: String) : PeerPhase
    data class Confirming(val endpointId: String, val name: String, val digits: String, val incoming: Boolean) : PeerPhase
    data class Connected(val name: String) : PeerPhase
    data class Failed(val message: String) : PeerPhase
    data class Closed(val reason: String) : PeerPhase
}

/**
 * Nearby Connections link, [Strategy.P2P_POINT_TO_POINT] (exactly one peer; Nearby upgrades to Wi-Fi Direct /
 * hotspot for bandwidth on its own). Device-only: it needs Google Play services and the permissions of
 * [PeerPermissions], so it has no JVM test; everything above [PeerChannel] does.
 *
 * Flow: one phone calls [startAdvertising], the other [startDiscovery]; when the discoverer sees the endpoint
 * ([PeerPhase.Found]) it calls [connectTo]; both then reach [PeerPhase.Confirming] with the same
 * authentication digits, the user compares them and each side calls [confirm] (or [reject]); on success
 * both are [PeerPhase.Connected] and this object works as a [PeerChannel].
 *
 * Wire framing of BYTES payloads: byte 0 = 0 control frame (rest is the message), 1 = file header
 * `<payloadId>|<tag>` announcing the FILE payload that follows. Received files are copied into [inboxDir].
 */
class NearbyPeerLink(
    context: Context,
    private val localName: String,
    private val inboxDir: File,
    private val serviceId: String = SERVICE_ID,
) : PeerChannel {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val queue = Channel<PeerEvent>(Channel.UNLIMITED)
    private val _phase = MutableStateFlow<PeerPhase>(PeerPhase.Idle)
    val phase: StateFlow<PeerPhase> = _phase.asStateFlow()

    @Volatile private var endpoint: String? = null
    @Volatile private var open = false
    private val incomingTags = ConcurrentHashMap<Long, String>()
    private val incomingFiles = ConcurrentHashMap<Long, Payload>()
    private val outgoing = ConcurrentHashMap<Long, Pair<CompletableDeferred<Unit>, (Float) -> Unit>>()
    private val tagOfIncoming = ConcurrentHashMap<Long, String>()

    override val events: Flow<PeerEvent> = queue.receiveAsFlow()
    override val isOpen: Boolean get() = open

    private val strategy = Strategy.P2P_POINT_TO_POINT

    // ------------------------------------------------------------------ pairing

    fun startAdvertising() {
        val opts = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(localName, serviceId, lifecycle, opts)
            .addOnSuccessListener { _phase.value = PeerPhase.Advertising }
            .addOnFailureListener { _phase.value = PeerPhase.Failed("Advertising failed: ${it.message}") }
    }

    fun startDiscovery() {
        val opts = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discovery, opts)
            .addOnSuccessListener { _phase.value = PeerPhase.Discovering }
            .addOnFailureListener { _phase.value = PeerPhase.Failed("Discovery failed: ${it.message}") }
    }

    /** Discoverer side: asks the found endpoint to connect. */
    fun connectTo(endpointId: String) {
        client.requestConnection(localName, endpointId, lifecycle)
            .addOnFailureListener { _phase.value = PeerPhase.Failed("Connection request failed: ${it.message}") }
    }

    /** The user saw matching digits on both screens. */
    fun confirm() {
        val id = endpoint ?: return
        client.acceptConnection(id, payloads)
            .addOnFailureListener { _phase.value = PeerPhase.Failed("Accept failed: ${it.message}") }
    }

    /** The digits differ or the user declined. */
    fun reject() {
        val id = endpoint ?: return
        client.rejectConnection(id)
        _phase.value = PeerPhase.Idle
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (_phase.value is PeerPhase.Discovering) _phase.value = PeerPhase.Found(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            val p = _phase.value
            if (p is PeerPhase.Found && p.endpointId == endpointId) _phase.value = PeerPhase.Discovering
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpoint = endpointId
            _phase.value = PeerPhase.Confirming(endpointId, info.endpointName, info.authenticationDigits, info.isIncomingConnection)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                open = true
                client.stopAdvertising(); client.stopDiscovery()
                val name = (_phase.value as? PeerPhase.Confirming)?.name ?: "peer"
                _phase.value = PeerPhase.Connected(name)
            } else {
                endpoint = null
                _phase.value = PeerPhase.Failed("Connection refused (${result.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (open) finish("peer disconnected")
        }
    }

    // ------------------------------------------------------------------ payloads

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val b = payload.asBytes() ?: return
                    if (b.isEmpty()) return
                    if (b[0] == FRAME_CONTROL) queue.trySend(PeerEvent.Message(b.copyOfRange(1, b.size)))
                    else if (b[0] == FRAME_FILE_HEADER) {
                        val s = String(b, 1, b.size - 1, Charsets.UTF_8)
                        val bar = s.indexOf('|')
                        if (bar > 0) s.substring(0, bar).toLongOrNull()?.let { incomingTags[it] = s.substring(bar + 1) }
                    }
                }
                Payload.Type.FILE -> incomingFiles[payload.id] = payload
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val id = update.payloadId
            val frac = if (update.totalBytes > 0) (update.bytesTransferred.toFloat() / update.totalBytes).coerceIn(0f, 1f) else 0f
            val out = outgoing[id]
            if (out != null) {
                out.second(frac)
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> { outgoing.remove(id); out.first.complete(Unit) }
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                        outgoing.remove(id); out.first.completeExceptionally(PeerClosedException("file transfer failed"))
                    }
                    else -> Unit
                }
                return
            }
            val payload = incomingFiles[id] ?: return
            val tag = incomingTags[id] ?: tagOfIncoming.getOrPut(id) { "payload-$id" }
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> queue.trySend(PeerEvent.FileProgress(tag, frac))
                PayloadTransferUpdate.Status.SUCCESS -> {
                    incomingFiles.remove(id); incomingTags.remove(id); tagOfIncoming.remove(id)
                    val dest = File(inboxDir, safeName(tag))
                    runCatching {
                        inboxDir.mkdirs()
                        val pfd = payload.asFile()?.asParcelFileDescriptor() ?: error("no file descriptor")
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                    }.onSuccess { queue.trySend(PeerEvent.FileReceived(tag, dest)) }
                        .onFailure { finish("could not store received file: ${it.message}") }
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    incomingFiles.remove(id); incomingTags.remove(id)
                    finish("incoming file transfer failed")
                }
                else -> Unit
            }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        val id = endpoint
        if (!open || id == null) throw PeerClosedException()
        client.sendPayload(id, Payload.fromBytes(byteArrayOf(FRAME_CONTROL) + bytes))
    }

    override suspend fun sendFile(tag: String, file: File, onProgress: (Float) -> Unit) {
        val id = endpoint
        if (!open || id == null) throw PeerClosedException()
        val payload = Payload.fromFile(file)
        val done = CompletableDeferred<Unit>()
        outgoing[payload.id] = done to onProgress
        client.sendPayload(id, Payload.fromBytes(byteArrayOf(FRAME_FILE_HEADER) + "${payload.id}|$tag".toByteArray(Charsets.UTF_8)))
        client.sendPayload(id, payload)
        try {
            done.await()
        } finally {
            outgoing.remove(payload.id)
        }
    }

    override fun close(reason: String) {
        endpoint?.let { client.disconnectFromEndpoint(it) }
        finish(reason)
    }

    private fun finish(reason: String) {
        val wasOpen = open
        open = false
        endpoint = null
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        outgoing.values.forEach { it.first.completeExceptionally(PeerClosedException(reason)) }
        outgoing.clear()
        _phase.value = PeerPhase.Closed(reason)
        if (wasOpen) { queue.trySend(PeerEvent.Disconnected(reason)); queue.close() }
    }

    private fun safeName(tag: String) = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val SERVICE_ID = "com.example.arruler.tandem.v1"
        private const val FRAME_CONTROL: Byte = 0
        private const val FRAME_FILE_HEADER: Byte = 1
    }
}
