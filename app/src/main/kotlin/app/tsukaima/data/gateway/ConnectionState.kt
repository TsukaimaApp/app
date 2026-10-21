package app.tsukaima.data.gateway

/** Lifecycle of the chat WebSocket as seen by the UI. */
enum class ConnectionState {
    /** Not wanted (never connected, [GatewayClient.disconnect] called, or auth failed). */
    Disconnected,

    /** First attempt after [GatewayClient.connect]: fetching a ticket, upgrading, or awaiting `gateway.ready`. */
    Connecting,

    /** `gateway.ready` received on the current socket; requests flow. */
    Connected,

    /** The socket dropped while wanted; backing off and retrying with a fresh ticket. */
    Reconnecting,
}
