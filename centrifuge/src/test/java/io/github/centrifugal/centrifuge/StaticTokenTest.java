package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * Clients and subscriptions without a token getter keep the token they have: nothing
 * else could replace it.
 */
public class StaticTokenTest {

    private FakeCentrifugoServer server;
    private Client client;
    private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

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

    private Client newClient(String token) {
        Options opts = new Options();
        if (token != null) {
            opts.setToken(token);
        }
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(100);
        client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                events.add("connected");
            }

            @Override
            public void onDisconnected(Client c, DisconnectedEvent e) {
                events.add("disconnected:" + e.getCode());
            }

            @Override
            public void onError(Client c, ErrorEvent e) {
                events.add("error:" + e.getError().getClass().getSimpleName());
            }
        });
        return client;
    }

    private List<String> connectTokens() {
        List<String> out = new ArrayList<>();
        for (Protocol.Command cmd : server.received()) {
            if (cmd.hasConnect()) {
                out.add(cmd.getConnect().getToken());
            }
        }
        return out;
    }

    private String nextEvent() throws InterruptedException {
        return events.poll(5, TimeUnit.SECONDS);
    }

    /** State invalidation (3014) to a client with a static token: it reconnects with that token. */
    @Test
    public void testStateInvalidationKeepsStaticToken() throws Exception {
        newClient("static").connect();
        assertEquals("connected", nextEvent());
        server.disconnect(Client.DISCONNECTED_STATE_INVALIDATED, "state invalidated");
        assertEquals("connected", nextEvent());
        assertEquals(java.util.Arrays.asList("static", "static"), connectTokens());
    }

    /** State invalidation (3014) to an anonymous client: it reconnects. */
    @Test
    public void testStateInvalidationReconnectsAnonymousClient() throws Exception {
        newClient(null).connect();
        assertEquals("connected", nextEvent());
        server.disconnect(Client.DISCONNECTED_STATE_INVALIDATED, "state invalidated");
        assertEquals("connected", nextEvent());
        assertEquals(ClientState.CONNECTED, client.getState());
    }

    /**
     * After a token expired error (109) without a token getter, the app sets a new token and
     * connects again: the new token is used.
     */
    @Test
    public void testSetTokenAfterExpiredTokenWithoutGetter() throws Exception {
        server.onCommand = cmd -> cmd.hasConnect() && cmd.getConnect().getToken().equals("expired")
                ? Protocol.Reply.newBuilder().setId(cmd.getId())
                    .setError(Protocol.Error.newBuilder().setCode(109).setMessage("token expired").build()).build()
                : null;
        newClient("expired").connect();
        // 109, then a local configuration error: no getter can get another token.
        String event;
        do {
            event = nextEvent();
            assertNotNull("disconnected after the expired token", event);
        } while (!event.startsWith("disconnected:"));
        assertEquals("expired", connectTokens().get(0));
        events.clear();

        client.setToken("fresh");
        client.connect();
        assertEquals("connected with the token set by the app", "connected", nextEvent());
        List<String> tokens = connectTokens();
        assertEquals("fresh", tokens.get(tokens.size() - 1));
    }

    /**
     * A subscription token expired (109) without a token getter: a configuration error and
     * unsubscribed as unauthorized, not a resubscribe without a token.
     */
    @Test
    public void testExpiredStaticSubscriptionTokenUnsubscribesUnauthorized() throws Exception {
        server.onCommand = cmd -> cmd.hasSubscribe()
                ? Protocol.Reply.newBuilder().setId(cmd.getId())
                    .setError(Protocol.Error.newBuilder().setCode(109).setMessage("token expired").build()).build()
                : null;
        newClient(null).connect();
        assertEquals("connected", nextEvent());
        LinkedBlockingQueue<String> subEvents = new LinkedBlockingQueue<>();
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setToken("sub-static");
        Subscription sub = client.newSubscription("private:ch", subOpts, new SubscriptionEventListener() {
            @Override
            public void onError(Subscription s, SubscriptionErrorEvent e) {
                subEvents.add("error:" + e.getError().getClass().getSimpleName());
            }

            @Override
            public void onUnsubscribed(Subscription s, UnsubscribedEvent e) {
                subEvents.add("unsubscribed:" + e.getCode());
            }
        });
        sub.subscribe();
        assertEquals("error:ConfigurationError", subEvents.poll(5, TimeUnit.SECONDS));
        assertEquals("unsubscribed:" + Client.UNSUBSCRIBED_UNAUTHORIZED, subEvents.poll(5, TimeUnit.SECONDS));
        Thread.sleep(300);
        int subscribes = 0;
        for (Protocol.Command cmd : server.received()) {
            if (cmd.hasSubscribe()) {
                subscribes++;
                assertEquals("never subscribes without its token", "sub-static", cmd.getSubscribe().getToken());
            }
        }
        assertEquals(1, subscribes);
        assertNull(subEvents.poll(100, TimeUnit.MILLISECONDS));
    }
}
