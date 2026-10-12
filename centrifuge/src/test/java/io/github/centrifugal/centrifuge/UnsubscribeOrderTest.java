package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * unsubscribe() writes its command before anything queued after it, and a subscribe
 * reply is only applied to the subscribe attempt that sent it.
 */
public class UnsubscribeOrderTest {

    private FakeCentrifugoServer server;
    private Client client;

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

    private List<String> subscriptionCommands(String channel) {
        List<String> out = new ArrayList<>();
        for (Protocol.Command cmd : server.received()) {
            if (cmd.hasSubscribe() && cmd.getSubscribe().getChannel().equals(channel)) {
                out.add("subscribe");
            } else if (cmd.hasUnsubscribe() && cmd.getUnsubscribe().getChannel().equals(channel)) {
                out.add("unsubscribe");
            }
        }
        return out;
    }

    private Client connectedClient() throws InterruptedException {
        CountDownLatch connected = new CountDownLatch(1);
        client = new Client(server.url(), new Options(), new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }
        });
        client.connect();
        assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
        return client;
    }

    /**
     * unsubscribe() then subscribe() from the app: the server must get the unsubscribe
     * before the new subscribe. Sent the other way round, the server answers the
     * subscribe with 105 (already subscribed) and the late unsubscribe then ends the
     * new subscription.
     */
    @Test
    public void testUnsubscribeThenSubscribeReachesServerInOrder() throws Exception {
        connectedClient();
        LinkedBlockingQueue<SubscribedEvent> subscribed = new LinkedBlockingQueue<>();
        Subscription sub = client.newSubscription("order", new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                subscribed.add(e);
            }
        });
        sub.subscribe();
        assertNotNull("first subscribe", subscribed.poll(5, TimeUnit.SECONDS));

        sub.unsubscribe();
        sub.subscribe();

        assertNotNull("subscribed again", subscribed.poll(5, TimeUnit.SECONDS));
        assertEquals(java.util.Arrays.asList("subscribe", "unsubscribe", "subscribe"), subscriptionCommands("order"));
        assertEquals(SubscriptionState.SUBSCRIBED, sub.getState());
    }

    /**
     * subscribe(), unsubscribe(), subscribe() while the first subscribe reply is held:
     * that reply belongs to a subscribe the server was told to end, so it must not
     * complete the second attempt — only the second reply does.
     */
    @Test
    public void testReplyOfEndedSubscribeAttemptIsIgnored() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server.onSubscribe = (channel, req) -> Protocol.SubscribeResult.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFromUtf8(String.valueOf(attempts.incrementAndGet())))
                .build();
        CountDownLatch bothSent = new CountDownLatch(2);
        server.deferCommand = cmd -> {
            if (cmd.hasSubscribe()) {
                bothSent.countDown();
                return true;
            }
            return false;
        };
        connectedClient();
        LinkedBlockingQueue<SubscribedEvent> subscribed = new LinkedBlockingQueue<>();
        Subscription sub = client.newSubscription("toggle", new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                subscribed.add(e);
            }
        });

        sub.subscribe();
        sub.unsubscribe();
        sub.subscribe();
        assertTrue("both subscribes sent", bothSent.await(5, TimeUnit.SECONDS));
        server.releaseDeferredCommands();

        SubscribedEvent event = subscribed.poll(5, TimeUnit.SECONDS);
        assertNotNull("subscribed", event);
        assertEquals("completed by the second attempt's reply", "2", new String(event.getData(), StandardCharsets.UTF_8));
        assertNull("subscribed once", subscribed.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(java.util.Arrays.asList("subscribe", "unsubscribe", "subscribe"), subscriptionCommands("toggle"));
    }
}
