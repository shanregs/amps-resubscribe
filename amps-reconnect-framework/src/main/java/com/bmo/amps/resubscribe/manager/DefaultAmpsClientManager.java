package com.bmo.amps.resubscribe.manager;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.CommandId;
import com.crankuptheamps.client.DefaultServerChooser;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.Message;
import com.crankuptheamps.client.MessageStream;
import com.crankuptheamps.client.exception.AMPSException;

import com.bmo.amps.resubscribe.config.AmpsClientDefinition;
import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.model.ConnectionState;
import com.bmo.amps.resubscribe.util.AmpsFrameworkException;
import com.bmo.amps.resubscribe.util.ExecutorFactory;
import com.bmo.amps.resubscribe.util.ExponentialBackoffRetryPolicy;
import com.bmo.amps.resubscribe.util.RetryPolicy;

/**
 * The only class permitted to call {@code HAClient} methods (ARCHITECTURE.md {@literal §}1).
 *
 * <p><b>AMPS SDK surface used here</b> — verified against the AMPS Java Client 5.3.3.3 sources
 * (see {@code amps-client-5.3.3.3-sources.jar}); this class is the single, isolated place that
 * surface is exercised, so any future SDK version adjustment is a one-file change:
 * <ul>
 *   <li>{@code new HAClient(String name)}</li>
 *   <li>{@code haClient.setServerChooser(ServerChooser)} / {@code new DefaultServerChooser().add(uri)}
 *       — called once per configured URI, so failover candidates (e.g. primary + secondary) are all
 *       registered</li>
 *   <li>{@code haClient.connectAndLogon()} — throws {@code ConnectionException extends AMPSException}</li>
 *   <li>{@code haClient.execute(Command)} returning {@code MessageStream}</li>
 *   <li>{@code haClient.unsubscribe(CommandId)}</li>
 *   <li>{@code haClient.close()} — no checked exception</li>
 *   <li>{@code new Command("sow_and_subscribe").setTopic(...).setAckType(Message.AckType.Processed)
 *       .setCommandId(CommandId.nextIdentifier())}</li>
 * </ul>
 * Connection-state listener registration is done by {@code AmpsLifecycleManager}
 * (CALL_FLOW.md {@literal §}1 step 4), not here — this class only ever touches the client itself.
 */
public class DefaultAmpsClientManager implements AmpsClientManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultAmpsClientManager.class);

    private final Map<ClientType, AmpsClientContext> contexts = new ConcurrentHashMap<>();
    private final HAClientFactory haClientFactory;

    public DefaultAmpsClientManager() {
        this(DefaultAmpsClientManager::createDefaultHaClient);
    }

    /** Test seam — see {@link HAClientFactory}. */
    public DefaultAmpsClientManager(HAClientFactory haClientFactory) {
        this.haClientFactory = haClientFactory;
    }

    @Override
    public void initialize(List<AmpsClientDefinition> definitions) {
        for (AmpsClientDefinition definition : definitions) {
            ClientType type = definition.type();
            HAClient haClient = haClientFactory.create(definition);

            RetryPolicy retryPolicy = new ExponentialBackoffRetryPolicy(
                    definition.retry().initialDelay(),
                    definition.retry().maxDelay(),
                    definition.retry().multiplier());

            AmpsClientContext context = new AmpsClientContext(
                    type,
                    haClient,
                    definition,
                    retryPolicy,
                    ExecutorFactory.createSingleThreadExecutor(type, "reader"),
                    ExecutorFactory.createSingleThreadExecutor(type, "recovery"));

            contexts.put(type, context);
            log.info("{} Context initialized [uris={}, queue={}]", type, definition.uris(), definition.queue());
        }
    }

    private static HAClient createDefaultHaClient(AmpsClientDefinition definition) {
        HAClient haClient = new HAClient(definition.type().name());
        DefaultServerChooser serverChooser = new DefaultServerChooser();
        for (String uri : definition.uris()) {
            serverChooser.add(uri);
        }
        haClient.setServerChooser(serverChooser);
        return haClient;
    }

    @Override
    public void connect(ClientType type) {
        AmpsClientContext context = contextFor(type);
        if (!context.transition(ConnectionState.CREATED, ConnectionState.CONNECTING)) {
            log.debug("{} connect() ignored, not in CREATED state", type);
            return;
        }
        try {
            context.haClient().connectAndLogon();
        } catch (AMPSException e) {
            throw new AmpsFrameworkException("Failed to connect/logon " + type, e);
        }
    }

    @Override
    public void subscribe(ClientType type) {
        AmpsClientContext context = contextFor(type);
        if (context.subscriptionId() != null) {
            log.debug("{} subscribe() ignored, already subscribed [subscriptionId={}]",
                    type, context.subscriptionId());
            return;
        }
        doSubscribe(context);
        context.transition(ConnectionState.LOGGED_ON, ConnectionState.SUBSCRIBED);
    }

    @Override
    public void resubscribe(ClientType type) {
        AmpsClientContext context = contextFor(type);
        CommandId staleSubscriptionId = context.subscriptionId();

        // Unlimited retries with capped exponential backoff (DESIGN.md §6): a long-running service
        // must not give up processing a queue permanently just because one resubscribe attempt
        // raced the reconnect window.
        int attempt = 1;
        while (true) {
            try {
                doSubscribe(context);
                break;
            } catch (AmpsFrameworkException e) {
                log.warn("{} Resubscribe attempt {} failed, retrying: {}",
                        type, attempt, rootMessage(e));
                sleepUninterruptibly(context.retryPolicy().nextDelay(attempt));
                attempt++;
            }
        }

        if (staleSubscriptionId != null) {
            try {
                context.haClient().unsubscribe(staleSubscriptionId);
            } catch (AMPSException e) {
                log.debug("{} Unsubscribe of stale subscription {} failed (expected if the connection "
                        + "already dropped it server-side): {}", type, staleSubscriptionId, e.getMessage());
            }
        }

        log.info("{} Resubscribed [subscriptionId={}]", type, context.subscriptionId());
    }

    private void doSubscribe(AmpsClientContext context) {
        AmpsClientDefinition definition = context.definition();
        // Our own fresh id every call (DESIGN.md §5 rule 1's dedup check and rule 4's "always a new
        // id on resubscribe" both depend on subscriptionId being deterministic and never reused).
        CommandId commandId = CommandId.nextIdentifier();
        try {
            Command command = new Command("sow_and_subscribe")
                    .setTopic(definition.queue())
                    .setAckType(Message.AckType.Processed)
                    .setCommandId(commandId);
            MessageStream stream = context.haClient().execute(command);
            context.messageStream(stream);
            context.subscriptionId(commandId);
            log.info("{} Subscribed [subscriptionId={}]", context.clientType(), commandId);
        } catch (AMPSException e) {
            throw new AmpsFrameworkException("Subscribe failed for " + context.clientType(), e);
        }
    }

    @Override
    public void disconnect(ClientType type) {
        AmpsClientContext context = contextFor(type);
        context.forceState(ConnectionState.SHUTTING_DOWN);
        try {
            context.haClient().close();
        } catch (RuntimeException e) {
            log.warn("{} Error during disconnect", type, e);
        } finally {
            context.forceState(ConnectionState.SHUTDOWN);
        }
    }

    @Override
    public AmpsClientContext contextFor(ClientType type) {
        AmpsClientContext context = contexts.get(type);
        if (context == null) {
            throw new IllegalStateException("No context initialized for " + type);
        }
        return context;
    }

    @Override
    public Collection<AmpsClientContext> allContexts() {
        return contexts.values();
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t.getCause();
        return cause != null ? cause.getMessage() : t.getMessage();
    }

    private static void sleepUninterruptibly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
