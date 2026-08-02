package com.bmo.amps.resubscribe.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.HAClient;
import com.crankuptheamps.client.MessageStream;

import com.bmo.amps.resubscribe.context.AmpsClientContext;
import com.bmo.amps.resubscribe.listener.AmpsConnectionListener;
import com.bmo.amps.resubscribe.manager.AmpsClientManager;
import com.bmo.amps.resubscribe.manager.DefaultAmpsClientManager;
import com.bmo.amps.resubscribe.model.ClientType;
import com.bmo.amps.resubscribe.model.ConnectionState;
import com.bmo.amps.resubscribe.processor.MessageProcessor;
import com.bmo.amps.resubscribe.reader.AmpsMessageReader;
import com.bmo.amps.resubscribe.reader.DefaultAmpsMessageReader;

/**
 * Wires a real {@code DefaultAmpsClientManager} + {@code DefaultAmpsMessageReader} +
 * {@code AmpsConnectionListener} against a mocked {@code HAClient} so component-level tests
 * (MultiReconnectTests, ThreadLeakTests, DuplicateSubscriptionTests) can drive full startup and
 * disconnect/recovery cycles without a live AMPS server. AMPS gives no distinct "reconnecting"
 * callback, so a cycle here is simply Disconnected -> (await WAITING) -> Connected -> LoggedOn.
 */
public final class ReconnectCycleHarness {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(2);

    private final List<AmpsClientManager> managers = new ArrayList<>();

    public record ReadyClient(
            AmpsClientManager manager, AmpsClientContext context, AmpsConnectionListener listener,
            AmpsMessageReader reader, HAClient haClient) {
    }

    public ReadyClient startedClient(ClientType type) throws Exception {
        return startedClient(type, mock(HAClient.class));
    }

    public ReadyClient startedClient(ClientType type, HAClient haClient) throws Exception {
        when(haClient.execute(any(Command.class))).thenAnswer(invocation -> newBlockingStream());

        AmpsClientManager manager = new DefaultAmpsClientManager(definition -> haClient);
        managers.add(manager);
        manager.initialize(List.of(TestContextFactory.definition(type)));
        AmpsClientContext context = manager.contextFor(type);

        MessageProcessor processor = mock(MessageProcessor.class);
        AmpsMessageReader reader = new DefaultAmpsMessageReader(processor);
        AmpsConnectionListener listener = new AmpsConnectionListener(context, manager, reader);

        manager.connect(type);
        listener.connectionStateChanged(ConnectionStates.connected());
        listener.connectionStateChanged(ConnectionStates.loggedOn());
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(context.state()).isEqualTo(ConnectionState.READING));

        return new ReadyClient(manager, context, listener, reader, haClient);
    }

    public void runOneReconnectCycle(ReadyClient client) throws Exception {
        client.listener().connectionStateChanged(ConnectionStates.disconnected());
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(client.context().state()).isEqualTo(ConnectionState.WAITING));

        client.listener().connectionStateChanged(ConnectionStates.connected());
        client.listener().connectionStateChanged(ConnectionStates.loggedOn());
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(client.context().state()).isEqualTo(ConnectionState.READING));
    }

    public void cleanup() {
        for (AmpsClientManager manager : managers) {
            for (AmpsClientContext context : manager.allContexts()) {
                TestContextFactory.shutdown(context);
            }
        }
    }

    private static MessageStream newBlockingStream() {
        MessageStream stream = mock(MessageStream.class);
        AtomicBoolean closed = new AtomicBoolean(false);
        when(stream.hasNext()).thenAnswer(invocation -> {
            while (!closed.get()) {
                Thread.sleep(5);
            }
            return false;
        });
        doAnswer(invocation -> {
            closed.set(true);
            return null;
        }).when(stream).close();
        return stream;
    }
}
