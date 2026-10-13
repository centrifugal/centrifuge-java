package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class RemoveSubscriptionTest {

    private FakeCentrifugoServer server;
    private Client client;

    @Before
    public void setUp() throws Exception {
        server = new FakeCentrifugoServer();
        server.start();
        CountDownLatch connected = new CountDownLatch(1);
        client = new Client(server.url(), new Options(), new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }
        });
        client.connect();
        connected.await(5, TimeUnit.SECONDS);
    }

    @After
    public void tearDown() throws InterruptedException {
        client.close(1000);
        server.stop();
    }

    private Subscription subscribed(String channel, LinkedBlockingQueue<String> events) throws Exception {
        Subscription sub = client.newSubscription(channel, new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                events.add("subscribed");
            }

            @Override
            public void onUnsubscribed(Subscription s, UnsubscribedEvent e) {
                events.add("unsubscribed");
            }
        });
        sub.subscribe();
        assertEquals("subscribed", events.poll(5, TimeUnit.SECONDS));
        return sub;
    }

    /**
     * removeSubscription() of a subscription removed before must not remove the newer
     * subscription to the same channel.
     */
    @Test
    public void testRemovingOldSubscriptionAgainKeepsNewOne() throws Exception {
        LinkedBlockingQueue<String> oldEvents = new LinkedBlockingQueue<>();
        Subscription old = subscribed("ch", oldEvents);
        client.removeSubscription(old);
        assertEquals("unsubscribed", oldEvents.poll(5, TimeUnit.SECONDS));

        LinkedBlockingQueue<String> newEvents = new LinkedBlockingQueue<>();
        Subscription fresh = subscribed("ch", newEvents);
        int commandsBefore = server.received().size();

        client.removeSubscription(old);
        Thread.sleep(300);
        assertSame("the new subscription stays registered", fresh, client.getSubscription("ch"));
        assertEquals(SubscriptionState.SUBSCRIBED, fresh.getState());
        for (Protocol.Command cmd : server.received().subList(commandsBefore, server.received().size())) {
            if (cmd.hasUnsubscribe()) {
                fail("no unsubscribe sent for the new subscription");
            }
        }
    }

    /** A removed subscription can't subscribe again: the client no longer tracks it. */
    @Test
    public void testSubscribeOnRemovedSubscriptionThrows() throws Exception {
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        Subscription sub = subscribed("ch", events);
        client.removeSubscription(sub);
        assertNotNull(events.poll(5, TimeUnit.SECONDS));
        try {
            sub.subscribe();
            fail("subscribe() on a removed subscription must throw");
        } catch (IllegalStateException expected) {
            // expected
        }
    }
}
