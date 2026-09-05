package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A subscribe reply may arrive after the subscription already left the SUBSCRIBING
 * state — most easily when unsubscribe() is called while the subscribe command is
 * still in flight. Such a reply is stale and must be dropped: the server has
 * already been told to unsubscribe, so acting on it would leave the subscription
 * permanently SUBSCRIBED with no pushes ever arriving, and would emit onSubscribed
 * after onUnsubscribed.
 */
public class StaleSubscribeReplyTest {

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
    public void testSubscribeReplyAfterUnsubscribeIsIgnored() throws Exception {
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<UnsubscribedEvent> unsubscribedQ = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch subscribeSent = new CountDownLatch(1);

        // Hold the subscribe reply so we can unsubscribe before it is delivered.
        server.deferCommand = cmd -> {
            if (cmd.hasSubscribe()) {
                subscribeSent.countDown();
                return true;
            }
            return false;
        };

        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }
        });
        client.connect();
        Subscription sub = client.newSubscription("test-channel", new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                subscribedQ.add(e);
            }

            @Override
            public void onUnsubscribed(Subscription s, UnsubscribedEvent e) {
                unsubscribedQ.add(e);
            }
        });
        try {
            assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
            sub.subscribe();
            assertTrue("subscribe command sent", subscribeSent.await(5, TimeUnit.SECONDS));

            sub.unsubscribe();
            UnsubscribedEvent unsubscribed = unsubscribedQ.poll(5, TimeUnit.SECONDS);
            assertNotNull("unsubscribed event", unsubscribed);
            assertEquals(SubscriptionState.UNSUBSCRIBED, sub.getState());

            // Now let the in-flight subscribe reply through.
            server.releaseDeferredCommands();

            assertNull("stale subscribe reply must not emit onSubscribed",
                    subscribedQ.poll(1, TimeUnit.SECONDS));
            assertEquals(SubscriptionState.UNSUBSCRIBED, sub.getState());
        } finally {
            client.close(1000);
        }
    }
}
