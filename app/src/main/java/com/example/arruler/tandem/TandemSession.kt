package com.example.arruler.tandem

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The typed layer over a [PeerChannel]: decodes control frames into [TandemMessage]s (hot [messages] flow, subscribe BEFORE you
 * send the request whose answer you wait for), keeps received files by tag (a file that arrives before anyone asks is kept), and
 * reports the end of the link through [closed]. Frames that do not decode are counted, never fatal.
 */
class TandemSession(val channel: PeerChannel, scope: CoroutineScope) {
    private val seq = AtomicLong()
    val messages = MutableSharedFlow<TandemMessage>(extraBufferCapacity = 1024)
    val fileProgress = MutableSharedFlow<PeerEvent.FileProgress>(extraBufferCapacity = 256)
    private val files = ConcurrentHashMap<String, CompletableDeferred<File>>()

    /** Completes with the reason when the link ends. */
    val closed = CompletableDeferred<String>()
    private val bad = AtomicInteger()
    val malformedFrames: Int get() = bad.get()
    val isOpen: Boolean get() = channel.isOpen && !closed.isCompleted

    private val pump: Job = scope.launch {
        try {
            channel.events.collect { e ->
                when (e) {
                    is PeerEvent.Message -> when (val d = TandemCodec.decode(e.bytes)) {
                        is Decoded.Ok -> messages.emit(d.msg)
                        is Decoded.UnsupportedVersion -> { bad.incrementAndGet(); messages.emit(TandemMessage.Error("version", "peer speaks protocol ${d.version}, this is ${TandemCodec.VERSION}")) }
                        is Decoded.Malformed -> bad.incrementAndGet()
                    }
                    is PeerEvent.FileProgress -> fileProgress.emit(e)
                    is PeerEvent.FileReceived -> files.getOrPut(e.tag) { CompletableDeferred() }.complete(e.file)
                    is PeerEvent.Disconnected -> end(e.reason)
                }
            }
        } finally {
            end("link ended")
        }
    }

    @Volatile private var closeReason = "closed"

    private fun end(reason: String) {
        if (!closed.isCompleted) closeReason = reason
        closed.complete(reason)
        files.values.forEach { it.completeExceptionally(PeerClosedException(reason)) }
    }

    suspend fun send(msg: TandemMessage) = channel.send(TandemCodec.encode(msg, seq.incrementAndGet()))

    suspend fun sendFile(tag: String, file: File, onProgress: (Float) -> Unit = {}) = channel.sendFile(tag, file, onProgress)

    /** The file the peer sent under [tag]; throws [PeerClosedException] if the link ends first. */
    suspend fun awaitFile(tag: String): File {
        val d = files.getOrPut(tag) { CompletableDeferred() }
        if (closed.isCompleted && !d.isCompleted) d.completeExceptionally(PeerClosedException(closeReason))
        return d.await()
    }

    fun forget(tag: String) { files.remove(tag) }

    fun close(reason: String = "closed") {
        channel.close(reason)
        end(reason)
        pump.cancel()
    }
}
