package io.github.centrifugal.centrifuge;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

/**
 * A node in a publication tags-filter expression tree, used for server-side
 * publication filtering. Build instances with {@link FilterNodeBuilder} and pass
 * the result to {@link SubscriptionOptions#setTagsFilter} or
 * {@link Subscription#setTagsFilter}.
 *
 * <p>A filter is either a leaf node (a comparison such as {@code ticker == "AAPL"})
 * or a logical node combining child nodes with and/or/not. The server evaluates
 * the filter against each publication's tags and delivers only matching
 * publications. See
 * <a href="https://centrifugal.dev/docs/server/publication_filtering">publication filtering</a>.
 *
 * <p>Publication filtering must be enabled for the namespace on the server
 * ({@code allow_tags_filter}) and cannot be combined with delta compression.
 */
public final class FilterNode {
    private final Protocol.FilterNode node;

    FilterNode(Protocol.FilterNode node) {
        this.node = node;
    }

    Protocol.FilterNode getProtoNode() {
        return node;
    }
}
