package com.bmo.amps.resubscribe.lifecycle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.bmo.amps.resubscribe.config.AmpsProperties;
import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.listener.AmpsConnectionListener;
import com.bmo.amps.resubscribe.manager.AmpsClientManager;
import com.bmo.amps.resubscribe.reader.AmpsMessageReader;

/**
 * Application-level start/stop orchestration (CALL_FLOW.md {@literal §}1, {@literal §}4). Delegates
 * everything else — never touches {@code HAClient} or threads directly.
 */
public class AmpsLifecycleManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AmpsLifecycleManager.class);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 15;

    private final AmpsProperties properties;
    private final AmpsClientManager manager;
    private final AmpsMessageReader reader;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AmpsLifecycleManager(AmpsProperties properties, AmpsClientManager manager, AmpsMessageReader reader) {
        this.properties = properties;
        this.manager = manager;
        this.reader = reader;
    }

    @Override
    public void start() {
        log.info("Starting AMPS lifecycle for {} client(s)", properties.clients().size());
        manager.initialize(properties.clients());

        for (AmpsClientContext context : manager.allContexts()) {
            AmpsConnectionListener listener = new AmpsConnectionListener(context, manager, reader);
            try {
                context.haClient().addConnectionStateListener(listener);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to register connection listener for " + context.clientType(), e);
            }
            manager.connect(context.clientType());
        }

        running.set(true);
    }

    @Override
    public void stop() {
        log.info("Stopping AMPS lifecycle");
        for (AmpsClientContext context : manager.allContexts()) {
            try {
                reader.stop(context);
            } catch (RuntimeException e) {
                log.warn("{} Error stopping reader during shutdown", context.clientType(), e);
            }
            try {
                manager.disconnect(context.clientType());
            } catch (RuntimeException e) {
                log.warn("{} Error disconnecting during shutdown", context.clientType(), e);
            }
            shutdownExecutor(context.clientType() + "-reader", context.readerExecutor());
            shutdownExecutor(context.clientType() + "-recovery", context.recoveryExecutor());
        }
        running.set(false);
    }

    private void shutdownExecutor(String label, ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{} Executor did not terminate within {}s, forcing shutdown",
                        label, SHUTDOWN_TIMEOUT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // start last, stop first
    }
}
