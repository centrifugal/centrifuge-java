package io.github.centrifugal.centrifuge;

/**
 * Configuration for a {@link Subscription} instance.
 */
public class SubscriptionOptions {
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /* Connection token. This is a token you have to receive from your application backend. */
    private String token = "";

    public SubscriptionTokenGetter getTokenGetter() {
        return tokenGetter;
    }

    public void setTokenGetter(SubscriptionTokenGetter tokenGetter) {
        this.tokenGetter = tokenGetter;
    }

    public SubscriptionTokenGetter tokenGetter;

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    /* Connect data to send to a server inside Connect command. */
    private byte[] data;

    public int getMinResubscribeDelay() {
        return minResubscribeDelay;
    }

    public void setMinResubscribeDelay(int minResubscribeDelay) {
        this.minResubscribeDelay = minResubscribeDelay;
    }

    private int minResubscribeDelay = 500;

    public int getMaxResubscribeDelay() {
        return maxResubscribeDelay;
    }

    public void setMaxResubscribeDelay(int maxResubscribeDelay) {
        this.maxResubscribeDelay = maxResubscribeDelay;
    }

    private int maxResubscribeDelay = 20000;

    public boolean isPositioned() {
        return positioned;
    }

    public void setPositioned(boolean positioned) {
        this.positioned = positioned;
    }

    private boolean positioned = false;

    public boolean isRecoverable() {
        return recoverable;
    }

    public void setRecoverable(boolean recoverable) {
        this.recoverable = recoverable;
    }

    private boolean recoverable = false;

    public boolean isJoinLeave() {
        return joinLeave;
    }

    public void setJoinLeave(boolean joinLeave) {
        this.joinLeave = joinLeave;
    }

    private boolean joinLeave = false;

    public String getDelta() {
        return delta;
    }

    // setDelta allows using delta compression for subscription. The delta compression
    // must be also enabled on server side. The only value at this point is "fossil".
    // See https://centrifugal.dev/docs/server/delta_compression.
    public void setDelta(String delta) {
        this.delta = delta;
    }

    private String delta = "";

    public SubscriptionStateGetter getStateGetter() {
        return stateGetter;
    }

    /**
     * Set SubscriptionStateGetter to load the app's current state and stream position.
     * Requires Centrifugo &gt;= 6.8.0.
     * <p>
     * The SDK calls the getter:
     * <ul>
     * <li>On initial subscribe (no saved position)</li>
     * <li>On reconnect when recovery fails (server returns error 112 — unrecoverable position)</li>
     * </ul>
     * NOT called on reconnects where the server successfully recovers missed publications —
     * in that case the recovered publications arrive as events and the getter is skipped.
     * <p>
     * The app should load its data from its own source of truth (database, API), render it,
     * and call back with the stream position. The SDK subscribes with recovery from the
     * returned position, so any publications between the state read and the subscribe are
     * delivered as publication events.
     * <p>
     * IMPORTANT: inside the getter, read the stream position FIRST, then read your data.
     * This ensures the position is a lower bound — any data loaded after the position read
     * is guaranteed to be included. The reverse order can produce gaps.
     * <p>
     * Recovered publications may overlap with data already loaded by the getter. This works
     * correctly when updates are idempotent (applying the same update twice produces the same
     * result). For non-idempotent updates, deduplicate by publication offset.
     * <p>
     * On error, the SDK emits an error event with {@link SubscriptionGetStateError} and
     * retries with backoff.
     */
    public void setStateGetter(SubscriptionStateGetter stateGetter) {
        this.stateGetter = stateGetter;
    }

    private SubscriptionStateGetter stateGetter;

    public void setSince(StreamPosition streamPosition) {
        this.since = streamPosition;
    }

    public StreamPosition getSince() {
        return since;
    }

    private StreamPosition since;
}
