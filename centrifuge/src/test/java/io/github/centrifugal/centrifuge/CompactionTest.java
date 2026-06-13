package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for channel compaction. The feature is Centrifugo PRO only, so it can't
 * be exercised against the OSS docker-compose server — these tests use the
 * in-process {@link FakeCentrifugoServer}: the subscribe reply negotiates a
 * numeric channel ID and subsequent pushes carry the ID instead of the channel
 * name, exactly like the real server does when compaction is enabled.
 */
public class CompactionTest {

    private FakeCentrifugoServer server;
    // Numeric channel id assigned on the next subscribe; a test can change it
    // before a resubscribe to exercise id refresh.
    private final AtomicLong nextChannelId = new AtomicLong(42);

    @Before
    public void setUp() throws IOException {
        server = new FakeCentrifugoServer();
        // Negotiate channel compaction: assign a numeric channel id whenever the
        // client offers the channelCompaction flag (bit 1).
        server.onSubscribe = (channel, req) -> {
            Protocol.SubscribeResult.Builder result = Protocol.SubscribeResult.newBuilder();
            if ((req.getFlag() & 1) != 0) {
                result.setId(nextChannelId.get());
            }
            return result.build();
        };
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    private Client newClient(LinkedBlockingQueue<SubscribedEvent> subscribedQ,
                             LinkedBlockingQueue<PublicationEvent> pubQ,
                             Subscription[] subOut, String channel) throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();
        Subscription sub = client.newSubscription(channel, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
            @Override public void onPublication(Subscription s, PublicationEvent e) { pubQ.add(e); }
        });
        subOut[0] = sub;
        sub.subscribe();
        assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
        return client;
    }

    private <T> T poll(LinkedBlockingQueue<T> q, String label) throws InterruptedException {
        T v = q.poll(5, TimeUnit.SECONDS);
        assertNotNull("timeout waiting for " + label, v);
        return v;
    }

    @Test
    public void testFlagOfferedAndPushesRoutedByID() throws Exception {
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<PublicationEvent> pubQ = new LinkedBlockingQueue<>();
        Subscription[] subOut = new Subscription[1];
        Client client = newClient(subscribedQ, pubQ, subOut, "compacted-channel");
        try {
            poll(subscribedQ, "subscribed");

            assertTrue("subscribe must offer compaction flag",
                    (server.lastSubscribe().getFlag() & 1) != 0);

            server.publishId(42, "{\"compacted\":true}".getBytes());
            PublicationEvent pub = poll(pubQ, "compacted publication");
            assertEquals("{\"compacted\":true}", new String(pub.getData()));
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testJoinLeaveRoutedByID() throws Exception {
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<JoinEvent> joinQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<LeaveEvent> leaveQ = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();
        Subscription sub = client.newSubscription("compacted-channel", new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
            @Override public void onJoin(Subscription s, JoinEvent e) { joinQ.add(e); }
            @Override public void onLeave(Subscription s, LeaveEvent e) { leaveQ.add(e); }
        });
        sub.subscribe();
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            poll(subscribedQ, "subscribed");

            server.joinId(42, "other-client");
            JoinEvent join = poll(joinQ, "compacted join");
            assertEquals("other-client", join.getInfo().getClient());

            server.leaveId(42, "other-client");
            LeaveEvent leave = poll(leaveQ, "compacted leave");
            assertEquals("other-client", leave.getInfo().getClient());
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testUnknownIDDropped() throws Exception {
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<PublicationEvent> pubQ = new LinkedBlockingQueue<>();
        Subscription[] subOut = new Subscription[1];
        Client client = newClient(subscribedQ, pubQ, subOut, "compacted-channel");
        try {
            poll(subscribedQ, "subscribed");

            // Unknown ID — must not be delivered. Known ID delivered after the stray
            // one (ordered same socket) proves the client processed and dropped it.
            server.publishId(99, "{\"stray\":true}".getBytes());
            server.publishId(42, "{\"ok\":true}".getBytes());

            PublicationEvent pub = poll(pubQ, "publication after stray push");
            assertEquals("{\"ok\":true}", new String(pub.getData()));
            assertNull("stray push with unknown id was delivered", pubQ.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testIDDroppedOnUnsubscribeRefreshedOnResubscribe() throws Exception {
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<PublicationEvent> pubQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<UnsubscribedEvent> unsubQ = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();
        Subscription sub = client.newSubscription("compacted-channel", new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
            @Override public void onPublication(Subscription s, PublicationEvent e) { pubQ.add(e); }
            @Override public void onUnsubscribed(Subscription s, UnsubscribedEvent e) { unsubQ.add(e); }
        });
        sub.subscribe();
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            poll(subscribedQ, "subscribed");

            sub.unsubscribe();
            poll(unsubQ, "unsubscribed");

            // Old ID must no longer route to the unsubscribed subscription.
            server.publishId(42, "{\"stale\":true}".getBytes());

            // Resubscribe — the server assigns a fresh ID.
            nextChannelId.set(43);
            sub.subscribe();
            poll(subscribedQ, "resubscribed");

            server.publishId(43, "{\"fresh\":true}".getBytes());
            PublicationEvent pub = poll(pubQ, "publication after resubscribe");
            assertEquals("{\"fresh\":true}", new String(pub.getData()));
            assertNull("stale push delivered after unsubscribe", pubQ.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testSameIDReRegisteredAfterReconnect() throws Exception {
        // Regression guard (found in the dart port): the client drops the ID
        // registry on teardown (IDs are server-session-scoped), and on reconnect
        // the server commonly assigns the SAME ID to the channel again. The
        // subscription must re-register it even though its own remembered ID is
        // unchanged.
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<PublicationEvent> pubQ = new LinkedBlockingQueue<>();
        CountDownLatch firstConnected = new CountDownLatch(1);
        // Tight reconnect backoff so the auto-reconnect after the server-side close
        // happens promptly.
        Options opts = new Options();
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(200);
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { firstConnected.countDown(); }
        });
        client.connect();
        Subscription sub = client.newSubscription("compacted-channel", new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
            @Override public void onPublication(Subscription s, PublicationEvent e) { pubQ.add(e); }
        });
        sub.subscribe();
        try {
            assertTrue(firstConnected.await(5, TimeUnit.SECONDS));
            poll(subscribedQ, "subscribed");

            // Server closes the connection — the client auto-reconnects (transport
            // closed → reconnect) and the subscription resubscribes, getting the
            // SAME channel ID 42 again from the fake server.
            server.closeConnection();
            poll(subscribedQ, "resubscribed after reconnect");

            server.publishId(42, "{\"after_reconnect\":true}".getBytes());
            PublicationEvent pub = poll(pubQ, "publication after reconnect");
            assertEquals("{\"after_reconnect\":true}", new String(pub.getData()));
        } finally {
            client.close(1000);
        }
    }
}
