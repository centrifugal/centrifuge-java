package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for "state invalidated" handling: unsubscribe code 2502 (per-subscription)
 * and disconnect code 3014 (connection-wide). On these the client drops cached
 * tokens (and recovery position / delta base) so a fresh token is obtained and
 * the subscription re-syncs. Private fields aren't visible to tests, so behavior
 * is asserted over the wire against the in-process {@link FakeCentrifugoServer}.
 */
public class StateInvalidationTest {

    private FakeCentrifugoServer server;

    @Before
    public void setUp() throws IOException {
        server = new FakeCentrifugoServer();
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    private Protocol.SubscribeRequest lastSubscribe() {
        return server.lastSubscribe();
    }

    private Protocol.ConnectRequest lastConnect() {
        Protocol.ConnectRequest last = null;
        for (Protocol.Command cmd : server.received()) {
            if (cmd.hasConnect()) {
                last = cmd.getConnect();
            }
        }
        return last;
    }

    @Test
    public void testUnsubscribe2502ClearsSubTokenAndResubscribes() throws Exception {
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        Options opts = new Options();
        opts.setMinReconnectDelay(50);
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();

        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setToken("sub-token-0");
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
        });
        sub.subscribe();
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertNotNull("subscribed", subscribedQ.poll(5, TimeUnit.SECONDS));
            assertEquals("initial subscribe carries the token", "sub-token-0", lastSubscribe().getToken());

            // Server sends "state invalidated" unsubscribe — the subscription must
            // drop its token and resubscribe with an empty token (no token getter
            // configured, so nothing repopulates it).
            server.unsubscribePush("ch", Client.UNSUBSCRIBED_STATE_INVALIDATED, "state invalidated");
            assertNotNull("resubscribed after 2502", subscribedQ.poll(5, TimeUnit.SECONDS));
            assertEquals("token cleared by 2502", "", lastSubscribe().getToken());
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testUnsubscribe2502RecoverableResubscribesUnrecovered() throws Exception {
        // A recoverable subscription must resubscribe REQUESTING recovery (recover
        // flag left true) but from the sentinel epoch "_" the server can't match,
        // so it gets wasRecovering=true, recovered=false.
        server.onSubscribe = (channel, req) -> Protocol.SubscribeResult.newBuilder()
                .setRecoverable(true).setEpoch("server-epoch").setOffset(5).build();

        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();
        Subscription sub = client.newSubscription("ch", new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
        });
        sub.subscribe();
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertNotNull("subscribed", subscribedQ.poll(5, TimeUnit.SECONDS));
            assertFalse("initial subscribe does not request recovery", lastSubscribe().getRecover());

            server.unsubscribePush("ch", Client.UNSUBSCRIBED_STATE_INVALIDATED, "state invalidated");
            assertNotNull("resubscribed after 2502", subscribedQ.poll(5, TimeUnit.SECONDS));

            Protocol.SubscribeRequest req = lastSubscribe();
            assertTrue("resubscribe requests recovery (recover left true)", req.getRecover());
            assertEquals("resubscribe carries the unrecoverable sentinel epoch", "_", req.getEpoch());
            assertEquals("resubscribe offset reset to 0", 0L, req.getOffset());
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testDisconnect3014ClearsConnTokenRefreshesAndInvalidatesSubs() throws Exception {
        LinkedBlockingQueue<ConnectedEvent> connectedQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        AtomicInteger connTokenCalls = new AtomicInteger();

        Options opts = new Options();
        opts.setToken("conn-token-0");
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(200);
        opts.setTokenGetter(new ConnectionTokenGetter() {
            @Override
            public void getConnectionToken(ConnectionTokenEvent e, TokenCallback cb) {
                connTokenCalls.incrementAndGet();
                cb.Done(null, "conn-token-1");
            }
        });
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connectedQ.add(e); }
        });
        client.connect();

        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setToken("sub-token-0");
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
        });
        sub.subscribe();
        try {
            assertNotNull("connected", connectedQ.poll(5, TimeUnit.SECONDS));
            assertNotNull("subscribed", subscribedQ.poll(5, TimeUnit.SECONDS));
            assertEquals("initial connect uses provided token", "conn-token-0", lastConnect().getToken());

            // Server sends "state invalidated" disconnect — the client must clear
            // its connection token, fetch a fresh one via the token getter on
            // reconnect, and invalidate the subscription (resubscribe w/o token).
            server.disconnect(Client.DISCONNECTED_STATE_INVALIDATED, "state invalidated");

            assertNotNull("reconnected after 3014", connectedQ.poll(5, TimeUnit.SECONDS));
            assertTrue("connection token getter called after 3014", connTokenCalls.get() >= 1);
            assertEquals("reconnect uses freshly fetched token", "conn-token-1", lastConnect().getToken());

            assertNotNull("resubscribed after 3014", subscribedQ.poll(5, TimeUnit.SECONDS));
            assertEquals("sub token cleared by 3014", "", lastSubscribe().getToken());
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testDisconnect3014ResetsServerSubRecoveryPosition() throws Exception {
        // Server-side subscriptions (pushed via the Connect reply's "subs" field,
        // tracked as ServerSubscription rather than Subscription) have no
        // invalidateState() of their own. A 3014 disconnect must still reset their
        // cached offset/epoch to the unrecoverable sentinel, or the next connect
        // request (see Client#sendConnect) resends the stale pre-invalidation
        // position and defeats the point of state invalidation.
        server.connectResult = Protocol.ConnectResult.newBuilder()
                .setClient("fake-client").setVersion("0.0.0").setPing(25)
                .putSubs("ch", Protocol.SubscribeResult.newBuilder()
                        .setRecoverable(true).setEpoch("server-epoch").setOffset(5).build())
                .build();

        LinkedBlockingQueue<ConnectedEvent> connectedQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ServerSubscribedEvent> serverSubscribedQ = new LinkedBlockingQueue<>();
        Options opts = new Options();
        opts.setToken("conn-token-0");
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(200);
        // A 3014 disconnect always sets refreshRequired, so a token getter is
        // needed for the client to be able to reconnect at all (mirrors the
        // connection-token test above).
        opts.setTokenGetter(new ConnectionTokenGetter() {
            @Override
            public void getConnectionToken(ConnectionTokenEvent e, TokenCallback cb) {
                cb.Done(null, "conn-token-1");
            }
        });
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connectedQ.add(e); }
            @Override public void onSubscribed(Client c, ServerSubscribedEvent e) { serverSubscribedQ.add(e); }
        });
        client.connect();
        try {
            assertNotNull("connected", connectedQ.poll(5, TimeUnit.SECONDS));
            assertNotNull("server sub established", serverSubscribedQ.poll(5, TimeUnit.SECONDS));

            // Server sends "state invalidated" disconnect — the cached server-side
            // subscription's recovery position must be reset to the sentinel before
            // the client reconnects.
            server.disconnect(Client.DISCONNECTED_STATE_INVALIDATED, "state invalidated");

            assertNotNull("reconnected after 3014", connectedQ.poll(5, TimeUnit.SECONDS));

            Protocol.ConnectRequest reconnect = lastConnect();
            assertTrue("reconnect resends the server sub", reconnect.containsSubs("ch"));
            Protocol.SubscribeRequest req = reconnect.getSubsMap().get("ch");
            assertTrue("recoverable left true", req.getRecover());
            assertEquals("offset reset to 0 by 3014", 0L, req.getOffset());
            assertEquals("epoch reset to sentinel by 3014", "_", req.getEpoch());
        } finally {
            client.close(1000);
        }
    }
}
