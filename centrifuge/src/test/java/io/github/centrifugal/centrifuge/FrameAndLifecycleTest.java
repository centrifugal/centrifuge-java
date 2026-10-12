package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The rest of a frame after a teardown, retry timers that fire around unsubscribe(), and the
 * lifetime of a client that is disconnected but never closed.
 */
public class FrameAndLifecycleTest {

    private FakeCentrifugoServer server;
    private Client client;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws IOException {
        server = new FakeCentrifugoServer();
        server.start();
    }

    @After
    public void tearDown() throws InterruptedException {
        if (client != null) {
            client.close(1000);
        }
        server.stop();
    }

    private static Protocol.Reply disconnectPush(int code) {
        return Protocol.Reply.newBuilder().setPush(Protocol.Push.newBuilder()
                .setDisconnect(Protocol.Disconnect.newBuilder().setCode(code).setReason("reconnect").build()).build()).build();
    }

    private static final Protocol.Reply PING = Protocol.Reply.newBuilder().build();

    private Client connect(Options opts, CountDownLatch connectedTwice) throws InterruptedException {
        CountDownLatch connected = new CountDownLatch(1);
        client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                events.add("connected");
                connected.countDown();
                connectedTwice.countDown();
            }

            @Override
            public void onConnecting(Client c, ConnectingEvent e) {
                events.add("connecting:" + e.getCode());
            }

            @Override
            public void onDisconnected(Client c, DisconnectedEvent e) {
                events.add("disconnected:" + e.getCode());
            }

            @Override
            public void onError(Client c, ErrorEvent e) {
                events.add("error:" + e.getError().getClass().getSimpleName());
            }
        });
        client.connect();
        assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
        return client;
    }

    /**
     * A server ping after a disconnect push in the same frame belongs to the connection that
     * ended: it must not be answered (the transport is gone), and the client reconnects.
     */
    @Test
    public void testPingAfterDisconnectPushInSameFrameIsIgnored() throws Exception {
        server.connectResult = Protocol.ConnectResult.newBuilder()
                .setClient("fake-client").setVersion("0.0.0").setPing(25).setPong(true).build();
        Options opts = new Options();
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(100);
        CountDownLatch connectedTwice = new CountDownLatch(2);
        connect(opts, connectedTwice);
        server.sendFrame(disconnectPush(3001), PING);
        assertTrue("reconnected: " + events, connectedTwice.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(java.util.Arrays.asList("connecting:0", "connected", "connecting:3001", "connected"), events);
        assertEquals(ClientState.CONNECTED, client.getState());
    }

    /**
     * Same frame shape with pong off: the old connection's ping must not re-arm a no-ping timer
     * that later tears down the next, healthy connection.
     */
    @Test
    public void testPingAfterDisconnectPushInSameFrameLeavesNoStalePingTimer() throws Exception {
        server.connectResult = Protocol.ConnectResult.newBuilder()
                .setClient("fake-client").setVersion("0.0.0").setPing(1).build();
        Options opts = new Options();
        opts.setMinReconnectDelay(300);
        opts.setMaxReconnectDelay(400);
        opts.setMaxServerPingDelay(300);
        CountDownLatch connectedTwice = new CountDownLatch(2);
        connect(opts, connectedTwice);
        // The next connection pings rarely: only a stale timer could fire in the next seconds.
        server.connectResult = Protocol.ConnectResult.newBuilder()
                .setClient("fake-client").setVersion("0.0.0").setPing(25).build();
        server.sendFrame(disconnectPush(3001), PING);
        assertTrue("reconnected: " + events, connectedTwice.await(5, TimeUnit.SECONDS));
        Thread.sleep(2500);
        assertEquals(java.util.Arrays.asList("connecting:0", "connected", "connecting:3001", "connected"), events);
    }

    /**
     * A resubscribe retry timer that fires while the client thread is busy, after the app
     * called unsubscribe(): the retry belongs to an attempt that ended and must not subscribe.
     */
    @Test
    public void testRetryTimerAfterUnsubscribeDoesNotSubscribe() throws Exception {
        AtomicInteger subscribes = new AtomicInteger();
        server.onCommand = cmd -> cmd.hasSubscribe() && subscribes.incrementAndGet() == 1
                ? Protocol.Reply.newBuilder().setId(cmd.getId())
                    .setError(Protocol.Error.newBuilder().setCode(100).setMessage("internal").setTemporary(true).build()).build()
                : null;
        CountDownLatch handlerRunning = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        client = new Client(server.url(), new Options(), new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }

            @Override
            public void onMessage(Client c, MessageEvent e) {
                handlerRunning.countDown();
                try {
                    Thread.sleep(700);
                } catch (InterruptedException ignored) {
                }
            }
        });
        client.connect();
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        LinkedBlockingQueue<String> subEvents = new LinkedBlockingQueue<>();
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setMinResubscribeDelay(300);
        subOpts.setMaxResubscribeDelay(300);
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override
            public void onError(Subscription s, SubscriptionErrorEvent e) {
                subEvents.add("error");
            }
        });
        sub.subscribe();
        assertEquals("error", subEvents.poll(5, TimeUnit.SECONDS));
        // Hold the client thread: unsubscribe() is queued first, the retry timer fires behind it.
        server.sendPush(Protocol.Push.newBuilder().setMessage(Protocol.Message.newBuilder().build()).build());
        assertTrue(handlerRunning.await(5, TimeUnit.SECONDS));
        sub.unsubscribe();
        Thread.sleep(1500);
        List<String> wire = new java.util.ArrayList<>();
        for (Protocol.Command cmd : server.received()) {
            if (cmd.hasSubscribe()) {
                wire.add("subscribe");
            } else if (cmd.hasUnsubscribe()) {
                wire.add("unsubscribe");
            }
        }
        assertEquals("no subscribe after the app's unsubscribe", "unsubscribe", wire.get(wire.size() - 1));
        assertEquals(SubscriptionState.UNSUBSCRIBED, sub.getState());
    }

    /** A client that was connected, then disconnected but never closed, is garbage-collected. */
    @Test
    public void testDisconnectedClientIsCollected() throws Exception {
        WeakReference<Client> ref = connectAndDisconnect();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(100);
        }
        assertTrue("collected", ref.get() == null);
    }

    private WeakReference<Client> connectAndDisconnect() throws InterruptedException {
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        Client c = new Client(server.url(), new Options(), new EventListener() {
            @Override
            public void onConnected(Client cl, ConnectedEvent e) {
                connected.countDown();
            }

            @Override
            public void onDisconnected(Client cl, DisconnectedEvent e) {
                disconnected.countDown();
            }
        });
        c.connect();
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        c.disconnect();
        assertTrue(disconnected.await(5, TimeUnit.SECONDS));
        return new WeakReference<>(c);
    }
}
