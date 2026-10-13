package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EpochAndEndpointTest {

    private FakeCentrifugoServer server;
    private Client client;

    @After
    public void tearDown() throws InterruptedException {
        if (client != null) {
            client.close(1000);
        }
        if (server != null) {
            server.stop();
        }
    }

    private Subscription recoverableSub(LinkedBlockingQueue<String> events) throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        Options opts = new Options();
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(100);
        client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }
        });
        client.connect();
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        Subscription sub = client.newSubscription("ch", new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                events.add("subscribed");
            }

            @Override
            public void onPublication(Subscription s, PublicationEvent e) {
                events.add("publication:" + e.getOffset());
            }
        });
        sub.subscribe();
        assertEquals("subscribed", events.poll(5, TimeUnit.SECONDS));
        return sub;
    }

    private Protocol.SubscribeRequest subscribeRequest(int index) {
        int n = 0;
        for (Protocol.Command cmd : server.received()) {
            if (cmd.hasSubscribe() && n++ == index) {
                return cmd.getSubscribe();
            }
        }
        return null;
    }

    /**
     * A subscribe reply without an epoch (no stream yet): the epoch comes with the first
     * publication, and the next recovery sends it. An empty epoch would skip the server's
     * epoch check.
     */
    @Test
    public void testEpochTakenFromFirstPublication() throws Exception {
        server = new FakeCentrifugoServer();
        server.onSubscribe = (channel, req) -> Protocol.SubscribeResult.newBuilder().setRecoverable(true).build();
        server.start();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        recoverableSub(events);
        server.sendPush(Protocol.Push.newBuilder().setChannel("ch")
                .setPub(Protocol.Publication.newBuilder().setOffset(1).setEpoch("E1").build()).build());
        assertEquals("publication:1", events.poll(5, TimeUnit.SECONDS));
        server.disconnect(3001, "reconnect");
        assertEquals("subscribed", events.poll(5, TimeUnit.SECONDS));
        Protocol.SubscribeRequest resubscribe = subscribeRequest(1);
        assertNotNull(resubscribe);
        assertTrue(resubscribe.getRecover());
        assertEquals(1, resubscribe.getOffset());
        assertEquals("E1", resubscribe.getEpoch());
    }

    /** A subscribe reply without an epoch doesn't drop the epoch already known. */
    @Test
    public void testReplyWithoutEpochKeepsKnownEpoch() throws Exception {
        server = new FakeCentrifugoServer();
        AtomicInteger replies = new AtomicInteger();
        server.onSubscribe = (channel, req) -> replies.incrementAndGet() == 1
                ? Protocol.SubscribeResult.newBuilder().setRecoverable(true).setEpoch("E1").build()
                : Protocol.SubscribeResult.newBuilder().setRecoverable(true).setRecovered(true).setWasRecovering(true).build();
        server.start();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        recoverableSub(events);
        server.disconnect(3001, "reconnect");
        assertEquals("subscribed", events.poll(5, TimeUnit.SECONDS));
        server.disconnect(3001, "reconnect");
        assertEquals("subscribed", events.poll(5, TimeUnit.SECONDS));
        assertEquals("E1", subscribeRequest(2).getEpoch());
    }

    /** An endpoint OkHttp can't open: a configuration error, and the client stays disconnected. */
    @Test
    public void testInvalidEndpointReportsConfigurationError() throws Exception {
        List<String> errors = new CopyOnWriteArrayList<>();
        CountDownLatch errored = new CountDownLatch(1);
        client = new Client("localhost:8000/connection/websocket", new Options(), new EventListener() {
            @Override
            public void onError(Client c, ErrorEvent e) {
                errors.add(e.getError().getClass().getSimpleName());
                errored.countDown();
            }
        });
        client.connect();
        assertTrue(errored.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(java.util.Collections.singletonList("ConfigurationError"), errors);
        assertEquals(ClientState.DISCONNECTED, client.getState());
    }
}
