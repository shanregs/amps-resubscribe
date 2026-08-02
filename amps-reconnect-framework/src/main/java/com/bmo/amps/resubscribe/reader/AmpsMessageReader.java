package com.bmo.amps.resubscribe.reader;

import com.bmo.amps.resubscribe.context.AmpsClientContext;

/**
 * Owns reader threads (THREADING_MODEL.md {@literal §}1). The only class in the framework permitted
 * to submit work to a reader executor. Never manages {@code HAClient} connection state and never
 * decides on its own to restart — that decision belongs to {@code AmpsConnectionListener}.
 */
public interface AmpsMessageReader {

    /**
     * No-op if a reader is already running for this context (CAS-guarded, DESIGN.md {@literal §}5
     * rule 3). Submits a new task to {@code context.readerExecutor()} — never creates a new executor.
     */
    void start(AmpsClientContext context);

    /**
     * No-op if no reader is running. Otherwise signals the running reader to stop and blocks until
     * its thread has actually terminated before returning (THREADING_MODEL.md {@literal §}3) — never
     * just "signals and hopes".
     */
    void stop(AmpsClientContext context);

    boolean isRunning(AmpsClientContext context);
}
