package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A call settles exactly once, and a reply already received wins over its timeout. */
public class CallSettleTest {

    private FakeCentrifugoServer server;
    private Client client;
    private final LinkedBlockingQueue<String> errors = new LinkedBlockingQueue<>();

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

    private void connect(int timeoutMs, EventListener extra) throws InterruptedException {
        Options opts = new Options();
        opts.setTimeout(timeoutMs);
        CountDownLatch connected = new CountDownLatch(1);
        client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }

            @Override
            public void onError(Client c, ErrorEvent e) {
                errors.add(e.getError().getClass().getSimpleName());
            }

            @Override
            public void onMessage(Client c, MessageEvent e) {
                extra.onMessage(c, e);
            }
        });
        client.connect();
        assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
    }

    /** A result callback slower than the timeout is not followed by a timeout callback. */
    @Test
    public void testSlowCallbackIsCalledOnce() throws Exception {
        connect(300, new EventListener() {});
        List<String> calls = new CopyOnWriteArrayList<>();
        client.publish("ch", "x".getBytes(StandardCharsets.UTF_8), (err, result) -> {
            calls.add(err == null ? "ok" : err.getClass().getSimpleName());
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
        });
        Thread.sleep(1500);
        assertEquals(java.util.Collections.singletonList("ok"), calls);
    }

    /** A callback that throws is called once; the exception is reported through onError. */
    @Test
    public void testThrowingCallbackIsCalledOnce() throws Exception {
        connect(300, new EventListener() {});
        List<String> calls = new CopyOnWriteArrayList<>();
        client.publish("ch", "x".getBytes(StandardCharsets.UTF_8), (err, result) -> {
            calls.add(err == null ? "ok" : err.getClass().getSimpleName());
            throw new IllegalStateException("callback bug");
        });
        Thread.sleep(800);
        assertEquals(java.util.Collections.singletonList("ok"), calls);
        assertEquals("UnclassifiedError", errors.poll(1, TimeUnit.SECONDS));
    }

    /**
     * A reply received while a handler holds the executor (here past the call timeout) is
     * delivered: the timeout must not fail a call whose reply is already waiting.
     */
    @Test
    public void testReplyQueuedBehindBusyExecutorIsNotTimedOut() throws Exception {
        server.onCommand = cmd -> {
            if (cmd.hasPublish()) {
                // A push the client's handler is slow with, then the publish reply.
                server.sendPush(Protocol.Push.newBuilder()
                        .setMessage(Protocol.Message.newBuilder().build()).build());
                return Protocol.Reply.newBuilder().setId(cmd.getId())
                        .setPublish(Protocol.PublishResult.newBuilder().build()).build();
            }
            return null;
        };
        connect(500, new EventListener() {
            @Override
            public void onMessage(Client c, MessageEvent e) {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
            }
        });
        LinkedBlockingQueue<String> result = new LinkedBlockingQueue<>();
        client.publish("ch", "x".getBytes(StandardCharsets.UTF_8),
                (err, r) -> result.add(err == null ? "ok" : err.getClass().getSimpleName()));
        assertEquals("ok", result.poll(5, TimeUnit.SECONDS));
        assertNull("called once", result.poll(1, TimeUnit.SECONDS));
    }

    /** A call made while disconnected gets its callback when the client is closed. */
    @Test
    public void testCallWaitingForConnectionFailsOnClose() throws Exception {
        Options opts = new Options();
        opts.setTimeout(5000);
        client = new Client(server.url(), opts, new EventListener() {});
        LinkedBlockingQueue<String> result = new LinkedBlockingQueue<>();
        client.publish("ch", "x".getBytes(StandardCharsets.UTF_8),
                (err, r) -> result.add(err == null ? "ok" : "error"));
        Thread.sleep(100);
        client.close(1000);
        client = null;
        String outcome = result.poll(2, TimeUnit.SECONDS);
        assertNotNull("callback called after close()", outcome);
        assertEquals("error", outcome);
    }

    /**
     * A subscription call that times out (here a publish waiting for a subscribe that never
     * completes) gets its callback on the client's thread, like every other callback, not on
     * a timer thread where it would run concurrently with event handlers.
     */
    @Test
    public void testSubscriptionCallTimeoutCallbackRunsOnClientThread() throws Exception {
        server.deferCommand = cmd -> cmd.hasSubscribe();
        LinkedBlockingQueue<String> handlerThread = new LinkedBlockingQueue<>();
        connect(300, new EventListener() {});
        Subscription sub = client.newSubscription("ch", new SubscriptionEventListener() {
            @Override
            public void onSubscribing(Subscription s, SubscribingEvent e) {
                handlerThread.add(Thread.currentThread().getName());
            }
        });
        sub.subscribe();
        String clientThread = handlerThread.poll(5, TimeUnit.SECONDS);
        assertNotNull(clientThread);
        LinkedBlockingQueue<String> callbackThread = new LinkedBlockingQueue<>();
        sub.publish("x".getBytes(StandardCharsets.UTF_8), (err, r) -> callbackThread.add(Thread.currentThread().getName()));
        assertEquals(clientThread, callbackThread.poll(5, TimeUnit.SECONDS));
    }
}
