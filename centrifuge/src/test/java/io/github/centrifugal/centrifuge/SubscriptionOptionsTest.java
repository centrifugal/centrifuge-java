package io.github.centrifugal.centrifuge;

import org.junit.Test;

import static org.junit.Assert.*;

public class SubscriptionOptionsTest {

    @Test
    public void testDefaultValues() {
        SubscriptionOptions opts = new SubscriptionOptions();
        assertEquals("", opts.getToken());
        assertNull(opts.getTokenGetter());
        assertNull(opts.getData());
        assertEquals(500, opts.getMinResubscribeDelay());
        assertEquals(20000, opts.getMaxResubscribeDelay());
        assertFalse(opts.isPositioned());
        assertFalse(opts.isRecoverable());
        assertFalse(opts.isJoinLeave());
        assertEquals("", opts.getDelta());
        assertNull(opts.getSince());
    }

    @Test
    public void testSetToken() {
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setToken("sub-token");
        assertEquals("sub-token", opts.getToken());
    }

    @Test
    public void testSetData() {
        SubscriptionOptions opts = new SubscriptionOptions();
        byte[] data = new byte[]{4, 5, 6};
        opts.setData(data);
        assertArrayEquals(data, opts.getData());
    }

    @Test
    public void testSetResubscribeDelays() {
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setMinResubscribeDelay(1000);
        opts.setMaxResubscribeDelay(30000);
        assertEquals(1000, opts.getMinResubscribeDelay());
        assertEquals(30000, opts.getMaxResubscribeDelay());
    }

    @Test
    public void testSetPositioned() {
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setPositioned(true);
        assertTrue(opts.isPositioned());
    }

    @Test
    public void testSetRecoverable() {
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setRecoverable(true);
        assertTrue(opts.isRecoverable());
    }

    @Test
    public void testSetJoinLeave() {
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setJoinLeave(true);
        assertTrue(opts.isJoinLeave());
    }

    @Test
    public void testSetDelta() {
        SubscriptionOptions opts = new SubscriptionOptions();
        opts.setDelta("fossil");
        assertEquals("fossil", opts.getDelta());
    }

    @Test
    public void testSetSince() {
        SubscriptionOptions opts = new SubscriptionOptions();
        StreamPosition sp = new StreamPosition();
        sp.setOffset(42);
        sp.setEpoch("test-epoch");
        opts.setSince(sp);
        assertNotNull(opts.getSince());
        assertEquals(42, opts.getSince().getOffset());
        assertEquals("test-epoch", opts.getSince().getEpoch());
    }
}
