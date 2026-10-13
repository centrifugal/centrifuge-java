package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/**
 * Pushes are applied only to a subscription in the state they are for.
 */
public class PushStateTest {

    private FakeCentrifugoServer server;
    private Client client;
    private final LinkedBlockingQueue<String> clientEvents = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<String> subEvents = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws IOException {
        server = new FakeCentrifugoServer();
    }

    @After
    public void tearDown() throws InterruptedException {
        if (client != null) {
            client.close(1000);
        }
        server.stop();
    }

    private void connect() throws Exception {
        server.start();
        CountDownLatch connected = new CountDownLatch(1);
        client = new Client(server.url(), new Options(), new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }

            @Override
            public void onConnecting(Client c, ConnectingEvent e) {
                clientEvents.add("connecting:" + e.getCode());
            }

            @Override
            public void onPublication(Client c, ServerPublicationEvent e) {
                clientEvents.add("server_publication:" + e.getChannel());
            }
        });
        client.connect();
        assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
        // Only the events after the first connect count.
        clientEvents.clear();
    }

    private Subscription newSub(String channel) throws Exception {
        return client.newSubscription(channel, new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                subEvents.add("subscribed");
            }

            @Override
            public void onSubscribing(Subscription s, SubscribingEvent e) {
                subEvents.add("subscribing:" + e.getCode());
            }

            @Override
            public void onUnsubscribed(Subscription s, UnsubscribedEvent e) {
                subEvents.add("unsubscribed:" + e.getCode());
            }

            @Override
            public void onPublication(Subscription s, PublicationEvent e) {
                subEvents.add("publication:" + new String(e.getData(), StandardCharsets.UTF_8));
            }
        });
    }

    private Subscription subscribedThenUnsubscribed(String channel) throws Exception {
        Subscription sub = newSub(channel);
        sub.subscribe();
        assertEquals("subscribing:0", subEvents.poll(5, TimeUnit.SECONDS));
        assertEquals("subscribed", subEvents.poll(5, TimeUnit.SECONDS));
        sub.unsubscribe();
        assertEquals("unsubscribed:0", subEvents.poll(5, TimeUnit.SECONDS));
        return sub;
    }

    /** A publication already on its way when the app unsubscribed is not delivered. */
    @Test
    public void testPublicationAfterUnsubscribeIsNotDelivered() throws Exception {
        connect();
        Subscription sub = subscribedThenUnsubscribed("news");
        server.publishChannel("news", "late".getBytes(StandardCharsets.UTF_8));
        assertNull("nothing after unsubscribed", subEvents.poll(500, TimeUnit.MILLISECONDS));
        assertEquals(SubscriptionState.UNSUBSCRIBED, sub.getState());
    }

    /**
     * An unsubscribe push with a resubscribe code for a subscription the app unsubscribed
     * ends that previous subscription: it must not subscribe again.
     */
    @Test
    public void testResubscribePushAfterUnsubscribeIsIgnored() throws Exception {
        connect();
        Subscription sub = subscribedThenUnsubscribed("news");
        server.unsubscribePush("news", 2500, "insufficient state");
        assertNull("no resubscribe", subEvents.poll(500, TimeUnit.MILLISECONDS));
        assertEquals(SubscriptionState.UNSUBSCRIBED, sub.getState());
    }

    /**
     * A server-side subscription's publication goes to the client listener even when the app
     * keeps an unsubscribed client-side subscription object for the same channel.
     */
    @Test
    public void testServerSidePublicationIsNotTakenByUnsubscribedClientSideObject() throws Exception {
        server.connectResult = Protocol.ConnectResult.newBuilder()
                .setClient("fake-client").setVersion("0.0.0").setPing(25)
                .putSubs("shared", Protocol.SubscribeResult.newBuilder().build())
                .build();
        connect();
        newSub("shared");
        server.publishChannel("shared", "srv".getBytes(StandardCharsets.UTF_8));
        String event = clientEvents.poll(2, TimeUnit.SECONDS);
        assertEquals("server_publication:shared", event);
        assertNull("nothing on the client-side object", subEvents.poll(300, TimeUnit.MILLISECONDS));
    }

    /**
     * An unsubscribe error reply: the server may still hold the subscription, so the client
     * reconnects to start from a clean server state (as on an unsubscribe timeout).
     */
    @Test
    public void testUnsubscribeErrorReplyReconnects() throws Exception {
        server.onCommand = cmd -> cmd.hasUnsubscribe()
                ? Protocol.Reply.newBuilder().setId(cmd.getId())
                    .setError(Protocol.Error.newBuilder().setCode(100).setMessage("internal").build()).build()
                : null;
        connect();
        subscribedThenUnsubscribed("news");
        String event = clientEvents.poll(3, TimeUnit.SECONDS);
        assertNotNull("reconnect after the unsubscribe error", event);
        assertEquals("connecting:" + Client.CONNECTING_UNSUBSCRIBE_ERROR, event);
    }
}
