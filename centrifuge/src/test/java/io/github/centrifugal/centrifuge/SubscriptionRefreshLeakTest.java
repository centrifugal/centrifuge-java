package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests that leaving SUBSCRIBED for SUBSCRIBING cancels the pending
 * subscription token-refresh task, mirroring how it already resets the
 * resubscribe backoff step (see {@link ResubscribeBackoffTest}).
 *
 * <p>The server sends an insufficient-state unsubscribe (code 2500), which
 * pushes the subscription back to SUBSCRIBING and triggers an immediate
 * resubscribe. That second subscribe reply never expires, so any token-getter
 * call observed afterwards can only come from a stale refresh task tied to the
 * first (expiring) subscribe reply firing late.
 */
public class SubscriptionRefreshLeakTest {

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
    public void testRefreshTaskCancelledOnMoveToSubscribing() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch resubscribed = new CountDownLatch(1);
        final AtomicInteger subscribeCount = new AtomicInteger();
        final AtomicInteger tokenCalls = new AtomicInteger();

        server.onSubscribe = (channel, req) -> {
            int n = subscribeCount.incrementAndGet();
            if (n == 1) {
                // Short TTL: schedules a refresh task shortly after subscribing.
                return Protocol.SubscribeResult.newBuilder().setExpires(true).setTtl(1).build();
            }
            // The resubscribe triggered by the insufficient-state push below:
            // no TTL, so this reply schedules no refresh task of its own.
            return Protocol.SubscribeResult.getDefaultInstance();
        };

        Options opts = new Options();
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();

        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setTokenGetter(new SubscriptionTokenGetter() {
            @Override public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                tokenCalls.incrementAndGet();
                cb.Done(null, "sub-token");
            }
        });
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) {
                if (subscribed.getCount() == 0) {
                    resubscribed.countDown();
                } else {
                    subscribed.countDown();
                }
            }
        });
        sub.subscribe();
        try {
            assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
            assertTrue("subscribed", subscribed.await(5, TimeUnit.SECONDS));

            // Force the subscription back to SUBSCRIBING and immediately
            // resubscribing, the way a server-side insufficient-state push does.
            server.unsubscribePush("ch", 2500, "insufficient state");
            assertTrue("resubscribed", resubscribed.await(5, TimeUnit.SECONDS));
            int callsAfterResubscribe = tokenCalls.get();

            // Wait past the first subscribe reply's 1s TTL. The second reply never
            // expires, so any further token-getter call here can only be the stale
            // refresh task from the first reply firing late.
            Thread.sleep(1500);
            assertEquals("stale refresh task called the token getter again after resubscribe",
                    callsAfterResubscribe, tokenCalls.get());
        } finally {
            client.disconnect();
        }
    }
}
