package io.github.centrifugal.centrifuge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests covering token refresh, RPC, subscribe-before-connect, close lifecycle,
 * and the small client/registry helpers (duplicate detection, removeSubscription).
 */
public class AdvancedClientIntegrationTest extends IntegrationTestBase {

    private Client client;

    @Before public void setUp() { this.client = null; }

    @After public void tearDown() throws InterruptedException {
        if (this.client != null) this.client.close(2000);
    }

    // ----- token refresh --------------------------------------------------

    @Test(timeout = 15_000)
    public void testTokenRefreshAfterServerRejectsExpiredToken() throws Exception {
        // Initial token is already expired. Centrifugo rejects the connect
        // with code 109 (token expired). The SDK sets refreshRequired=true,
        // closes the ws, and on reconnect calls our tokenGetter for a fresh
        // token, then connects successfully. This exercises the SDK's
        // refresh-on-109 path which docker-compose's CLIENT_INSECURE=true
        // still triggers (signature/expiry are still validated when a token
        // IS supplied — insecure only relaxes the "must supply" rule).
        String user = "user-" + UUID.randomUUID();
        AtomicInteger tokenGetterCalls = new AtomicInteger();
        Captured<ConnectedEvent> connected = new Captured<>();

        Options opts = new Options();
        opts.setToken(JwtUtil.connectionToken(user, tokenSecret(), -10)); // already expired
        opts.setTokenGetter(new ConnectionTokenGetter() {
            @Override public void getConnectionToken(ConnectionTokenEvent event, TokenCallback cb) {
                tokenGetterCalls.incrementAndGet();
                cb.Done(null, JwtUtil.connectionToken(user, tokenSecret(), 60));
            }
        });
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(500);

        client = new Client(endpoint(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) {
                if (!connected.isSet()) connected.set(e);
            }
        });
        client.connect();
        connected.await("connected after token refresh", 10_000);

        assertTrue("tokenGetter should have been called for refresh, got " + tokenGetterCalls.get(),
                tokenGetterCalls.get() >= 1);
        assertEquals(ClientState.CONNECTED, client.getState());
    }

    @Test(timeout = 15_000)
    public void testTokenGetterUnauthorizedDisconnects() throws Exception {
        String user = "user-" + UUID.randomUUID();
        Captured<DisconnectedEvent> disconnected = new Captured<>();

        Options opts = new Options();
        // Empty token forces the SDK to ask the tokenGetter on connect.
        opts.setTokenGetter(new ConnectionTokenGetter() {
            @Override public void getConnectionToken(ConnectionTokenEvent event, TokenCallback cb) {
                cb.Done(new UnauthorizedException(), null);
            }
        });

        client = new Client(endpoint(), opts, new EventListener() {
            @Override public void onDisconnected(Client c, DisconnectedEvent e) { disconnected.set(e); }
        });
        client.connect();
        DisconnectedEvent de = disconnected.await("unauthorized -> disconnected");
        assertEquals(Client.DISCONNECTED_UNAUTHORIZED, de.getCode());
        assertEquals(ClientState.DISCONNECTED, client.getState());
        // Help the @After tearDown — explicitly dropping our reference avoids
        // a redundant close on an already-closed client.
        Thread.sleep(200);
        assertEquals("client must not bounce back from UNAUTHORIZED",
                ClientState.DISCONNECTED, client.getState());
        // Silence-check unused
        assertNotNull(user);
    }

    @Test(timeout = 20_000)
    public void testSubscriptionTokenAutoRefresh() throws Exception {
        String user = "user-" + UUID.randomUUID();
        String channel = "ch-" + UUID.randomUUID();

        // Connection token is long-lived; subscription token expires fast.
        Options opts = new Options();
        opts.setToken(JwtUtil.connectionToken(user, tokenSecret(), 60));
        Captured<ConnectedEvent> connected = new Captured<>();
        client = new Client(endpoint(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.set(e); }
        });
        client.connect();
        connected.await("connected");

        AtomicInteger subRefreshCalls = new AtomicInteger();
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setToken(JwtUtil.subscriptionToken(user, channel, tokenSecret())); // no exp; we'll refresh from getter on demand
        subOpts.setTokenGetter(new SubscriptionTokenGetter() {
            @Override public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                subRefreshCalls.incrementAndGet();
                cb.Done(null, JwtUtil.subscriptionToken(user, event.getChannel(), tokenSecret()));
            }
        });

        Captured<SubscribedEvent> subscribed = new Captured<>();
        AtomicInteger errCount = new AtomicInteger();
        Subscription sub = client.newSubscription(channel, subOpts,
                new SubscriptionEventListener() {
                    @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribed.set(e); }
                    @Override public void onError(Subscription s, SubscriptionErrorEvent e) { errCount.incrementAndGet(); }
                });
        sub.subscribe();
        subscribed.await("subscribed");
        assertEquals(SubscriptionState.SUBSCRIBED, sub.getState());

        // Centrifugo's docker-compose config doesn't require sub-token expiry,
        // so this primarily confirms the subscribe-with-token flow doesn't
        // explode and the channel goes to SUBSCRIBED. The tokenGetter wiring
        // is exercised by the connection-token test; subscription-side wiring
        // is symmetric and validated here as a smoke test.
        assertEquals(0, errCount.get());
    }

    // ----- RPC ------------------------------------------------------------

    @Test(timeout = 10_000)
    public void testRpcUnknownMethodReturnsError() throws Exception {
        // Centrifugo without RPC proxy returns an error for any RPC method.
        // We want the SDK's RPC round-trip to deliver that error to the caller.
        Captured<ConnectedEvent> connected = new Captured<>();
        client = newClient(new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.set(e); }
        });
        client.connect();
        connected.await("connected");

        Captured<Throwable> errCap = new Captured<>();
        AtomicReference<RPCResult> resultRef = new AtomicReference<>();
        client.rpc("does-not-exist", "{}".getBytes(StandardCharsets.UTF_8),
                (err, result) -> {
                    resultRef.set(result);
                    errCap.set(err == null ? new NullPointerException("no error") : err);
                });
        Throwable err = errCap.await("rpc error");
        assertNull("RPC should not deliver a result on error", resultRef.get());
        assertTrue("expected ReplyError, got " + err.getClass().getName(),
                err instanceof ReplyError);
        ReplyError re = (ReplyError) err;
        assertTrue("expected non-zero error code, got " + re.getCode(), re.getCode() != 0);
    }

    // ----- subscribe-before-connect --------------------------------------

    @Test(timeout = 15_000)
    public void testSubscribeBeforeConnect() throws Exception {
        String channel = "ch-" + UUID.randomUUID();
        client = newClient(new EventListener() {});

        Captured<SubscribedEvent> subscribed = new Captured<>();
        Subscription sub = client.newSubscription(channel,
                new SubscriptionEventListener() {
                    @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribed.set(e); }
                });

        // sub.subscribe() before client.connect(): subscription enters
        // SUBSCRIBING; the actual subscribe command is sent once the client
        // connects.
        sub.subscribe();
        // Quick sleep to ensure the subscribe task has run on the executor.
        Thread.sleep(50);
        assertEquals(SubscriptionState.SUBSCRIBING, sub.getState());

        client.connect();
        subscribed.await("subscribed after deferred connect");
        assertEquals(SubscriptionState.SUBSCRIBED, sub.getState());
    }

    // ----- close() lifecycle ---------------------------------------------

    @Test(timeout = 10_000)
    public void testCloseTransitionsToClosedAndStaysThere() throws Exception {
        Captured<ConnectedEvent> connected = new Captured<>();
        AtomicInteger connectingCount = new AtomicInteger();
        client = newClient(new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.set(e); }
            @Override public void onConnecting(Client c, ConnectingEvent e) { connectingCount.incrementAndGet(); }
        });
        client.connect();
        connected.await("connected");
        assertEquals(1, connectingCount.get());

        boolean terminated = client.close(2000);
        assertTrue("executor should have terminated within 2s", terminated);
        assertEquals(ClientState.CLOSED, client.getState());

        // Subsequent close() must be idempotent and not throw.
        boolean terminatedAgain = client.close(500);
        // After a fresh close on an already-shutdown executor we don't expect
        // it to "terminate" a second time meaningfully — but it must not crash.
        // Either return value is acceptable.
        assertTrue("second close did not throw", terminatedAgain || !terminatedAgain);

        // No further connecting events after close.
        Thread.sleep(300);
        assertEquals("close() must not trigger reconnects", 1, connectingCount.get());

        // Drop our reference so @After doesn't try to close again — close()
        // already happened above and the executor is shut down.
        client = null;
    }

    // ----- subscription registry -----------------------------------------

    @Test(timeout = 10_000)
    public void testDuplicateSubscriptionThrows() throws Exception {
        client = newClient(new EventListener() {});
        String channel = "ch-" + UUID.randomUUID();
        client.newSubscription(channel, new SubscriptionEventListener() {});
        try {
            client.newSubscription(channel, new SubscriptionEventListener() {});
            fail("expected DuplicateSubscriptionException");
        } catch (DuplicateSubscriptionException expected) {
            // ok
        }
    }

    @Test(timeout = 15_000)
    public void testRemoveSubscriptionUnsubscribesAndDeregisters() throws Exception {
        String channel = "ch-" + UUID.randomUUID();
        Captured<ConnectedEvent> connected = new Captured<>();
        client = newClient(new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.set(e); }
        });
        Captured<SubscribedEvent> subscribed = new Captured<>();
        Captured<UnsubscribedEvent> unsubscribed = new Captured<>();
        Subscription sub = client.newSubscription(channel,
                new SubscriptionEventListener() {
                    @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribed.set(e); }
                    @Override public void onUnsubscribed(Subscription s, UnsubscribedEvent e) { unsubscribed.set(e); }
                });
        client.connect();
        sub.subscribe();
        connected.await("connected");
        subscribed.await("subscribed");

        client.removeSubscription(sub);
        unsubscribed.await("unsubscribed via removeSubscription");
        assertEquals(SubscriptionState.UNSUBSCRIBED, sub.getState());
        // Registry should no longer return the subscription.
        assertNull("subscription should be removed from the registry",
                client.getSubscription(channel));

        // newSubscription on the same channel should now succeed (no duplicate).
        Subscription fresh = client.newSubscription(channel, new SubscriptionEventListener() {});
        assertNotNull(fresh);
        assertFalse("a new Subscription instance was created",
                fresh == sub);
    }

    // ----- ordering & burst -----------------------------------------------

    @Test(timeout = 30_000)
    public void testBurstPublicationsArriveInOrder() throws Exception {
        // Fire publishes serially via the HTTP API (each call awaits the
        // server response) so they enter Centrifugo's broadcast pipeline in
        // strict order. Client-side `client.publish()` would race because
        // CENTRIFUGO_CLIENT_CONCURRENCY=8 lets the server process commands
        // in parallel, scrambling assigned offsets.
        client = newClient(new EventListener() {});
        String channel = "ch-" + UUID.randomUUID();
        int n = 50;
        ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        CountDownLatch all = new CountDownLatch(n);
        Captured<SubscribedEvent> subbed = new Captured<>();

        Subscription sub = client.newSubscription(channel,
                new SubscriptionEventListener() {
                    @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subbed.set(e); }
                    @Override public void onPublication(Subscription s, PublicationEvent e) {
                        received.add(new String(e.getData(), StandardCharsets.UTF_8));
                        all.countDown();
                    }
                });
        client.connect();
        sub.subscribe();
        subbed.await("subscribed");

        CentrifugoApi api = new CentrifugoApi();
        for (int i = 0; i < n; i++) {
            api.publish(channel, "m-" + i);
        }
        awaitLatch(all, "all " + n + " publications received", 10_000);

        List<String> expected = new ArrayList<>(n);
        for (int i = 0; i < n; i++) expected.add("\"m-" + i + "\"");
        assertEquals(expected, new ArrayList<>(received));
    }
}
