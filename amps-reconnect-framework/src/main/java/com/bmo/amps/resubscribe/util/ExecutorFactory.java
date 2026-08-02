package com.bmo.amps.resubscribe.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.bmo.amps.resubscribe.model.ClientType;

/**
 * Creates the single-thread, named, daemon executors that back reader and recovery threads
 * (THREADING_MODEL.md {@literal §}2, {@literal §}4). Called exactly twice per {@link ClientType} —
 * once for {@code "reader"}, once for {@code "recovery"} — during startup. Nothing else in the
 * framework is permitted to create a {@code Thread} or {@code ExecutorService} directly.
 */
public final class ExecutorFactory {

    private ExecutorFactory() {
    }

    public static ExecutorService createSingleThreadExecutor(ClientType clientType, String role) {
        String namePrefix = "amps-" + role + "-" + clientType.name();
        return Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory(namePrefix));
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger sequence = new AtomicInteger(1);

        private NamedDaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            // A single-thread executor only ever has one live thread at a time, but the executor
            // may create a replacement thread if the prior one dies unexpectedly (e.g. an uncaught
            // Error) — the suffix keeps replacement threads distinguishable in a thread dump.
            String name = namePrefix + "-" + sequence.getAndIncrement();
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
