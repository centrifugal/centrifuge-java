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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for publication filtering (server-side filtering by publication tags).
 * The {@link FilterNodeBuilder} helpers build a protocol FilterNode tree; the
 * subscribe request carries it in the {@code tf} field. The feature requires
 * Centrifugo PRO / namespace config, so wire-level behavior is exercised against
 * the in-process {@link FakeCentrifugoServer}.
 */
public class FilterTest {

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
    public void testBuilderLeafNodes() {
        assertEquals("eq", FilterNodeBuilder.eq("ticker", "AAPL").getProtoNode().getCmp());
        assertEquals("AAPL", FilterNodeBuilder.eq("ticker", "AAPL").getProtoNode().getVal());
        assertEquals("neq", FilterNodeBuilder.neq("source", "TEST").getProtoNode().getCmp());
        assertEquals("ex", FilterNodeBuilder.exists("price").getProtoNode().getCmp());
        assertEquals("nex", FilterNodeBuilder.notExists("internal_id").getProtoNode().getCmp());
        assertEquals("sw", FilterNodeBuilder.startsWith("ticker", "AA").getProtoNode().getCmp());
        assertEquals("ew", FilterNodeBuilder.endsWith("source", "DAQ").getProtoNode().getCmp());
        assertEquals("ct", FilterNodeBuilder.contains("category", "ec").getProtoNode().getCmp());
        assertEquals("gt", FilterNodeBuilder.gt("price", "100").getProtoNode().getCmp());
        assertEquals("gte", FilterNodeBuilder.gte("volume", "1000").getProtoNode().getCmp());
        assertEquals("lt", FilterNodeBuilder.lt("price", "200").getProtoNode().getCmp());
        assertEquals("lte", FilterNodeBuilder.lte("volume", "1000").getProtoNode().getCmp());
    }

    @Test
    public void testBuilderSetMembership() {
        Protocol.FilterNode in = FilterNodeBuilder.in("category", "tech", "finance").getProtoNode();
        assertEquals("in", in.getCmp());
        assertEquals(2, in.getValsCount());
        assertEquals("tech", in.getVals(0));

        Protocol.FilterNode nin = FilterNodeBuilder.nin("ticker", "MSFT", "GOOGL").getProtoNode();
        assertEquals("nin", nin.getCmp());
        assertEquals(2, nin.getValsCount());
    }

    @Test
    public void testBuilderLogicalNodes() {
        Protocol.FilterNode and = FilterNodeBuilder.and(
                FilterNodeBuilder.eq("ticker", "AAPL"),
                FilterNodeBuilder.gte("price", "100"),
                FilterNodeBuilder.in("source", "NASDAQ", "NYSE")).getProtoNode();
        assertEquals("and", and.getOp());
        assertEquals(3, and.getNodesCount());
        assertEquals("ticker", and.getNodes(0).getKey());
        assertEquals("in", and.getNodes(2).getCmp());

        Protocol.FilterNode or = FilterNodeBuilder.or(
                FilterNodeBuilder.eq("ticker", "MSFT"),
                FilterNodeBuilder.eq("category", "tech")).getProtoNode();
        assertEquals("or", or.getOp());
        assertEquals(2, or.getNodesCount());

        Protocol.FilterNode not = FilterNodeBuilder.not(FilterNodeBuilder.eq("source", "NYSE")).getProtoNode();
        assertEquals("not", not.getOp());
        assertEquals(1, not.getNodesCount());
        assertEquals("eq", not.getNodes(0).getCmp());
    }

    @Test
    public void testSetTagsFilterRejectsDeltaCombination() throws Exception {
        Client client = new Client(server.url(), new Options(), new EventListener() {});
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setDelta("fossil");
        Subscription sub = client.newSubscription("market:stocks", opts, new SubscriptionEventListener() {});
        try {
            sub.setTagsFilter(FilterNodeBuilder.eq("ticker", "AAPL"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
        client.close(1000);
    }

    @Test
    public void testSubscribeRequestCarriesTagsFilter() throws Exception {
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setTagsFilter(FilterNodeBuilder.and(
                FilterNodeBuilder.eq("ticker", "AAPL"),
                FilterNodeBuilder.gte("price", "100")));

        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();
        Subscription sub = client.newSubscription("market:stocks", opts, new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
        });
        sub.subscribe();
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertNotNull(subscribedQ.poll(5, TimeUnit.SECONDS));

            Protocol.FilterNode tf = server.lastSubscribe().getTf();
            assertEquals("and", tf.getOp());
            assertEquals(2, tf.getNodesCount());
            assertEquals("ticker", tf.getNodes(0).getKey());
            assertEquals("eq", tf.getNodes(0).getCmp());
            assertEquals("AAPL", tf.getNodes(0).getVal());
            assertEquals("gte", tf.getNodes(1).getCmp());
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testSetTagsFilterAppliesOnSubscribe() throws Exception {
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();
        Subscription sub = client.newSubscription("market:stocks", new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
        });
        sub.setTagsFilter(FilterNodeBuilder.eq("ticker", "BTC"));
        sub.subscribe();
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertNotNull(subscribedQ.poll(5, TimeUnit.SECONDS));

            Protocol.FilterNode tf = server.lastSubscribe().getTf();
            assertEquals("ticker", tf.getKey());
            assertEquals("BTC", tf.getVal());
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testSubscribeWithoutFilterSendsNoTf() throws Exception {
        LinkedBlockingQueue<SubscribedEvent> subscribedQ = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override public void onConnected(Client c, ConnectedEvent e) { connected.countDown(); }
        });
        client.connect();
        Subscription sub = client.newSubscription("market:stocks", new SubscriptionEventListener() {
            @Override public void onSubscribed(Subscription s, SubscribedEvent e) { subscribedQ.add(e); }
        });
        sub.subscribe();
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertNotNull(subscribedQ.poll(5, TimeUnit.SECONDS));
            assertFalse(server.lastSubscribe().hasTf());
        } finally {
            client.close(1000);
        }
    }
}
