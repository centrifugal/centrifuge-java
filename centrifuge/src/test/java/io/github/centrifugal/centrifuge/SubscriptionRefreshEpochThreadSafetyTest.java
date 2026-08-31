package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for a thread-safety bug in {@link Subscription#sendRefresh()}:
 * the {@code subscribeEpoch} field was bumped directly on whatever thread invoked
 * {@code sendRefresh()} - the client's scheduler thread, via
 * {@code client.getScheduler().schedule(Subscription.this::sendRefresh, ...)} - instead
 * of on the client's single-thread executor. Every other read/write of that field
 * (in {@code sendSubscribe()}, {@code continueSubscribe()}, and the callbacks inside
 * {@code sendRefresh()} itself) happens on the executor thread, so the stray write
 * could race with a concurrent (re)subscribe attempt and corrupt the epoch used to
 * detect stale async callbacks.
 *
 * <p>The fix mirrors {@code Client.sendRefresh()}: wrap the whole body of
 * {@code Subscription.sendRefresh()}, including the epoch bump, in
 * {@code client.getExecutor().submit(...)}.
 */
public class SubscriptionRefreshEpochThreadSafetyTest {

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
    public void testSendRefreshBumpsEpochOnClientExecutorNotSchedulerThread() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch executorBlockerRunning = new CountDownLatch(1);
        final CountDownLatch releaseExecutor = new CountDownLatch(1);

        server.onSubscribe = (channel, req) ->
                Protocol.SubscribeResult.newBuilder().setExpires(true).setTtl(1).build();

        Options opts = new Options();
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();

        // A token is set directly (not via the getter) so the initial subscribe
        // never touches subscribeEpoch - only the scheduled sendRefresh() does.
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setToken("initial-token");
        subOpts.setTokenGetter(new SubscriptionTokenGetter() {
            @Override
            public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                cb.Done(null, "refreshed-token");
            }
        });
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) {
                subscribed.countDown();
            }
        });
        sub.subscribe();

        try {
            assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
            assertTrue("subscribed", subscribed.await(5, TimeUnit.SECONDS));

            // Occupy the client's single executor thread before the 1s TTL elapses,
            // so any work correctly funneled through the executor cannot run until
            // we release it below.
            client.getExecutor().submit(() -> {
                executorBlockerRunning.countDown();
                try {
                    releaseExecutor.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });
            assertTrue("executor blocker running", executorBlockerRunning.await(5, TimeUnit.SECONDS));

            // Wait well past the 1s TTL so the scheduler has definitely fired
            // sendRefresh(). If the epoch bump were still happening on the scheduler
            // thread, it would already be visible here even though the executor is
            // blocked.
            Thread.sleep(2000);
            assertEquals("epoch must not change until the executor is free to process sendRefresh()",
                    0L, readSubscribeEpoch(sub));

            releaseExecutor.countDown();

            // Now the executor drains: first our blocker, then sendRefresh()'s body.
            long deadline = System.currentTimeMillis() + 5000;
            long epoch = readSubscribeEpoch(sub);
            while (epoch == 0L && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                epoch = readSubscribeEpoch(sub);
            }
            assertEquals("epoch must be bumped once the executor processes sendRefresh()", 1L, epoch);
        } finally {
            releaseExecutor.countDown();
            client.disconnect();
        }
    }

    private static long readSubscribeEpoch(Subscription sub) throws Exception {
        Field field = Subscription.class.getDeclaredField("subscribeEpoch");
        field.setAccessible(true);
        return field.getLong(sub);
    }
}
