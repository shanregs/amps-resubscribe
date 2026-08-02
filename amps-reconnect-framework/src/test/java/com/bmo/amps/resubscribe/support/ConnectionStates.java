package com.bmo.amps.resubscribe.support;

import com.crankuptheamps.client.ConnectionStateListener;

/**
 * AMPS connection-state constants (plain {@code int}s on {@code ConnectionStateListener}, verified
 * against the AMPS Java Client 5.3.3.3 sources). There is no {@code Reconnecting} constant — AMPS
 * gives no distinct "still retrying" signal, only the next {@code Connected}/{@code Disconnected}.
 */
public final class ConnectionStates {

    private ConnectionStates() {
    }

    public static int connected() {
        return ConnectionStateListener.Connected;
    }

    public static int loggedOn() {
        return ConnectionStateListener.LoggedOn;
    }

    public static int disconnected() {
        return ConnectionStateListener.Disconnected;
    }
}
