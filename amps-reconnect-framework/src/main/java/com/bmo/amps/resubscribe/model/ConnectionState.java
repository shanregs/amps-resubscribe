package com.bmo.amps.resubscribe.model;

/**
 * Recovery state machine for a single {@code AmpsClientContext}, as defined in DESIGN.md {@literal §}3.
 * All transitions are guarded compare-and-set operations performed by
 * {@code AmpsClientContext.transition(from, to)} — nothing outside that method assigns this
 * directly.
 *
 * <p><b>Revision:</b> the real {@code com.crankuptheamps.client.ConnectionStateListener} callback
 * has no distinct "reconnecting" notification (its states are {@code Disconnected}, {@code Shutdown},
 * {@code Connected}, {@code LoggedOn}, {@code PublishReplayed}, {@code HeartbeatInitiated},
 * {@code Resubscribed} — verified against the AMPS Java Client 5.3.3.3 sources). {@code WAITING}
 * therefore covers the entire "disconnected, AMPS retrying internally" period until the next
 * {@code Connected} callback arrives; there is no separate {@code RECONNECTING} state to observe.
 */
public enum ConnectionState {
    CREATED,
    CONNECTING,
    CONNECTED,
    LOGGED_ON,
    SUBSCRIBED,
    READING,
    DISCONNECTED,
    WAITING,
    RESUBSCRIBED,
    SHUTTING_DOWN,
    SHUTDOWN
}
