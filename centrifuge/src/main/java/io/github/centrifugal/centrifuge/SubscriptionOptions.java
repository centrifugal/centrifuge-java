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

    public void setSince(StreamPosition streamPosition) {
        this.since = streamPosition;
    }

    public StreamPosition getSince() {
        return since;
    }

    private StreamPosition since;
}
