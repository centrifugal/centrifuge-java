package io.github.centrifugal.centrifuge;

import java.util.Arrays;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

/**
 * Helpers for building {@link FilterNode} expressions for server-side publication
 * filtering based on publication tags. Comparison helpers create leaf nodes;
 * {@link #and}, {@link #or} and {@link #not} combine them.
 *
 * <pre>
 * // (ticker == "AAPL") AND (price &gt;= "100") AND (source in ["NASDAQ", "NYSE"])
 * FilterNode filter = FilterNodeBuilder.and(
 *         FilterNodeBuilder.eq("ticker", "AAPL"),
 *         FilterNodeBuilder.gte("price", "100"),
 *         FilterNodeBuilder.in("source", "NASDAQ", "NYSE"));
 * </pre>
 */
public final class FilterNodeBuilder {

    private FilterNodeBuilder() {
    }

    private static FilterNode leaf(String key, String cmp, String val) {
        Protocol.FilterNode.Builder b = Protocol.FilterNode.newBuilder().setKey(key).setCmp(cmp);
        if (val != null) {
            b.setVal(val);
        }
        return new FilterNode(b.build());
    }

    private static FilterNode set(String key, String cmp, String[] vals) {
        Protocol.FilterNode.Builder b = Protocol.FilterNode.newBuilder().setKey(key).setCmp(cmp);
        b.addAllVals(Arrays.asList(vals));
        return new FilterNode(b.build());
    }

    private static FilterNode logical(String op, FilterNode[] nodes) {
        Protocol.FilterNode.Builder b = Protocol.FilterNode.newBuilder().setOp(op);
        for (FilterNode n : nodes) {
            b.addNodes(n.getProtoNode());
        }
        return new FilterNode(b.build());
    }

    /** Tag {@code key} equals {@code val}. */
    public static FilterNode eq(String key, String val) {
        return leaf(key, "eq", val);
    }

    /** Tag {@code key} does not equal {@code val}. */
    public static FilterNode neq(String key, String val) {
        return leaf(key, "neq", val);
    }

    /** Tag {@code key} is one of {@code vals}. */
    public static FilterNode in(String key, String... vals) {
        return set(key, "in", vals);
    }

    /** Tag {@code key} is not one of {@code vals}. */
    public static FilterNode nin(String key, String... vals) {
        return set(key, "nin", vals);
    }

    /** Tag {@code key} exists. */
    public static FilterNode exists(String key) {
        return leaf(key, "ex", null);
    }

    /** Tag {@code key} does not exist. */
    public static FilterNode notExists(String key) {
        return leaf(key, "nex", null);
    }

    /** String tag {@code key} starts with {@code val}. */
    public static FilterNode startsWith(String key, String val) {
        return leaf(key, "sw", val);
    }

    /** String tag {@code key} ends with {@code val}. */
    public static FilterNode endsWith(String key, String val) {
        return leaf(key, "ew", val);
    }

    /** String tag {@code key} contains {@code val}. */
    public static FilterNode contains(String key, String val) {
        return leaf(key, "ct", val);
    }

    /** Numeric tag {@code key} is greater than {@code val}. */
    public static FilterNode gt(String key, String val) {
        return leaf(key, "gt", val);
    }

    /** Numeric tag {@code key} is greater than or equal to {@code val}. */
    public static FilterNode gte(String key, String val) {
        return leaf(key, "gte", val);
    }

    /** Numeric tag {@code key} is less than {@code val}. */
    public static FilterNode lt(String key, String val) {
        return leaf(key, "lt", val);
    }

    /** Numeric tag {@code key} is less than or equal to {@code val}. */
    public static FilterNode lte(String key, String val) {
        return leaf(key, "lte", val);
    }

    /** All {@code nodes} must match. */
    public static FilterNode and(FilterNode... nodes) {
        return logical("and", nodes);
    }

    /** At least one of {@code nodes} must match. */
    public static FilterNode or(FilterNode... nodes) {
        return logical("or", nodes);
    }

    /** Inverts {@code node}. */
    public static FilterNode not(FilterNode node) {
        return logical("not", new FilterNode[]{node});
    }
}
