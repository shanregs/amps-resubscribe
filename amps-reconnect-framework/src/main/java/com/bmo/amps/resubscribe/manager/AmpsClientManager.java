package com.bmo.amps.resubscribe.manager;

import java.util.Collection;
import java.util.List;

import com.bmo.amps.resubscribe.config.AmpsClientDefinition;
import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.model.ClientType;

/**
 * Owns every {@code HAClient} instance (ARCHITECTURE.md {@literal §}1). The only class permitted to
 * call any {@code HAClient} method. Never creates threads, never reads messages, never sequences
 * recovery — that belongs to {@code AmpsConnectionListener} (ARCHITECTURE.md {@literal §}4).
 */
public interface AmpsClientManager {

    /**
     * Creates one {@link AmpsClientContext} per definition: builds the {@code HAClient}, its retry
     * policy, and its reader/recovery executors (THREADING_MODEL.md {@literal §}2). Called exactly
     * once, at startup.
     */
    void initialize(List<AmpsClientDefinition> definitions);

    /** {@code CREATED -> CONNECTING}, then {@code haClient.connectAndLogon()}. */
    void connect(ClientType type);

    /**
     * First-time subscribe. No-op if {@code context.subscriptionId()} is already set
     * (DESIGN.md {@literal §}5 rule 1) — use {@link #resubscribe(ClientType)} after a reconnect.
     */
    void subscribe(ClientType type);

    /**
     * Idempotent re-subscribe used only during recovery: issues a fresh subscribe command (retried
     * per {@code RetryPolicy} on transient NAK), replaces {@code context.subscriptionId()} and
     * {@code context.messageStream()} with the new ones, and best-effort unsubscribes the stale id.
     */
    void resubscribe(ClientType type);

    /** Unconditional disconnect used during shutdown, regardless of current state. */
    void disconnect(ClientType type);

    AmpsClientContext contextFor(ClientType type);

    Collection<AmpsClientContext> allContexts();
}
