package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Commands that are still in flight when the connection goes away must not leave
 * their futures behind in the client's pending-command registry. Every disconnect
 * completes those futures exceptionally, so nothing is lost — but the entries
 * themselves have to go too, otherwise the registry grows by one entry per
 * in-flight command on every reconnect cycle for the lifetime of the client.
 */
public class PendingCommandCleanupTest {

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

    @SuppressWarnings("unchecked")
    private static Map<Integer, ?> pendingCommands(Client client) throws Exception {
        Field f = Client.class.getDeclaredField("futures");
        f.setAccessible(true);
        return (Map<Integer, ?>) f.get(client);
    }

    @Test
    public void testInFlightCommandsClearedOnDisconnect() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        CountDownLatch subscribedA = new CountDownLatch(1);
        CountDownLatch unsubscribeSent = new CountDownLatch(1);
        CountDownLatch subscribeBSent = new CountDownLatch(1);

        // Hold the unsubscribe for channel "a" and the subscribe for channel "b",
        // so both commands are still awaiting a reply when we disconnect.
        server.deferCommand = cmd -> {
            if (cmd.hasUnsubscribe()) {
                unsubscribeSent.countDown();
                return true;
            }
            if (cmd.hasSubscribe() && cmd.getSubscribe().getChannel().equals("b")) {
                subscribeBSent.countDown();
                return true;
            }
            return false;
        };

        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }

            @Override
            public void onDisconnected(Client c, DisconnectedEvent e) {
                disconnected.countDown();
            }
        });
        client.connect();
        try {
            assertTrue("connected", connected.await(5, TimeUnit.SECONDS));

            Subscription subA = client.newSubscription("a", new SubscriptionEventListener() {
                @Override
                public void onSubscribed(Subscription s, SubscribedEvent e) {
                    subscribedA.countDown();
                }
            });
            subA.subscribe();
            assertTrue("subscribed to a", subscribedA.await(5, TimeUnit.SECONDS));

            // Unsubscribe command for "a" is now in flight (reply held by the server).
            subA.unsubscribe();
            assertTrue("unsubscribe sent", unsubscribeSent.await(5, TimeUnit.SECONDS));

            // Subscribe command for "b" is now in flight too.
            Subscription subB = client.newSubscription("b", new SubscriptionEventListener() {});
            subB.subscribe();
            assertTrue("subscribe to b sent", subscribeBSent.await(5, TimeUnit.SECONDS));

            assertEquals("both commands pending before disconnect", 2, pendingCommands(client).size());

            client.disconnect();
            assertTrue("disconnected", disconnected.await(5, TimeUnit.SECONDS));

            // Cleanup of a pending command may be finished on the client executor,
            // so give it a moment to drain before asserting.
            Map<Integer, ?> pending = pendingCommands(client);
            for (int i = 0; i < 100 && !pending.isEmpty(); i++) {
                Thread.sleep(20);
            }
            assertEquals("no pending command futures left after disconnect: " + pending,
                    0, pending.size());
        } finally {
            client.close(1000);
        }
    }
}
