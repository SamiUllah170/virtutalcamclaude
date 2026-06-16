package com.virtualcam

/**
 * Represents every possible state the stream connection can be in.
 * Flows through [StreamViewModel] → observed by [MainActivity] and [StreamService].
 */
sealed class StreamState {
    /** Initial / after explicit disconnect */
    object Disconnected : StreamState()

    /** TCP handshake in progress */
    object Connecting : StreamState()

    /**
     * WebSocket open, frames arriving.
     * @param latencyMs Round-trip latency derived from the 8-byte timestamp header.
     */
    data class Connected(val latencyMs: Long = 0L) : StreamState()

    /**
     * Unrecoverable error surfaced to the UI (auto-reconnect handles transient ones).
     */
    data class Error(val message: String) : StreamState()
}
