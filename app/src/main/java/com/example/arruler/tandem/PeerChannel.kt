package com.example.arruler.tandem

import kotlinx.coroutines.flow.Flow
import java.io.File

/** What a peer link reports upwards. */
sealed interface PeerEvent {
    /** A BYTES payload (control message). */
    class Message(val bytes: ByteArray) : PeerEvent

    /** Progress (0..1) of an incoming file; [tag] is the sender's name for it. */
    data class FileProgress(val tag: String, val fraction: Float) : PeerEvent

    /** A complete incoming file, already copied to app storage. */
    class FileReceived(val tag: String, val file: File) : PeerEvent

    /** The link is gone (peer left, radio dropped, closed locally). Always the last event. */
    data class Disconnected(val reason: String) : PeerEvent
}

/**
 * One established, authenticated connection to the other phone. BYTES messages are small control frames,
 * files carry blobs (clouds, meshes, ZIPs). The Nearby implementation is [NearbyPeerLink]; tests use an
 * in-memory pair, so everything above this interface is JVM-testable.
 */
interface PeerChannel {
    /** Single-consumer event stream; ends after [PeerEvent.Disconnected]. */
    val events: Flow<PeerEvent>
    val isOpen: Boolean

    /** Sends a control frame. Throws [PeerClosedException] when the link is gone. */
    suspend fun send(bytes: ByteArray)

    /** Sends [file] under [tag]; returns when it has been delivered. [onProgress] gets 0..1. */
    suspend fun sendFile(tag: String, file: File, onProgress: (Float) -> Unit = {})

    fun close(reason: String = "closed")
}

class PeerClosedException(message: String = "peer link closed") : Exception(message)
