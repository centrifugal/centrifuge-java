package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * An exception thrown by app code (a token getter, an event handler) never leaves the
 * client half-way through a step: it is reported, and the client either goes on or
 * stops visibly.
 */
public class AppExceptionTest {

    private FakeCentrifugoServer server;
    private Client client;
    private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

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

    private Options fastOptions() {
        Options opts = new Options();
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(100);
        return opts;
    }

    private String nextEvent(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String e = events.poll(100, TimeUnit.MILLISECONDS);
            if (e != null && e.startsWith(prefix)) {
                return e;
            }
        }
        return null;
    }

    /** A connection token getter that throws: a token error, then the next attempt connects. */
    @Test
    public void testThrowingConnectionTokenGetterRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Options opts = fastOptions();
        opts.setTokenGetter(new ConnectionTokenGetter() {
            @Override
            public void getConnectionToken(ConnectionTokenEvent event, TokenCallback cb) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("token backend down");
                }
                cb.Done(null, "token");
            }
        });
        client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                events.add("connected");
            }

            @Override
            public void onError(Client c, ErrorEvent e) {
                events.add("error:" + e.getError().getClass().getSimpleName());
            }
        });
        client.connect();
        assertEquals("error:TokenError", nextEvent("error:"));
        assertNotNull("connected on the next attempt", nextEvent("connected"));
        assertEquals(2, calls.get());
    }

    /**
     * A subscribed handler that throws stops the client. The position its event reported
     * is kept: the next subscribe recovers from it, not from an older position, which
     * delivered publications from before the subscription.
     */
    @Test
    public void testThrowingSubscribedHandlerKeepsThePosition() throws Exception {
        server.onSubscribe = (channel, req) -> io.github.centrifugal.centrifuge.internal.protocol.Protocol.SubscribeResult.newBuilder()
                .setRecoverable(true).setEpoch("e").setOffset(5).setRecovered(req.getRecover()).setWasRecovering(req.getRecover())
                .build();
        client = new Client(server.url(), fastOptions(), new EventListener() {
            @Override
            public void onDisconnected(Client c, DisconnectedEvent e) {
                events.add("disconnected:" + e.getCode());
            }
        });
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setRecoverable(true);
        AtomicInteger subscribed = new AtomicInteger();
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                events.add("subscribed");
                if (subscribed.incrementAndGet() == 1) {
                    throw new IllegalStateException("handler bug");
                }
            }
        });
        sub.subscribe();
        client.connect();
        assertNotNull(nextEvent("subscribed"));
        assertNotNull("the exception stops the client", nextEvent("disconnected:"));

        client.connect();
        assertNotNull("subscribed again", nextEvent("subscribed"));
        io.github.centrifugal.centrifuge.internal.protocol.Protocol.SubscribeRequest last = server.lastSubscribe();
        assertTrue("recovers", last.getRecover());
        assertEquals("from the position the subscribed event reported", 5, last.getOffset());
    }

    /** A subscription token getter that throws: a token error, then the resubscribe succeeds. */
    @Test
    public void testThrowingSubscriptionTokenGetterRetries() throws Exception {
        client = new Client(server.url(), fastOptions(), new EventListener() {});
        client.connect();
        AtomicInteger calls = new AtomicInteger();
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setMinResubscribeDelay(50);
        subOpts.setMaxResubscribeDelay(100);
        subOpts.setTokenGetter(new SubscriptionTokenGetter() {
            @Override
            public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("token backend down");
                }
                cb.Done(null, "sub-token");
            }
        });
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                events.add("subscribed");
            }

            @Override
            public void onError(Subscription s, SubscriptionErrorEvent e) {
                events.add("sub_error:" + e.getError().getClass().getSimpleName());
            }
        });
        sub.subscribe();
        assertEquals("sub_error:SubscriptionTokenError", nextEvent("sub_error:"));
        assertNotNull("subscribed after the retry", nextEvent("subscribed"));
    }

    /** A connecting handler that throws doesn't stop connect(): reported, and connected. */
    @Test
    public void testThrowingConnectingHandlerDoesNotStopConnect() throws Exception {
        client = new Client(server.url(), fastOptions(), new EventListener() {
            @Override
            public void onConnecting(Client c, ConnectingEvent e) {
                throw new IllegalStateException("handler bug");
            }

            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                events.add("connected");
            }

            @Override
            public void onError(Client c, ErrorEvent e) {
                events.add("error:" + e.getError().getClass().getSimpleName());
            }
        });
        client.connect();
        assertEquals("error:UnclassifiedError", nextEvent("error:"));
        assertNotNull("connected", nextEvent("connected"));
    }

    /**
     * A connected handler that throws while the connect reply is handled: reported, and the
     * client stops visibly (bad protocol) instead of staying connected without its
     * resubscribes and ping timer. connect() works again afterwards.
     */
    @Test
    public void testThrowingConnectedHandlerStopsVisibly() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        client = new Client(server.url(), fastOptions(), new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                events.add("connected");
                if (connects.incrementAndGet() == 1) {
                    throw new IllegalStateException("handler bug");
                }
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
        assertEquals("error:UnclassifiedError", nextEvent("error:"));
        assertEquals("disconnected:" + Client.DISCONNECTED_BAD_PROTOCOL, nextEvent("disconnected:"));
        client.connect();
        assertNotNull("connected again", nextEvent("connected"));
    }

    /**
     * A subscribing handler that throws during a reconnect teardown: every subscription still
     * moves to subscribing and resubscribes after the reconnect.
     */
    @Test
    public void testThrowingSubscribingHandlerInTeardownDoesNotStopOthers() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        client = new Client(server.url(), fastOptions(), new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }
        });
        client.connect();
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        LinkedBlockingQueue<String> subscribed = new LinkedBlockingQueue<>();
        Subscription[] subs = new Subscription[3];
        for (int i = 0; i < subs.length; i++) {
            final String name = "ch" + i;
            subs[i] = client.newSubscription(name, new SubscriptionEventListener() {
                @Override
                public void onSubscribed(Subscription s, SubscribedEvent e) {
                    subscribed.add(name);
                }

                @Override
                public void onSubscribing(Subscription s, SubscribingEvent e) {
                    if (e.getCode() == Client.SUBSCRIBING_TRANSPORT_CLOSED) {
                        throw new IllegalStateException("handler bug");
                    }
                }
            });
            subs[i].subscribe();
        }
        for (int i = 0; i < subs.length; i++) {
            assertNotNull("subscribed", subscribed.poll(5, TimeUnit.SECONDS));
        }
        server.disconnect(3001, "reconnect");
        for (int i = 0; i < subs.length; i++) {
            assertNotNull("resubscribed after the reconnect: " + i, subscribed.poll(5, TimeUnit.SECONDS));
        }
        for (Subscription s : subs) {
            assertEquals(SubscriptionState.SUBSCRIBED, s.getState());
        }
    }
}
