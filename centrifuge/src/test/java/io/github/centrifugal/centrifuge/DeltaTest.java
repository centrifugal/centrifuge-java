package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.ByteString;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Fossil delta decoding of publications, as centrifuge-js. */
public class DeltaTest {

    private FakeCentrifugoServer server;
    private Client client;
    private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws IOException {
        server = new FakeCentrifugoServer();
        server.start();
        server.onSubscribe = (channel, req) -> Protocol.SubscribeResult.newBuilder().setDelta(req.getDelta().equals("fossil")).build();
    }

    @After
    public void tearDown() throws InterruptedException {
        if (client != null) {
            client.close(1000);
        }
        server.stop();
    }

    private String nextEvent(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String e = events.poll(100, TimeUnit.MILLISECONDS);
            if (e != null && e.startsWith(prefix)) {
                return e;
            }
        }
        return null;
    }

    private Subscription subscribe(SubscriptionEventListener listener) throws Exception {
        client = new Client(server.url(), new Options(), new EventListener() {
            @Override
            public void onDisconnected(Client c, DisconnectedEvent e) {
                events.add("disconnected:" + e.getCode());
            }
        });
        SubscriptionOptions subOpts = new SubscriptionOptions();
        subOpts.setDelta("fossil");
        Subscription sub = client.newSubscription("ch", subOpts, listener);
        sub.subscribe();
        client.connect();
        assertNotNull(nextEvent("subscribed"));
        return sub;
    }

    private void publish(byte[] data, boolean delta) {
        server.sendPush(Protocol.Push.newBuilder().setChannel("ch")
                .setPub(Protocol.Publication.newBuilder().setData(ByteString.copyFrom(data)).setDelta(delta))
                .build());
    }

    /** A delta with nothing to apply it to is a protocol error, not a payload. */
    @Test
    public void testDeltaWithoutBaseStopsTheClient() throws Exception {
        subscribe(new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                events.add("subscribed");
            }

            @Override
            public void onPublication(Subscription s, PublicationEvent e) {
                events.add("pub:" + new String(e.getData(), StandardCharsets.UTF_8));
            }
        });
        publish("4\n4:test2Bm6Re;".getBytes(StandardCharsets.UTF_8), true);
        assertEquals("disconnected:2", nextEvent("disconnected:"));
        assertNull("the delta is not delivered", nextEvent("pub:"));
    }

    /** Changing the delivered data in place does not change the base of the next delta. */
    @Test
    public void testDeliveredDataIsNotTheBase() throws Exception {
        subscribe(new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription s, SubscribedEvent e) {
                events.add("subscribed");
            }

            @Override
            public void onPublication(Subscription s, PublicationEvent e) {
                events.add("pub:" + new String(e.getData(), StandardCharsets.UTF_8));
                java.util.Arrays.fill(e.getData(), (byte) 'x');
            }
        });
        byte[] base = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] target = "hello world".getBytes(StandardCharsets.UTF_8);
        publish(base, false);
        assertEquals("pub:hello", nextEvent("pub:"));
        publish(insertDelta(target), true);
        assertEquals("pub:hello world", nextEvent("pub:"));
    }

    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz~";

    private static String fossilInt(long v) {
        if (v == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        while (v > 0) {
            sb.insert(0, DIGITS.charAt((int) (v & 0x3f)));
            v >>>= 6;
        }
        return sb.toString();
    }

    /** A delta that copies the first 5 bytes of the base and inserts the rest of target. */
    private static byte[] insertDelta(byte[] target) {
        String rest = new String(target, 5, target.length - 5, StandardCharsets.UTF_8);
        String delta = fossilInt(target.length) + "\n5@0," + fossilInt(rest.length()) + ":" + rest
                + fossilInt(Fossil.checksum(target)) + ";";
        return delta.getBytes(StandardCharsets.UTF_8);
    }
}
