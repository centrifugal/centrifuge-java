package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Tests that the resubscribe backoff step is reset once a subscription becomes
 * subscribed, so that a later failure starts retrying from minResubscribeDelay
 * again instead of continuing to grow from where the previous cycle left off.
 *
 * <p>Private fields aren't visible to tests, so this is asserted over the wire
 * against the in-process {@link FakeCentrifugoServer}: how many resubscribe
 * attempts the client manages within a fixed window after a successful
 * subscribe tells us which backoff step it is retrying from.
 */
public class ResubscribeBackoffTest {

    /** Failed subscribes used to drive the backoff step up before the successful one. */
    private static final int PRIMING_FAILURES = 11;
    /** Failed subscribes to observe after the successful one. */
    private static final int MEASURED_FAILURES = 7;
    /** Server unsubscribe code that sends a subscription back to subscribing. */
    private static final int INSUFFICIENT_STATE = 2500;

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

    private static Protocol.Reply temporaryError(int id) {
        return Protocol.Reply.newBuilder()
                .setId(id)
                .setError(Protocol.Error.newBuilder()
                        .setCode(108)
                        .setMessage("not available")
                        .setTemporary(true)
                        .build())
                .build();
    }

    @Test
    public void testBackoffResetsAfterSuccessfulSubscribe() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch measured = new CountDownLatch(MEASURED_FAILURES);
        final AtomicInteger subscribes = new AtomicInteger();
        final AtomicReference<Throwable> unexpected = new AtomicReference<>();

        server.onCommand = cmd -> {
            if (!cmd.hasSubscribe()) {
                return null;
            }
            int n = subscribes.incrementAndGet();
            if (n == PRIMING_FAILURES + 1) {
                // The single subscribe we let succeed: fall through to the
                // server's default (empty) subscribe result.
                return null;
            }
            if (n > PRIMING_FAILURES + 1) {
                measured.countDown();
            }
            return temporaryError(cmd.getId());
        };

        Options opts = new Options();
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();

        SubscriptionOptions subOpts = new SubscriptionOptions();
        // A 1ms floor keeps the priming failures cheap, while the default 20s
        // ceiling leaves plenty of room for the backoff step to grow into.
        subOpts.setMinResubscribeDelay(1);
        subOpts.setMaxResubscribeDelay(20000);
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribed.countDown(); }
            @Override public void onError(Subscription s, SubscriptionErrorEvent e) {
                // Temporary subscribe errors are expected here, nothing else is.
                if (!(e.getError() instanceof SubscriptionSubscribeError)) {
                    unexpected.set(e.getError());
                }
            }
        });
        sub.subscribe();
        try {
            assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
            assertTrue("subscribed after " + PRIMING_FAILURES + " failed attempts",
                    subscribed.await(10, TimeUnit.SECONDS));

            // Send the subscription back to subscribing so it starts a fresh
            // resubscribe cycle against a server that now fails every attempt.
            server.unsubscribePush("ch", INSUFFICIENT_STATE, "insufficient state");

            // With the step reset by the successful subscribe these retries are
            // spaced by at most 1 + 2^k ms for k = 0..5, so they all land well
            // inside the window. Without the reset the step carries on from 11
            // and the very first retry alone averages seconds.
            assertTrue(MEASURED_FAILURES + " resubscribes should follow quickly, saw "
                            + (MEASURED_FAILURES - measured.getCount()),
                    measured.await(3, TimeUnit.SECONDS));
            assertTrue("unexpected error: " + unexpected.get(), unexpected.get() == null);
        } finally {
            client.disconnect();
        }
    }
}
