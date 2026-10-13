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
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Token requests, retries and refreshes of a subscription across connection changes. */
public class SubscriptionFlowTest {

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

    private Client newClient(CountDownLatch connected) {
        Options opts = new Options();
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(100);
        client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }
        });
        return client;
    }

    private int commands(java.util.function.Predicate<Protocol.Command> p) {
        int n = 0;
        for (Protocol.Command cmd : server.received()) {
            if (p.test(cmd)) {
                n++;
            }
        }
        return n;
    }

    /** subscribe() before connect(): the token is requested once, after the client connected. */
    @Test
    public void testTokenNotRequestedWhileDisconnected() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        newClient(connected);
        List<ClientState> getterCalls = new CopyOnWriteArrayList<>();
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setTokenGetter(new SubscriptionTokenGetter() {
            @Override
            public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                getterCalls.add(client.getState());
                cb.Done(null, "token");
            }
        });
        LinkedBlockingQueue<String> subscribed = new LinkedBlockingQueue<>();
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                subscribed.add("subscribed");
            }
        });
        sub.subscribe();
        Thread.sleep(200);
        client.connect();
        assertNotNull(subscribed.poll(5, TimeUnit.SECONDS));
        assertEquals(java.util.Collections.singletonList(ClientState.CONNECTED), getterCalls);
    }

    /** A subscribe error's retry backoff is kept across a reconnect. */
    @Test
    public void testResubscribeBackoffKeptAcrossReconnect() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        newClient(connected).connect();
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        List<Long> calls = new CopyOnWriteArrayList<>();
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setMinResubscribeDelay(1500);
        subOpts.setMaxResubscribeDelay(1500);
        subOpts.setTokenGetter(new SubscriptionTokenGetter() {
            @Override
            public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                calls.add(System.nanoTime());
                if (calls.size() == 1) {
                    cb.Done(new Exception("token backend down"), null);
                } else {
                    cb.Done(null, "token");
                }
            }
        });
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override
            public void onError(Subscription s, SubscriptionErrorEvent e) {
                events.add("error");
            }

            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                events.add("subscribed");
            }
        });
        sub.subscribe();
        assertEquals("error", events.poll(5, TimeUnit.SECONDS));
        server.disconnect(3001, "reconnect");
        assertEquals("subscribed", events.poll(5, TimeUnit.SECONDS));
        long retryAfterMs = TimeUnit.NANOSECONDS.toMillis(calls.get(1) - calls.get(0));
        assertTrue("retried after its backoff (" + retryAfterMs + " ms), not at the reconnect", retryAfterMs >= 1300);
    }

    /**
     * A refresh whose token request is still pending when the connection drops belongs to the
     * subscribed period that ended: after the resubscribe there is one refresh chain, not two.
     */
    @Test
    public void testRefreshInFlightAcrossReconnectDoesNotStartSecondChain() throws Exception {
        server.onSubscribe = (channel, req) -> Protocol.SubscribeResult.newBuilder().setExpires(true).setTtl(1).build();
        server.onCommand = cmd -> cmd.hasSubRefresh()
                ? Protocol.Reply.newBuilder().setId(cmd.getId())
                    .setSubRefresh(Protocol.SubRefreshResult.newBuilder().setExpires(true).setTtl(1).build()).build()
                : null;
        CountDownLatch connected = new CountDownLatch(1);
        newClient(connected).connect();
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        AtomicInteger getterCalls = new AtomicInteger();
        CountDownLatch slowRefreshStarted = new CountDownLatch(1);
        CountDownLatch resubscribed = new CountDownLatch(1);
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setTokenGetter(new SubscriptionTokenGetter() {
            @Override
            public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
                int n = getterCalls.incrementAndGet();
                if (n == 2) {
                    // The first refresh: answered right after the connection dropped and the
                    // subscription resubscribed, before the new period's first refresh.
                    slowRefreshStarted.countDown();
                    new Thread(() -> {
                        try {
                            resubscribed.await(5, TimeUnit.SECONDS);
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                        }
                        cb.Done(null, "token-" + n);
                    }).start();
                    return;
                }
                cb.Done(null, "token-" + n);
            }
        });
        LinkedBlockingQueue<String> subscribed = new LinkedBlockingQueue<>();
        Subscription sub = client.newSubscription("ch", subOpts, new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                subscribed.add("subscribed");
            }
        });
        sub.subscribe();
        assertNotNull(subscribed.poll(5, TimeUnit.SECONDS));
        assertTrue("refresh started", slowRefreshStarted.await(5, TimeUnit.SECONDS));
        server.disconnect(3001, "reconnect");
        assertNotNull("resubscribed", subscribed.poll(5, TimeUnit.SECONDS));
        resubscribed.countDown();

        // ttl=1s: one chain sends about 4 refreshes in the next 4s, two chains about 8.
        int before = commands(Protocol.Command::hasSubRefresh);
        Thread.sleep(4000);
        int refreshes = commands(Protocol.Command::hasSubRefresh) - before;
        assertTrue("one refresh chain: " + refreshes + " sub_refresh commands in 4s", refreshes <= 5);
    }
}
