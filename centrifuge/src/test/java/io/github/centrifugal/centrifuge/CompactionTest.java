package io.github.centrifugal.centrifuge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for channel compaction. The feature is Centrifugo PRO only, so it can't
 * be exercised against the OSS docker-compose server — this test uses an
 * in-process MockWebServer speaking the protobuf protocol which negotiates a
 * numeric channel ID in the subscribe reply and then sends pushes carrying the
 * ID instead of the channel name, exactly like the real server does when
 * compaction is enabled.
 */
public class CompactionTest {

    private MockWebServer server;
    private final AtomicReference<WebSocket> currentSocket = new AtomicReference<>();
    private final AtomicLong lastSubscribeFlag = new AtomicLong(0);
    private final AtomicLong nextChannelId = new AtomicLong(42);

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        Dispatcher dispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // Every connection (initial + reconnects) gets a fresh upgrade.
                return new MockResponse().withWebSocketUpgrade(new ServerListener());
            }
        };
        server.setDispatcher(dispatcher);
        server.start();
    }

    @After
    public void tearDown() {
        // Close the live server-side socket first so MockWebServer's reader thread
        // exits promptly.
        WebSocket s = currentSocket.get();
        if (s != null) {
            s.close(1000, "bye");
        }
        try {
            server.shutdown();
        } catch (IOException e) {
            // MockWebServer can time out waiting for WebSocket reader threads to
            // wind down even after the client disconnected. The test assertions
            // have already run, so this teardown hiccup is harmless.
        }
    }

    private String url() {
        return "ws://" + server.getHostName() + ":" + server.getPort() + "/connection/websocket";
    }

    private final class ServerListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            currentSocket.set(webSocket);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            InputStream stream = new ByteArrayInputStream(bytes.toByteArray());
            try {
                while (stream.available() > 0) {
                    Protocol.Command cmd = Protocol.Command.parseDelimitedFrom(stream);
                    if (cmd == null) {
                        break;
                    }
                    handleCommand(webSocket, cmd);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void handleCommand(WebSocket webSocket, Protocol.Command cmd) {
        if (cmd.hasConnect()) {
            send(webSocket, Protocol.Reply.newBuilder()
                    .setId(cmd.getId())
                    .setConnect(Protocol.ConnectResult.newBuilder()
                            .setClient("fake-client").setVersion("0.0.0").setPing(25).build())
                    .build());
        } else if (cmd.hasSubscribe()) {
            lastSubscribeFlag.set(cmd.getSubscribe().getFlag());
            Protocol.SubscribeResult.Builder result = Protocol.SubscribeResult.newBuilder();
            if ((cmd.getSubscribe().getFlag() & 1) != 0) {
                // Client offered channel compaction — assign a numeric channel ID.
                result.setId(nextChannelId.get());
            }
            send(webSocket, Protocol.Reply.newBuilder().setId(cmd.getId()).setSubscribe(result).build());
        } else if (cmd.hasUnsubscribe()) {
            send(webSocket, Protocol.Reply.newBuilder()
                    .setId(cmd.getId())
                    .setUnsubscribe(Protocol.UnsubscribeResult.newBuilder().build())
                    .build());
        } else if (cmd.getId() != 0) {
            // Reply to anything else with an empty result to avoid client timeouts.
            send(webSocket, Protocol.Reply.newBuilder().setId(cmd.getId()).build());
        }
    }

    private void send(WebSocket webSocket, Protocol.Reply reply) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            reply.writeDelimitedTo(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        webSocket.send(ByteString.of(out.toByteArray()));
    }

    private void sendCompactedPub(long id, byte[] data) {
        send(currentSocket.get(), Protocol.Reply.newBuilder()
                .setPush(Protocol.Push.newBuilder()
                        .setId(id)
                        .setPub(Protocol.Publication.newBuilder()
                                .setData(com.google.protobuf.ByteString.copyFrom(data)).build())
                        .build())
                .build());
    }

    private void sendCompactedJoin(long id, String client) {
        send(currentSocket.get(), Protocol.Reply.newBuilder()
                .setPush(Protocol.Push.newBuilder()
                        .setId(id)
                        .setJoin(Protocol.Join.newBuilder()
                                .setInfo(Protocol.ClientInfo.newBuilder().setClient(client).build()).build())
                        .build())
                .build());
    }

    private void sendCompactedLeave(long id, String client) {
        send(currentSocket.get(), Protocol.Reply.newBuilder()
                .setPush(Protocol.Push.newBuilder()
                        .setId(id)
                        .setLeave(Protocol.Leave.newBuilder()
                                .setInfo(Protocol.ClientInfo.newBuilder().setClient(client).build()).build())
                        .build())
                .build());
    }

    private Client newClient(LinkedBlockingQueue<SubscribedEvent> subscribedQ,
                             LinkedBlockingQueue<PublicationEvent> pubQ,
                             Subscription[] subOut, String channel) throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        Client client = new Client(url(), new Options(), new EventListener() {
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
                    (lastSubscribeFlag.get() & 1) != 0);

            sendCompactedPub(42, "{\"compacted\":true}".getBytes());
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
        Client client = new Client(url(), new Options(), new EventListener() {
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

            sendCompactedJoin(42, "other-client");
            JoinEvent join = poll(joinQ, "compacted join");
            assertEquals("other-client", join.getInfo().getClient());

            sendCompactedLeave(42, "other-client");
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
            sendCompactedPub(99, "{\"stray\":true}".getBytes());
            sendCompactedPub(42, "{\"ok\":true}".getBytes());

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
        Client client = new Client(url(), new Options(), new EventListener() {
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
            sendCompactedPub(42, "{\"stale\":true}".getBytes());

            // Resubscribe — the server assigns a fresh ID.
            nextChannelId.set(43);
            sub.subscribe();
            poll(subscribedQ, "resubscribed");

            sendCompactedPub(43, "{\"fresh\":true}".getBytes());
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
        Client client = new Client(url(), opts, new EventListener() {
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
            currentSocket.get().close(1000, "bye");
            poll(subscribedQ, "resubscribed after reconnect");

            sendCompactedPub(42, "{\"after_reconnect\":true}".getBytes());
            PublicationEvent pub = poll(pubQ, "publication after reconnect");
            assertEquals("{\"after_reconnect\":true}", new String(pub.getData()));
        } finally {
            client.close(1000);
        }
    }
}
