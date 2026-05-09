package io.github.centrifugal.centrifuge;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Reconnect / re-subscribe behavior. Drives the server-side via Centrifugo's
 * HTTP API to disconnect or push messages while the client is offline.
 */
public class ReconnectIntegrationTest extends IntegrationTestBase {

    /** Reconnect-range code per the SDK's {@code code < 3500} rule. */
    private static final int CODE_DISCONNECT_RECONNECT = 3000;
    /** Non-reconnect-range code per the SDK's {@code code >= 3500 && code < 4000} rule. */
    private static final int CODE_DISCONNECT_TERMINAL = 3501;

    private CentrifugoApi api;
    private Client client;

    @Before
    public void setUp() {
        this.api = new CentrifugoApi();
        this.client = null;
    }

    @After
    public void tearDown() throws InterruptedException {
        if (this.client != null) this.client.close(2000);
    }

    // ----- helpers --------------------------------------------------------

    /** Tight backoff so tests don't sit waiting 500ms+ for each reconnect. */
    private Options fastReconnectOpts(String user) {
        Options opts = new Options();
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(500);
        opts.setToken(JwtUtil.connectionToken(user, tokenSecret()));
        return opts;
    }

    private SubscriptionOptions fastResubscribeOpts() {
        SubscriptionOptions o = new SubscriptionOptions();
        o.setMinResubscribeDelay(50);
        o.setMaxResubscribeDelay(500);
        return o;
    }

    // ----- tests ----------------------------------------------------------

    @Test(timeout = 15_000)
    public void testReconnectsAfterServerDisconnect() throws Exception {
        String user = "user-" + UUID.randomUUID();
        Captured<ConnectedEvent> firstConnected = new Captured<>();
        AtomicInteger connectingCount = new AtomicInteger();
        AtomicInteger connectedCount = new AtomicInteger();
        Captured<ConnectedEvent> reconnected = new Captured<>();

        client = new Client(endpoint(), fastReconnectOpts(user), new EventListener() {
            @Override public void onConnecting(Client c, ConnectingEvent e) { connectingCount.incrementAndGet(); }
            @Override public void onConnected(Client c, ConnectedEvent e) {
                connectedCount.incrementAndGet();
                if (!firstConnected.isSet()) firstConnected.set(e);
                else if (!reconnected.isSet()) reconnected.set(e);
            }
        });
        client.connect();
        ConnectedEvent first = firstConnected.await("first connect");

        api.disconnectUser(user, CODE_DISCONNECT_RECONNECT, "test");

        ConnectedEvent second = reconnected.await("reconnected");
        assertNotEquals("server should issue a fresh client id on reconnect",
                first.getClient(), second.getClient());
        assertEquals(ClientState.CONNECTED, client.getState());
        // 1 onConnecting from initial connect + 1 from disconnect (transport closed).
        assertTrue("got onConnecting events: " + connectingCount.get(),
                connectingCount.get() >= 2);
        assertEquals(2, connectedCount.get());
    }

    @Test(timeout = 15_000)
    public void testNoReconnectOnTerminalDisconnect() throws Exception {
        String user = "user-" + UUID.randomUUID();
        Captured<ConnectedEvent> connected = new Captured<>();
        Captured<DisconnectedEvent> disconnected = new Captured<>();

        client = new Client(endpoint(), fastReconnectOpts(user), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.set(e); }
            @Override public void onDisconnected(Client c, DisconnectedEvent e) { disconnected.set(e); }
        });
        client.connect();
        connected.await("connected");

        api.disconnectUser(user, CODE_DISCONNECT_TERMINAL, "go away");

        DisconnectedEvent de = disconnected.await("terminal disconnect");
        assertEquals(CODE_DISCONNECT_TERMINAL, de.getCode());
        assertEquals(ClientState.DISCONNECTED, client.getState());

        // Confirm we don't bounce back: brief settle then state should still be DISCONNECTED.
        Thread.sleep(800);
        assertEquals(ClientState.DISCONNECTED, client.getState());
    }

    @Test(timeout = 20_000)
    public void testSubscriptionRestoredAfterServerDisconnect() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String channel = "ch-" + UUID.randomUUID();

        Captured<ConnectedEvent> firstConnected = new Captured<>();
        AtomicInteger subscribedCount = new AtomicInteger();
        AtomicInteger subscribingCount = new AtomicInteger();
        CountDownLatch resubscribed = new CountDownLatch(2); // initial + post-reconnect
        ConcurrentLinkedQueue<String> publications = new ConcurrentLinkedQueue<>();

        client = new Client(endpoint(), fastReconnectOpts(user), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) {
                if (!firstConnected.isSet()) firstConnected.set(e);
            }
        });

        Subscription sub = client.newSubscription(channel, fastResubscribeOpts(),
                new SubscriptionEventListener() {
                    @Override public void onSubscribing(Subscription s, SubscribingEvent e) {
                        subscribingCount.incrementAndGet();
                    }
                    @Override public void onSubscribed(Subscription s, SubscribedEvent e) {
                        subscribedCount.incrementAndGet();
                        resubscribed.countDown();
                    }
                    @Override public void onPublication(Subscription s, PublicationEvent e) {
                        publications.add(new String(e.getData(), StandardCharsets.UTF_8));
                    }
                });

        client.connect();
        sub.subscribe();
        ConnectedEvent first = firstConnected.await("first connect");
        long deadline = System.currentTimeMillis() + 5000;
        while (subscribedCount.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, subscribedCount.get());

        api.disconnectUser(user, CODE_DISCONNECT_RECONNECT, "test");
        awaitLatch(resubscribed, "subscription restored after reconnect", 8000);
        assertEquals(SubscriptionState.SUBSCRIBED, sub.getState());

        // Server-side publish lands on the restored subscription.
        api.publish(channel, "after-reconnect");
        deadline = System.currentTimeMillis() + 5000;
        while (publications.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(Collections.singletonList("\"after-reconnect\""),
                Arrays.asList(publications.toArray()));
    }

    @Test(timeout = 30_000)
    public void testRecoversMissedPublications() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String channel = "ch-" + UUID.randomUUID();

        SubscriptionOptions subOpts = fastResubscribeOpts();
        subOpts.setRecoverable(true);
        subOpts.setPositioned(true);

        Captured<ConnectedEvent> firstConnected = new Captured<>();
        AtomicInteger subscribedCount = new AtomicInteger();
        ConcurrentLinkedQueue<String> publications = new ConcurrentLinkedQueue<>();
        CountDownLatch firstSub = new CountDownLatch(1);
        CountDownLatch secondSub = new CountDownLatch(1);

        client = new Client(endpoint(), fastReconnectOpts(user), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) {
                if (!firstConnected.isSet()) firstConnected.set(e);
            }
        });
        Subscription sub = client.newSubscription(channel, subOpts,
                new SubscriptionEventListener() {
                    @Override public void onSubscribed(Subscription s, SubscribedEvent e) {
                        int n = subscribedCount.incrementAndGet();
                        if (n == 1) firstSub.countDown();
                        else if (n == 2) secondSub.countDown();
                    }
                    @Override public void onPublication(Subscription s, PublicationEvent e) {
                        publications.add(new String(e.getData(), StandardCharsets.UTF_8));
                    }
                });
        client.connect();
        sub.subscribe();
        ConnectedEvent first = firstConnected.await("first connect");
        awaitLatch(firstSub, "first subscribe");

        // Drop the connection but require reconnect.
        api.disconnectUser(user, CODE_DISCONNECT_RECONNECT, "test");

        // Publish while the client is offline. Centrifugo retains these in
        // history (TTL 60s, size 10 per docker-compose.yml) — recovery on
        // reconnect should deliver them.
        api.publish(channel, "missed-1");
        api.publish(channel, "missed-2");
        api.publish(channel, "missed-3");

        awaitLatch(secondSub, "subscription restored", 10_000);

        // Allow a short moment for recovered publications to land.
        long deadline = System.currentTimeMillis() + 5000;
        while (publications.size() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(Arrays.asList("\"missed-1\"", "\"missed-2\"", "\"missed-3\""),
                Arrays.asList(publications.toArray()));
    }

    @Test(timeout = 20_000)
    public void testManySubscriptionsRestoredAfterReconnect() throws Exception {
        String user = "user-" + UUID.randomUUID();
        int n = 5;
        List<String> channels = Collections.unmodifiableList(Arrays.asList(
                "ch-a-" + UUID.randomUUID(),
                "ch-b-" + UUID.randomUUID(),
                "ch-c-" + UUID.randomUUID(),
                "ch-d-" + UUID.randomUUID(),
                "ch-e-" + UUID.randomUUID()));

        Captured<ConnectedEvent> firstConnected = new Captured<>();
        ConcurrentHashMap<String, AtomicInteger> subscribedCounts = new ConcurrentHashMap<>();
        for (String ch : channels) subscribedCounts.put(ch, new AtomicInteger());

        client = new Client(endpoint(), fastReconnectOpts(user), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) {
                if (!firstConnected.isSet()) firstConnected.set(e);
            }
        });
        for (String ch : channels) {
            client.newSubscription(ch, fastResubscribeOpts(), new SubscriptionEventListener() {
                @Override public void onSubscribed(Subscription s, SubscribedEvent e) {
                    subscribedCounts.get(s.getChannel()).incrementAndGet();
                }
            }).subscribe();
        }
        client.connect();
        ConnectedEvent first = firstConnected.await("first connect");

        // Wait for all initial subscribes.
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            boolean allSubscribed = subscribedCounts.values().stream().allMatch(c -> c.get() >= 1);
            if (allSubscribed) break;
            Thread.sleep(20);
        }
        for (String ch : channels) {
            assertEquals("initial subscribe missing for " + ch, 1, subscribedCounts.get(ch).get());
        }

        api.disconnectUser(user, CODE_DISCONNECT_RECONNECT, "test");

        // Wait for all subscriptions to restore.
        deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allRestored = subscribedCounts.values().stream().allMatch(c -> c.get() >= 2);
            if (allRestored) break;
            Thread.sleep(20);
        }
        for (String ch : channels) {
            assertEquals("subscription not restored for " + ch, 2, subscribedCounts.get(ch).get());
            assertEquals(SubscriptionState.SUBSCRIBED,
                    client.getSubscription(ch).getState());
        }
    }

    @Test(timeout = 15_000)
    public void testServerSideUnsubscribeTerminatesSubscription() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String channel = "ch-" + UUID.randomUUID();

        Captured<ConnectedEvent> connected = new Captured<>();
        Captured<SubscribedEvent> subscribed = new Captured<>();
        Captured<UnsubscribedEvent> unsubscribed = new Captured<>();

        client = new Client(endpoint(), fastReconnectOpts(user), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.set(e); }
        });
        Subscription sub = client.newSubscription(channel, fastResubscribeOpts(),
                new SubscriptionEventListener() {
                    @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribed.set(e); }
                    @Override public void onUnsubscribed(Subscription s, UnsubscribedEvent e) { unsubscribed.set(e); }
                });
        client.connect();
        connected.await("connected");
        sub.subscribe();
        subscribed.await("subscribed");

        api.unsubscribe(user, channel);

        UnsubscribedEvent ue = unsubscribed.await("server-side unsubscribe", 5000);
        assertEquals(SubscriptionState.UNSUBSCRIBED, sub.getState());
        assertTrue("server-issued unsubscribe code (got " + ue.getCode() + ")", ue.getCode() < 2500);
        // Client itself is still connected after a per-channel unsubscribe.
        assertEquals(ClientState.CONNECTED, client.getState());
    }

    @Test(timeout = 20_000)
    public void testPublishQueuedDuringReconnectIsDelivered() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String channel = "ch-" + UUID.randomUUID();

        Captured<ConnectedEvent> firstConnected = new Captured<>();
        AtomicInteger connectedCount = new AtomicInteger();
        AtomicReference<ConnectingEvent> firstConnecting = new AtomicReference<>();
        Captured<ConnectingEvent> serverConnecting = new Captured<>();

        client = new Client(endpoint(), fastReconnectOpts(user), new EventListener() {
            @Override public void onConnecting(Client c, ConnectingEvent e) {
                if (firstConnecting.compareAndSet(null, e)) return;
                if (!serverConnecting.isSet()) serverConnecting.set(e);
            }
            @Override public void onConnected(Client c, ConnectedEvent e) {
                connectedCount.incrementAndGet();
                if (!firstConnected.isSet()) firstConnected.set(e);
            }
        });
        client.connect();
        ConnectedEvent first = firstConnected.await("first connect");

        // Force a reconnect, then race a publish into the CONNECTING window.
        api.disconnectUser(user, CODE_DISCONNECT_RECONNECT, "test");
        ConnectingEvent reconnEv = serverConnecting.await("reconnect started", 5000);
        assertEquals(Client.CONNECTING_TRANSPORT_CLOSED, reconnEv.getCode());

        // At this point client.state is CONNECTING. publish() submits to the
        // executor; if it races in before the new ws is up the command is
        // queued in connectCommands and dispatched on reconnect.
        Captured<Throwable> publishErr = new Captured<>();
        client.publish(channel, "queued".getBytes(StandardCharsets.UTF_8),
                (err, result) -> publishErr.set(err));

        // The publish completes only after reconnect, so the latch waits ~backoff.
        Throwable err = publishErr.await("publish ack after reconnect", 8000);
        if (err != null) throw new AssertionError("publish failed: " + err, err);
        assertTrue("expected at least 2 connects (initial + reconnect), got " + connectedCount.get(),
                connectedCount.get() >= 2);
    }

    @Test(timeout = 15_000)
    public void testAutoReconnectFromInitialFailure() throws Exception {
        // Point at an unused port so connect() fails, then verify the SDK
        // keeps retrying with backoff. The SDK only fires onConnecting once
        // per CONNECTING phase, so we count repeated failures via onError
        // (one per failed transport attempt).
        Options opts = new Options();
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(200);

        AtomicInteger errorCount = new AtomicInteger();
        CountDownLatch attempts = new CountDownLatch(3);

        Client failClient = new Client("ws://127.0.0.1:1/connection/websocket", opts,
                new EventListener() {
                    @Override public void onError(Client c, ErrorEvent e) {
                        errorCount.incrementAndGet();
                        attempts.countDown();
                    }
                });
        try {
            failClient.connect();
            awaitLatch(attempts, "≥3 failed reconnect attempts on closed port", 10_000);
            assertTrue(errorCount.get() >= 3);
            assertFalse("client should not flip to CONNECTED while port is closed",
                    failClient.getState() == ClientState.CONNECTED);
        } finally {
            failClient.close(2000);
        }
    }
}
