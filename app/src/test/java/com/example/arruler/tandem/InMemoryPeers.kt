package com.example.arruler.tandem

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/** One end of an in-memory link; files are copied into the receiver's inbox like the Nearby implementation does. */
class InMemoryChannel(private val inbox: File, private val latencyMs: Long = 0) : PeerChannel {
    lateinit var other: InMemoryChannel
    private val q = Channel<PeerEvent>(Channel.UNLIMITED)

    @Volatile override var isOpen = true
        private set
    override val events: Flow<PeerEvent> = q.receiveAsFlow()

    /** Fault injection: when this returns true for a file tag the link dies instead of delivering it. */
    @Volatile var dropOnFile: (String) -> Boolean = { false }

    /** Fault injection: the link dies after this many control messages sent from this end. */
    @Volatile var dropAfterMessages: Int = Int.MAX_VALUE
    private var sentMessages = 0
    val filesSent = ArrayList<String>()

    override suspend fun send(bytes: ByteArray) {
        if (!isOpen || !other.isOpen) throw PeerClosedException()
        if (++sentMessages > dropAfterMessages) { close("injected drop"); throw PeerClosedException() }
        if (latencyMs > 0) delay(latencyMs)
        other.q.trySend(PeerEvent.Message(bytes.copyOf()))
    }

    override suspend fun sendFile(tag: String, file: File, onProgress: (Float) -> Unit) {
        if (!isOpen || !other.isOpen) throw PeerClosedException()
        if (dropOnFile(tag)) { close("injected drop on $tag"); throw PeerClosedException() }
        other.inbox.mkdirs()
        val dest = File(other.inbox, tag)
        file.copyTo(dest, overwrite = true)
        filesSent += tag
        onProgress(0.5f)
        other.q.trySend(PeerEvent.FileProgress(tag, 1f))
        other.q.trySend(PeerEvent.FileReceived(tag, dest))
        onProgress(1f)
    }

    override fun close(reason: String) {
        if (!isOpen) return
        isOpen = false
        q.trySend(PeerEvent.Disconnected(reason)); q.close()
        other.remoteClosed(reason)
    }

    private fun remoteClosed(reason: String) {
        if (!isOpen) return
        isOpen = false
        q.trySend(PeerEvent.Disconnected("peer closed: $reason")); q.close()
    }
}

object InMemoryPeers {
    fun pair(dir: File, latencyMs: Long = 0): Pair<InMemoryChannel, InMemoryChannel> {
        val a = InMemoryChannel(File(dir, "inbox_a"), latencyMs)
        val b = InMemoryChannel(File(dir, "inbox_b"), latencyMs)
        a.other = b; b.other = a
        return a to b
    }
}
