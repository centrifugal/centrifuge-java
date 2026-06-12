package io.github.centrifugal.centrifuge;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for SubscriptionOptions stateGetter (getState). These mirror
 * the getState tests in centrifuge-js / centrifuge-dart / centrifuge-go and
 * require the docker-compose Centrifugo (>= 6.8.0) running on localhost:8000.
 */
public class GetStateIntegrationTest extends IntegrationTestBase {

    private final CentrifugoApi api = new CentrifugoApi();
    private Client client;

    @Before
    public void setUp() {
        this.client = null;
    }

    @After
    public void tearDown() throws InterruptedException {
        if (this.client != null) {
            this.client.close(2000);
        }
    }

    private static String uniqueChannel(String namespace) {
        return namespace + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static <T> T poll(LinkedBlockingQueue<T> queue, String label) throws Exception {
        T value = queue.poll(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (value == null) {
            throw new TimeoutException("Timed out waiting for: " + label);
        }
        return value;
    }

    @Test(timeout = 15_000)
    public void testGetStateCalledOnInitialSubscribeAndRecovers() throws Exception {
        String channel = uniqueChannel("recovery");

        // Publish 3 messages BEFORE subscribing.
        for (int i = 1; i <= 3; i++) {
            api.publish(channel, "msg-" + i);
        }

        client = newClient(new EventListener() {});
        client.connect();

        // The state getter returns zero position — recovery delivers all 3 publications.
        AtomicInteger getStateCalls = new AtomicInteger();
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setStateGetter(new SubscriptionStateGetter() {
            @Override
            public void getSubscriptionState(SubscriptionGetStateEvent event, StateCallback cb) {
                getStateCalls.incrementAndGet();
                cb.Done(null, new StreamPosition(0, ""));
            }
        });

        Captured<SubscribedEvent> subscribed = new Captured<>();
        LinkedBlockingQueue<PublicationEvent> pubs = new LinkedBlockingQueue<>();
        Subscription sub = client.newSubscription(channel, opts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribed.set(e); }
            @Override public void onPublication(Subscription s, PublicationEvent e) { pubs.add(e); }
        });

        sub.subscribe();
        subscribed.await("subscribed");

        for (int i = 1; i <= 3; i++) {
            PublicationEvent e = poll(pubs, "recovered publication " + i);
            assertEquals("\"msg-" + i + "\"", new String(e.getData(), StandardCharsets.UTF_8));
        }
        assertEquals(1, getStateCalls.get());
    }

    @Test(timeout = 15_000)
    public void testGetStateNotCalledWhenRecoverySucceeds() throws Exception {
        String channel = uniqueChannel("recovery");

        client = newClient(new EventListener() {});
        client.connect();

        AtomicInteger getStateCalls = new AtomicInteger();
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setStateGetter(new SubscriptionStateGetter() {
            @Override
            public void getSubscriptionState(SubscriptionGetStateEvent event, StateCallback cb) {
                getStateCalls.incrementAndGet();
                cb.Done(null, new StreamPosition(0, ""));
            }
        });

        LinkedBlockingQueue<SubscribedEvent> subscribedEvents = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<PublicationEvent> pubs = new LinkedBlockingQueue<>();
        Subscription sub = client.newSubscription(channel, opts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedEvents.add(e); }
            @Override public void onPublication(Subscription s, PublicationEvent e) { pubs.add(e); }
        });

        sub.subscribe();
        poll(subscribedEvents, "initial subscribed");
        assertEquals(1, getStateCalls.get());

        // Disconnect, publish while away, reconnect — SDK has a saved position
        // and recovery succeeds, so the state getter must NOT be called again.
        client.disconnect();
        api.publish(channel, "away-1");
        api.publish(channel, "away-2");
        client.connect();

        SubscribedEvent resub = poll(subscribedEvents, "resubscribed");
        assertTrue("expected successful recovery on reconnect", resub.getRecovered());

        assertEquals("\"away-1\"", new String(poll(pubs, "recovered publication 1").getData(), StandardCharsets.UTF_8));
        assertEquals("\"away-2\"", new String(poll(pubs, "recovered publication 2").getData(), StandardCharsets.UTF_8));
        assertEquals("state getter must not be called when recovery succeeds", 1, getStateCalls.get());
    }

    @Test(timeout = 15_000)
    public void testGetStateErrorRetried() throws Exception {
        String channel = uniqueChannel("recovery");

        client = newClient(new EventListener() {});
        client.connect();

        // First state getter call fails, second succeeds.
        AtomicInteger getStateCalls = new AtomicInteger();
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setMinResubscribeDelay(50);
        opts.setMaxResubscribeDelay(50);
        opts.setStateGetter(new SubscriptionStateGetter() {
            @Override
            public void getSubscriptionState(SubscriptionGetStateEvent event, StateCallback cb) {
                if (getStateCalls.incrementAndGet() == 1) {
                    cb.Done(new Exception("simulated DB failure"), null);
                    return;
                }
                cb.Done(null, new StreamPosition(0, ""));
            }
        });

        Captured<SubscribedEvent> subscribed = new Captured<>();
        LinkedBlockingQueue<SubscriptionErrorEvent> errors = new LinkedBlockingQueue<>();
        Subscription sub = client.newSubscription(channel, opts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribed.set(e); }
            @Override public void onError(Subscription s, SubscriptionErrorEvent e) { errors.add(e); }
        });

        sub.subscribe();

        // First getter call fails → error emitted → resubscribe scheduled with
        // backoff. Second call succeeds → subscribe completes.
        subscribed.await("subscribed after state getter retry");
        assertTrue("expected at least 2 state getter calls", getStateCalls.get() >= 2);

        SubscriptionErrorEvent errEvent = poll(errors, "subscription error event");
        assertTrue("expected SubscriptionGetStateError, got " + errEvent.getError().getClass(),
                errEvent.getError() instanceof SubscriptionGetStateError);
        SubscriptionGetStateError getStateError = (SubscriptionGetStateError) errEvent.getError();
        assertEquals("simulated DB failure", getStateError.getError().getMessage());
    }

    @Test(timeout = 15_000)
    public void testGetStatePersistentFailureKeepsRetrying() throws Exception {
        String channel = uniqueChannel("recovery");

        client = newClient(new EventListener() {});
        client.connect();

        AtomicInteger getStateCalls = new AtomicInteger();
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setMinResubscribeDelay(50);
        opts.setMaxResubscribeDelay(50);
        opts.setStateGetter(new SubscriptionStateGetter() {
            @Override
            public void getSubscriptionState(SubscriptionGetStateEvent event, StateCallback cb) {
                getStateCalls.incrementAndGet();
                cb.Done(new Exception("always fails"), null);
            }
        });

        Subscription sub = client.newSubscription(channel, opts, new SubscriptionEventListener() {});
        sub.subscribe();

        // Wait for several retry cycles.
        Thread.sleep(500);

        // Should have retried multiple times while staying in subscribing state.
        assertTrue("expected more than 2 state getter calls, got " + getStateCalls.get(),
                getStateCalls.get() > 2);
        assertEquals(SubscriptionState.SUBSCRIBING, sub.getState());

        sub.unsubscribe();
    }

    @Test(timeout = 20_000)
    public void testGetStateCalledAgainOnUnrecoverablePosition() throws Exception {
        // Uses "smallhistory" namespace with history_size=2. After publishing
        // enough to evict old entries, reconnecting from an old position triggers
        // error 112 (unrecoverable position) because the subscribe request carries
        // the reject_unrecovered flag. The SDK must then call the state getter
        // again to reload app state instead of delivering recovered=false on an
        // active subscription.
        String channel = uniqueChannel("smallhistory");

        client = newClient(new EventListener() {});
        client.connect();

        // Simulate a real app: the state getter reads the current stream position
        // from the backend (here — via channel history top position).
        AtomicInteger getStateCalls = new AtomicInteger();
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setMinResubscribeDelay(50);
        opts.setMaxResubscribeDelay(50);
        opts.setStateGetter(new SubscriptionStateGetter() {
            @Override
            public void getSubscriptionState(SubscriptionGetStateEvent event, StateCallback cb) {
                getStateCalls.incrementAndGet();
                try {
                    cb.Done(null, api.history(event.getChannel()));
                } catch (Exception e) {
                    cb.Done(e, null);
                }
            }
        });

        LinkedBlockingQueue<SubscribedEvent> subscribedEvents = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<PublicationEvent> pubs = new LinkedBlockingQueue<>();
        Subscription sub = client.newSubscription(channel, opts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedEvents.add(e); }
            @Override public void onPublication(Subscription s, PublicationEvent e) { pubs.add(e); }
        });

        sub.subscribe();
        poll(subscribedEvents, "initial subscribed");
        assertEquals(1, getStateCalls.get());

        // Disconnect, then publish enough messages to push the stream beyond
        // recovery (history_size=2, so 5 messages evict old entries).
        client.disconnect();
        for (int i = 1; i <= 5; i++) {
            api.publish(channel, "evict-" + i);
        }

        // Reconnect — SDK tries to recover from the old position, server returns
        // error 112, SDK resets position and calls the state getter again.
        client.connect();
        poll(subscribedEvents, "resubscribed after unrecoverable position");
        assertEquals("state getter must be called again after unrecoverable position",
                2, getStateCalls.get());

        // Verify live delivery works after the re-sync.
        api.publish(channel, "live");
        PublicationEvent live = poll(pubs, "live publication after re-sync");
        assertEquals("\"live\"", new String(live.getData(), StandardCharsets.UTF_8));
    }
}
