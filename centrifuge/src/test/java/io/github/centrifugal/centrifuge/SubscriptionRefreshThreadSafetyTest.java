package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for a thread-safety bug in {@link Subscription#sendRefresh()}:
 * only the call to the app-supplied {@link SubscriptionTokenGetter} was submitted
 * to the client's single-thread executor, not the continuation that runs once the
 * getter calls back. An async token getter (the normal case, since the interface
 * is callback-shaped) therefore ran the continuation - including
 * {@code Client.subRefreshSynchronized}, which touches unsynchronized shared state
 * like the command id counter - directly on whatever foreign thread invoked the
 * callback, racing with everything else the client does on its own executor.
 *
 * <p>The fix mirrors the existing pattern used everywhere else in this codebase
 * (e.g. {@code Subscription.continueSubscribe()}, {@code Client.sendRefresh()}):
 * resubmit the continuation onto the client's executor before touching shared state.
 */
public class SubscriptionRefreshThreadSafetyTest {

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

    @Test
    public void testRefreshTokenCallbackContinuationRunsOnClientExecutor() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch unauthorized = new CountDownLatch(1);
        final AtomicReference<Thread> tokenGetterCallerThread = new AtomicReference<>();
        final AtomicReference<Thread> unauthorizedHandlerThread = new AtomicReference<>();

        server.onSubscribe = (channel, req) ->
                Protocol.SubscribeResult.newBuilder().setExpires(true).setTtl(1).build();

        Options opts = new Options();
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();

        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setTokenGetter(new SubscriptionTokenGetter() {
            @Override
            public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                if (subscribed.getCount() > 0) {
                    // Initial subscribe: answer immediately with a valid token.
                    cb.Done(null, "sub-token");
                    return;
                }
                // Refresh: simulate an async token getter (e.g. a network call)
                // answering on a foreign thread with an empty token, which the
                // client must treat as unauthorized.
                Thread t = new Thread(() -> {
                    tokenGetterCallerThread.set(Thread.currentThread());
                    cb.Done(null, "");
                });
                t.start();
            }
        });
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) {
                subscribed.countDown();
            }

            @Override public void onUnsubscribed(Subscription s, UnsubscribedEvent e) {
                unauthorizedHandlerThread.set(Thread.currentThread());
                unauthorized.countDown();
            }
        });
        sub.subscribe();

        try {
            assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
            assertTrue("subscribed", subscribed.await(5, TimeUnit.SECONDS));

            // The 1s TTL above schedules a refresh shortly after subscribing; the
            // empty token it receives back drives the subscription to unsubscribed.
            assertTrue("unauthorized after refresh", unauthorized.await(5, TimeUnit.SECONDS));

            final AtomicReference<Thread> executorThread = new AtomicReference<>();
            final CountDownLatch probeRan = new CountDownLatch(1);
            client.getExecutor().submit(() -> {
                executorThread.set(Thread.currentThread());
                probeRan.countDown();
            });
            assertTrue("executor probe ran", probeRan.await(5, TimeUnit.SECONDS));

            assertNotSame("refresh continuation must not run on the foreign token-getter thread",
                    tokenGetterCallerThread.get(), unauthorizedHandlerThread.get());
            assertSame("refresh continuation must run on the client's single executor thread",
                    executorThread.get(), unauthorizedHandlerThread.get());
        } finally {
            client.disconnect();
        }
    }
}
