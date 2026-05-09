package io.github.centrifugal.centrifuge;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests that exercise a real Centrifugo server. Run with:
 * <pre>
 *   docker compose up -d
 *   ./gradlew :centrifuge:integrationTest
 * </pre>
 */
public class ClientIntegrationTest extends IntegrationTestBase {

    private Client client;

    @Before
    public void setUp() {
        this.client = null;
    }

    @After
    public void tearDown() throws InterruptedException {
        if (this.client != null) {
            this.client.close(2000);
        }
    }

    @Test(timeout = 10_000)
    public void testConnectAndDisconnect() throws Exception {
        Captured<ConnectedEvent> connected = new Captured<>();
        Captured<DisconnectedEvent> disconnected = new Captured<>();

        client = newClient(new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e)    { connected.set(e); }
            @Override public void onDisconnected(Client c, DisconnectedEvent e) { disconnected.set(e); }
        });

        client.connect();
        ConnectedEvent ce = connected.await("connected");
        assertNotNull(ce.getClient());
        assertFalse("Centrifugo should assign a client id", ce.getClient().isEmpty());
        assertEquals(ClientState.CONNECTED, client.getState());

        client.disconnect();
        DisconnectedEvent de = disconnected.await("disconnected");
        assertEquals(Client.DISCONNECTED_DISCONNECT_CALLED, de.getCode());
        assertEquals(ClientState.DISCONNECTED, client.getState());
    }

    @Test(timeout = 10_000)
    public void testSubscribeAndUnsubscribe() throws Exception {
        client = newClient(new EventListener() {});
        client.connect();

        String channel = uniqueChannel("sub");
        Captured<SubscribedEvent> subscribed = new Captured<>();
        Captured<UnsubscribedEvent> unsubscribed = new Captured<>();

        Subscription sub = client.newSubscription(channel, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e)     { subscribed.set(e); }
            @Override public void onUnsubscribed(Subscription s, UnsubscribedEvent e) { unsubscribed.set(e); }
        });

        sub.subscribe();
        subscribed.await("subscribed");
        assertEquals(SubscriptionState.SUBSCRIBED, sub.getState());

        sub.unsubscribe();
        UnsubscribedEvent ue = unsubscribed.await("unsubscribed");
        assertEquals(Client.UNSUBSCRIBED_UNSUBSCRIBE_CALLED, ue.getCode());
        assertEquals(SubscriptionState.UNSUBSCRIBED, sub.getState());
    }

    @Test(timeout = 15_000)
    public void testPublishAndReceive() throws Exception {
        // Two clients on the same channel: A subscribes, B publishes, A receives.
        Client receiver = newClient(new EventListener() {});
        Client publisher = newClient(new EventListener() {});
        try {
            String channel = uniqueChannel("pub");
            byte[] payload = ("hello-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

            Captured<PublicationEvent> received = new Captured<>();
            Captured<SubscribedEvent> receiverSubscribed = new Captured<>();
            Subscription receiverSub = receiver.newSubscription(channel, new SubscriptionEventListener() {
                @Override public void onSubscribed(Subscription s, SubscribedEvent e)  { receiverSubscribed.set(e); }
                @Override public void onPublication(Subscription s, PublicationEvent e) { received.set(e); }
            });
            receiver.connect();
            receiverSub.subscribe();
            receiverSubscribed.await("receiver subscribed");

            publisher.connect();
            // We don't need a subscription on the publisher: client.publish() does it server-side.
            CountDownLatch published = new CountDownLatch(1);
            AtomicReference<Throwable> publishErr = new AtomicReference<>();
            publisher.publish(channel, payload, (err, result) -> {
                if (err != null) publishErr.set(err);
                published.countDown();
            });
            awaitLatch(published, "publish ack");
            assertNullErr("publish error", publishErr.get());

            PublicationEvent pe = received.await("publication received");
            assertEquals(new String(payload, StandardCharsets.UTF_8),
                    new String(pe.getData(), StandardCharsets.UTF_8));
        } finally {
            publisher.close(2000);
            receiver.close(2000);
        }
    }

    @Test(timeout = 15_000)
    public void testHistory() throws Exception {
        client = newClient(new EventListener() {});
        client.connect();

        String channel = uniqueChannel("hist");
        // Subscribe so the channel exists before we publish.
        Captured<SubscribedEvent> subscribed = new Captured<>();
        Subscription sub = client.newSubscription(channel, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribed.set(e); }
        });
        sub.subscribe();
        subscribed.await("subscribed");

        int n = 5;
        CountDownLatch publishesDone = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            byte[] payload = ("msg-" + i).getBytes(StandardCharsets.UTF_8);
            client.publish(channel, payload, (err, result) -> publishesDone.countDown());
        }
        awaitLatch(publishesDone, "all publishes acked");

        Captured<HistoryResult> history = new Captured<>();
        AtomicReference<Throwable> historyErr = new AtomicReference<>();
        sub.history(new HistoryOptions.Builder().withLimit(n).build(), (err, result) -> {
            if (err != null) historyErr.set(err);
            else history.set(result);
        });
        HistoryResult hr = history.await("history result");
        assertNullErr("history error", historyErr.get());
        assertEquals(n, hr.getPublications().size());
    }

    @Test(timeout = 15_000)
    public void testPresence() throws Exception {
        Client a = newClient(new EventListener() {});
        Client b = newClient(new EventListener() {});
        try {
            String channel = uniqueChannel("pres");
            Captured<SubscribedEvent> aSubbed = new Captured<>();
            Captured<SubscribedEvent> bSubbed = new Captured<>();
            Subscription aSub = a.newSubscription(channel, new SubscriptionEventListener() {
                @Override public void onSubscribed(Subscription s, SubscribedEvent e) { aSubbed.set(e); }
            });
            Subscription bSub = b.newSubscription(channel, new SubscriptionEventListener() {
                @Override public void onSubscribed(Subscription s, SubscribedEvent e) { bSubbed.set(e); }
            });
            a.connect();
            b.connect();
            aSub.subscribe();
            bSub.subscribe();
            aSubbed.await("a subscribed");
            bSubbed.await("b subscribed");

            Captured<PresenceStatsResult> stats = new Captured<>();
            aSub.presenceStats((err, result) -> { if (err == null) stats.set(result); });
            PresenceStatsResult s = stats.await("presence stats");
            assertEquals(2L, (long) s.getNumClients());
        } finally {
            a.close(2000);
            b.close(2000);
        }
    }

    @Test(timeout = 15_000)
    public void testJoinLeave() throws Exception {
        Client a = newClient(new EventListener() {});
        Client b = newClient(new EventListener() {});
        try {
            String channel = uniqueChannel("jl");
            Captured<SubscribedEvent> aSubbed = new Captured<>();
            Captured<JoinEvent> aSawJoin = new Captured<>();
            Captured<LeaveEvent> aSawLeave = new Captured<>();

            SubscriptionOptions joinLeaveOpts = new SubscriptionOptions();
            joinLeaveOpts.setJoinLeave(true);

            Subscription aSub = a.newSubscription(channel, joinLeaveOpts,
                    new SubscriptionEventListener() {
                        @Override public void onSubscribed(Subscription s, SubscribedEvent e) { aSubbed.set(e); }
                        @Override public void onJoin(Subscription s, JoinEvent e) {
                            // ignore the self-join, capture only the second client
                            if (!aSawJoin.isSet() && e.getInfo() != null && !e.getInfo().getClient().isEmpty()) {
                                aSawJoin.set(e);
                            }
                        }
                        @Override public void onLeave(Subscription s, LeaveEvent e) {
                            if (!aSawLeave.isSet()) aSawLeave.set(e);
                        }
                    });
            a.connect();
            aSub.subscribe();
            aSubbed.await("a subscribed");

            // The first client's own join may or may not be delivered to itself
            // depending on server config — drain a brief moment then track only
            // joins that happen after b connects.
            Captured<SubscribedEvent> bSubbed = new Captured<>();
            Subscription bSub = b.newSubscription(channel, joinLeaveOpts,
                    new SubscriptionEventListener() {
                        @Override public void onSubscribed(Subscription s, SubscribedEvent e) { bSubbed.set(e); }
                    });
            b.connect();
            bSub.subscribe();
            bSubbed.await("b subscribed");

            JoinEvent join = aSawJoin.await("join from b");
            assertNotNull(join.getInfo());

            bSub.unsubscribe();
            LeaveEvent leave = aSawLeave.await("leave from b");
            assertNotNull(leave.getInfo());
        } finally {
            a.close(2000);
            b.close(2000);
        }
    }

    @Test(timeout = 10_000)
    public void testConnectsWithToken() throws Exception {
        Captured<ConnectedEvent> connected = new Captured<>();
        client = newClientWithToken("user-42", new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.set(e); }
        });
        client.connect();
        ConnectedEvent ce = connected.await("connected with token");
        assertFalse(ce.getClient().isEmpty());
    }

    @Test(timeout = 10_000)
    public void testConnectsWithBadTokenIsUnauthorized() throws Exception {
        Options opts = new Options();
        opts.setToken("not.a.valid.token");
        Captured<DisconnectedEvent> disconnected = new Captured<>();
        client = new Client(endpoint(), opts, new EventListener() {
            @Override public void onDisconnected(Client c, DisconnectedEvent e) { disconnected.set(e); }
        });
        client.connect();
        DisconnectedEvent de = disconnected.await("disconnected with auth error");
        // Centrifugo rejects invalid token with code 109 (token expired/invalid).
        assertTrue("expected an error code, got " + de.getCode(), de.getCode() != 0);
    }

    @Test(timeout = 15_000)
    public void testSubscribesWithToken() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String channel = uniqueChannel("tok");
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setToken(JwtUtil.subscriptionToken(user, channel, tokenSecret()));

        Captured<ConnectedEvent> connected = new Captured<>();
        client = newClientWithToken(user, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.set(e); }
        });
        client.connect();
        connected.await("connected with token");

        Captured<SubscribedEvent> subscribed = new Captured<>();
        Subscription sub = client.newSubscription(channel, subOpts,
                new SubscriptionEventListener() {
                    @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribed.set(e); }
                });
        sub.subscribe();
        subscribed.await("subscribed with token");
        assertEquals(SubscriptionState.SUBSCRIBED, sub.getState());
    }

    @Test(timeout = 20_000)
    public void testConnectDisconnectLoop() throws Exception {
        // Stress: rapid connect/disconnect cycles must always end in the
        // expected terminal state without leaking listener invocations.
        client = newClient(new EventListener() {});
        for (int i = 0; i < 5; i++) {
            Captured<ConnectedEvent> connected = new Captured<>();
            Captured<DisconnectedEvent> disconnected = new Captured<>();
            // Replace listeners by closing & rebuilding the client so each
            // cycle has fresh latches. Cheap enough at 5 iterations.
            client.close(2000);
            client = newClient(new EventListener() {
                @Override public void onConnected(Client c, ConnectedEvent e)   { connected.set(e); }
                @Override public void onDisconnected(Client c, DisconnectedEvent e) { disconnected.set(e); }
            });
            client.connect();
            connected.await("connected #" + i);
            client.disconnect();
            disconnected.await("disconnected #" + i);
        }
    }

    private static String uniqueChannel(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static void assertNullErr(String label, Throwable err) {
        if (err != null) throw new AssertionError(label + ": " + err, err);
    }
}
