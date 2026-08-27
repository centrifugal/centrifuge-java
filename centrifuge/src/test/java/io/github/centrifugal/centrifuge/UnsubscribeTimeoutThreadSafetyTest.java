package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for the unsubscribe-timeout race: {@link java.util.concurrent.CompletableFuture#orTimeout}
 * fails the pending future from a JDK-internal thread, not the client's dedicated
 * single-thread executor. The {@code exceptionally} callback used to call
 * {@code processDisconnect} directly on that thread, racing with everything else
 * the client does on its own executor. The fix re-submits the callback onto the
 * client's executor (mirroring the existing subscribe-timeout handling).
 */
public class UnsubscribeTimeoutThreadSafetyTest {

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
    public void testUnsubscribeTimeoutRunsOnClientExecutor() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch timedOut = new CountDownLatch(1);
        final AtomicReference<Thread> callbackThread = new AtomicReference<>();

        // Swallow unsubscribe commands: send back a reply with no matching id so
        // the client's pending future is never completed and its timeout fires.
        server.onCommand = cmd -> cmd.hasUnsubscribe() ? Protocol.Reply.newBuilder().build() : null;

        Options opts = new Options();
        opts.setTimeout(100);
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }

            @Override
            public void onConnecting(Client c, ConnectingEvent e) {
                if (e.getCode() == Client.CONNECTING_UNSUBSCRIBE_ERROR) {
                    callbackThread.set(Thread.currentThread());
                    timedOut.countDown();
                }
            }
        });
        client.connect();

        Subscription sub = client.newSubscription("ch", new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                subscribed.countDown();
            }
        });
        sub.subscribe();

        try {
            assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
            assertTrue("subscribed", subscribed.await(5, TimeUnit.SECONDS));

            sub.unsubscribe();

            assertTrue("unsubscribe timeout should force a reconnect", timedOut.await(5, TimeUnit.SECONDS));

            final AtomicReference<Thread> executorThread = new AtomicReference<>();
            final CountDownLatch probeRan = new CountDownLatch(1);
            client.getExecutor().submit(() -> {
                executorThread.set(Thread.currentThread());
                probeRan.countDown();
            });
            assertTrue("executor probe ran", probeRan.await(5, TimeUnit.SECONDS));

            assertSame("unsubscribe timeout must run on the client's single executor thread, not a JDK timer thread",
                    executorThread.get(), callbackThread.get());
        } finally {
            client.disconnect();
        }
    }
}
