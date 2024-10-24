package io.github.centrifugal.centrifuge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.github.centrifugal.centrifuge.internal.backoff.Backoff;
import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import java8.util.concurrent.CompletableFuture;

public class Subscription {

    final private Client client;
    final private String channel;
    final private SubscriptionOptions opts;
    private boolean recover;
    private long offset;
    private String epoch;
    final private SubscriptionEventListener listener;
    private volatile SubscriptionState state = SubscriptionState.UNSUBSCRIBED;
    final private Map<String, CompletableFuture<Throwable>> futures = new ConcurrentHashMap<>();
    private final Backoff backoff;
    private ScheduledFuture<?> refreshTask;
    private ScheduledFuture<?> resubscribeTask;
    private int resubscribeAttempts = 0;
    private String token;
    private com.google.protobuf.ByteString data;
    private boolean deltaNegotiated;
    private byte[] prevData;

    Subscription(final Client client, final String channel, final SubscriptionEventListener listener, final SubscriptionOptions options) {
        this.client = client;
        this.channel = channel;
        this.listener = listener;
        this.backoff = new Backoff();
        this.opts = options;
        this.token = opts.getToken();
        if (opts.getData() != null) {
            this.data = com.google.protobuf.ByteString.copyFrom(opts.getData());
        }
        this.prevData = null;
        this.deltaNegotiated = false;
        if (opts.getSince() != null) {
            this.offset = opts.getSince().getOffset();
            this.epoch = opts.getSince().getEpoch();
            this.recover = true;
        }
    }

    Subscription(final Client client, final String channel, final SubscriptionEventListener listener) {
        this(client, channel, listener, new SubscriptionOptions());
    }

    void setState(SubscriptionState state) {
        this.state = state;
    }

    public SubscriptionState getState() {
        return this.state;
    }

    public String getChannel() {
        return channel;
    }

    SubscriptionEventListener getListener() {
        return listener;
    }

    long getOffset() {
        return offset;
    }

    private void setOffset(long offset) {
        this.offset = offset;
    }

    String getEpoch() {
        return epoch;
    }

    private void setEpoch(String epoch) {
        this.epoch = epoch;
    }

    // Access must be synchronized.
    void resubscribeIfNecessary() {
        if (this.getState() != SubscriptionState.SUBSCRIBING) {
            return;
        }
        this.sendSubscribe();
    }

    void sendRefresh() {
        if (this.opts.getTokenGetter() == null) {
            return;
        }
        this.client.getExecutor().submit(() -> Subscription.this.opts.getTokenGetter().getSubscriptionToken(new SubscriptionTokenEvent(this.getChannel()), (err, token) -> {
            if (Subscription.this.getState() != SubscriptionState.SUBSCRIBED) {
                return;
            }
            if (err != null) {
                if (err instanceof UnauthorizedException) {
                    Subscription.this.failUnauthorized(true);
                    return;
                }
                Subscription.this.listener.onError(Subscription.this, new SubscriptionErrorEvent(new SubscriptionTokenError(err)));
                Subscription.this.refreshTask = Subscription.this.client.getScheduler().schedule(
                        Subscription.this::sendRefresh,
                        Subscription.this.backoff.duration(0, 10000, 20000),
                        TimeUnit.MILLISECONDS
                );
                return;
            }
            if (token == null || token.equals("")) {
                this.failUnauthorized(true);
                return;
            }
            Subscription.this.token = token;
            Subscription.this.client.subRefreshSynchronized(Subscription.this.channel, token, (error, result) -> {
                if (Subscription.this.getState() != SubscriptionState.SUBSCRIBED) {
                    return;
                }
                Throwable errorOrNull = error != null ? error : (result == null ? new NullPointerException() : null);
                if (errorOrNull != null) {
                    Subscription.this.listener.onError(Subscription.this, new SubscriptionErrorEvent(new SubscriptionRefreshError(errorOrNull)));
                    if (error instanceof ReplyError) {
                        ReplyError e;
                        e = (ReplyError) error;
                        if (e.isTemporary()) {
                            Subscription.this.refreshTask = Subscription.this.client.getScheduler().schedule(
                                    Subscription.this::sendRefresh,
                                    Subscription.this.backoff.duration(0, 10000, 20000),
                                    TimeUnit.MILLISECONDS
                            );
                        } else {
                            Subscription.this._unsubscribe(true, e.getCode(), e.getMessage());
                        }
                        return;
                    } else {
                        Subscription.this.refreshTask = Subscription.this.client.getScheduler().schedule(
                                Subscription.this::sendRefresh,
                                Subscription.this.backoff.duration(0, 10000, 20000),
                                TimeUnit.MILLISECONDS
                        );
                    }
                    return;
                }
                if (result.getExpires()) {
                    Subscription.this.refreshTask = Subscription.this.client.getScheduler().schedule(
                            Subscription.this::sendRefresh,
                            result.getTtl(),
                            TimeUnit.SECONDS
                    );
                }
            });
        }));
    }

    void moveToSubscribing(int code, String reason) {
        if (this.getState() == SubscriptionState.SUBSCRIBING) {
            this.clearSubscribingState();
            return;
        }
        this.setState(SubscriptionState.SUBSCRIBING);
        this.listener.onSubscribing(this, new SubscribingEvent(code, reason));
    }

    void moveToUnsubscribed(boolean sendUnsubscribe, int code, String reason) {
        if (this.getState() == SubscriptionState.UNSUBSCRIBED) {
            return;
        }
        this._unsubscribe(sendUnsubscribe, code, reason);
    }

    void handlePublication(Protocol.Publication pub) throws Exception {
        ClientInfo info = ClientInfo.fromProtocolClientInfo(pub.getInfo());
        PublicationEvent event = new PublicationEvent();
        byte[] pubData = pub.getData().toByteArray();
        byte[] prevData = this.getPrevData();
        if (prevData != null && pub.getDelta()) {
            pubData = Fossil.applyDelta(prevData, pubData);
        }
        this.setPrevData(pubData);
        event.setData(pubData);
        event.setInfo(info);
        event.setOffset(pub.getOffset());
        event.setTags(pub.getTagsMap());
        if (pub.getOffset() > 0) {
            this.setOffset(pub.getOffset());
        }
        this.listener.onPublication(this, event);
    }

    void moveToSubscribed(Protocol.SubscribeResult result) throws Exception {
        this.setState(SubscriptionState.SUBSCRIBED);
        if (result.getRecoverable()) {
            this.recover = true;
        }
        this.setEpoch(result.getEpoch());
        this.deltaNegotiated = result.getDelta();

        byte[] data = null;
        if (result.getData() != null) {
            data = result.getData().toByteArray();
        }
        SubscribedEvent event = new SubscribedEvent(result.getWasRecovering(), result.getRecovered(), result.getPositioned(), result.getRecoverable(), result.getPositioned() || result.getRecoverable() ? new StreamPosition(result.getOffset(), result.getEpoch()) : null, data);
        this.listener.onSubscribed(this, event);

        if (result.getPublicationsCount() > 0) {
            for (Protocol.Publication publication : result.getPublicationsList()) {
                this.client.handlePub(this.channel, publication);
            }
        } else {
            this.setOffset(result.getOffset());
        }

        for(Map.Entry<String, CompletableFuture<Throwable>> entry: this.futures.entrySet()) {
            CompletableFuture<Throwable> f = entry.getValue();
            f.complete(null);
        }
        this.futures.clear();

        if (result.getExpires()) {
            this.refreshTask = this.client.getScheduler().schedule(
                Subscription.this::sendRefresh,
                result.getTtl(),
                TimeUnit.SECONDS
            );
        }
    }

    void subscribeError(ReplyError err) {
        this.listener.onError(this, new SubscriptionErrorEvent(new SubscriptionSubscribeError(err)));
        if (err.getCode() == 109) { // Token expired.
            this.token = "";
            this.data = null;
            this.scheduleResubscribe();
        } if (err.isTemporary()) {
            this.scheduleResubscribe();
        } else {
            this._unsubscribe(false, err.getCode(), err.getMessage());
        }
    }

    public void subscribe() {
        this.client.getExecutor().submit(() -> {
            if (Subscription.this.getState() == SubscriptionState.SUBSCRIBED || Subscription.this.getState() == SubscriptionState.SUBSCRIBING) {
                return;
            }
            Subscription.this.setState(SubscriptionState.SUBSCRIBING);
            Subscription.this.listener.onSubscribing(Subscription.this, new SubscribingEvent(Client.SUBSCRIBING_SUBSCRIBE_CALLED, "subscribe called"));
            Subscription.this.sendSubscribe();
        });
    }

    Protocol.SubscribeRequest createSubscribeRequest() {
        final boolean isRecover = this.getRecover();
        StreamPosition streamPosition = new StreamPosition();

        if (isRecover) {
            streamPosition.setOffset(this.getOffset());
            streamPosition.setEpoch(this.getEpoch());
        }

        Protocol.SubscribeRequest.Builder builder = Protocol.SubscribeRequest.newBuilder();

        builder.setChannel(this.channel).setToken(this.token);
        if (this.data != null) {
            builder.setData(this.data);
        }
        if (isRecover) {
            builder.setRecover(true)
                    .setEpoch(streamPosition.getEpoch())
                    .setOffset(streamPosition.getOffset());
        }

        builder.setPositioned(this.opts.isPositioned());
        builder.setRecoverable(this.opts.isRecoverable());
        builder.setJoinLeave(this.opts.isJoinLeave());
        builder.setDelta(this.opts.getDelta());

        return builder.build();
    }

    void sendSubscribe() {
        final boolean isRecover = this.getRecover();
        StreamPosition streamPosition = new StreamPosition();

        if (isRecover) {
            streamPosition.setOffset(this.getOffset());
            streamPosition.setEpoch(this.getEpoch());
        }

        if (this.token.equals("") && this.opts.getTokenGetter() != null) {
            SubscriptionTokenEvent subscriptionTokenEvent = new SubscriptionTokenEvent(this.channel);
            this.opts.getTokenGetter().getSubscriptionToken(subscriptionTokenEvent, (err, token) -> Subscription.this.client.getExecutor().submit(() -> {
                if (Subscription.this.getState() != SubscriptionState.SUBSCRIBING) {
                    return;
                }
                if (err != null) {
                    if (err instanceof UnauthorizedException) {
                        Subscription.this.failUnauthorized(true);
                        return;
                    }
                    Subscription.this.listener.onError(Subscription.this, new SubscriptionErrorEvent(new SubscriptionTokenError(err)));
                    Subscription.this.scheduleResubscribe();
                    return;
                }
                if (token == null || token.equals("")) {
                    Subscription.this.failUnauthorized(false);
                    return;
                }
                Subscription.this.token = token;
                Subscription.this.client.sendSubscribe(Subscription.this, Subscription.this.createSubscribeRequest());
            }));
        } else {
            Subscription.this.client.sendSubscribe(this, this.createSubscribeRequest());
        }
    }

    public void unsubscribe() {
        this.client.getExecutor().submit(() -> {
            Subscription.this._unsubscribe(true, Client.UNSUBSCRIBED_UNSUBSCRIBE_CALLED, "unsubscribe called");
        });
    }

    private void clearSubscribedState() {
        if (this.refreshTask != null) {
            this.refreshTask.cancel(true);
            this.refreshTask = null;
        }
    }

    private void clearSubscribingState() {
        if (this.resubscribeTask != null) {
            this.resubscribeTask.cancel(true);
            this.resubscribeTask = null;
        }
    }

    private void _unsubscribe(boolean sendUnsubscribe, int code, String reason) {
        if (this.getState() == SubscriptionState.UNSUBSCRIBED) {
            return;
        }
        if (this.getState() == SubscriptionState.SUBSCRIBED) {
            this.clearSubscribedState();
        } else if (this.getState() == SubscriptionState.SUBSCRIBING) {
            this.clearSubscribingState();
        }
        this.setState(SubscriptionState.UNSUBSCRIBED);
        if (sendUnsubscribe) {
            this.client.sendUnsubscribe(this.getChannel());
        }
        for(Map.Entry<String, CompletableFuture<Throwable>> entry: this.futures.entrySet()) {
            CompletableFuture<Throwable> f = entry.getValue();
            f.complete(new SubscriptionStateError(this.getState()));
        }
        this.futures.clear();
        this.listener.onUnsubscribed(this, new UnsubscribedEvent(code, reason));
    }

    private void scheduleResubscribe() {
        if (this.getState() != SubscriptionState.SUBSCRIBING) {
            return;
        }
        this.resubscribeTask = this.client.getScheduler().schedule(
                Subscription.this::startResubscribing,
                Subscription.this.backoff.duration(this.resubscribeAttempts, this.opts.getMinResubscribeDelay(), this.opts.getMaxResubscribeDelay()),
                TimeUnit.MILLISECONDS
        );
        this.resubscribeAttempts++;
    }

    void startResubscribing() {
        this.client.getExecutor().submit(this::sendSubscribe);
    }

    boolean getRecover() {
        return this.recover;
    }

    private void failUnauthorized(boolean sendUnsubscribe) {
        this._unsubscribe(sendUnsubscribe, Client.UNSUBSCRIBED_UNAUTHORIZED, "unauthorized");
    }

    public void publish(byte[] data, ResultCallback<PublishResult> cb) {
        this.client.getExecutor().submit(() -> Subscription.this.publishSynchronized(data, cb));
    }

    private void publishSynchronized(byte[] data, ResultCallback<PublishResult> cb) {
        CompletableFuture<Throwable> f = new CompletableFuture<>();
        String uuid = UUID.randomUUID().toString();
        this.futures.put(uuid, f);
        f.thenAccept(err -> {
            if (err != null) {
                cb.onDone(err, null);
                return;
            }
            this.futures.remove(uuid);
            this.client.publish(this.getChannel(), data, cb);
        }).orTimeout(this.client.getOpts().getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            Subscription.this.futures.remove(uuid);
            cb.onDone(e, null);
            return null;
        });
        if (this.getState() == SubscriptionState.SUBSCRIBED) {
            f.complete(null);
        }
    }

    public void history(HistoryOptions opts, ResultCallback<HistoryResult> cb) {
        this.client.getExecutor().submit(() -> Subscription.this.historySynchronized(opts, cb));
    }

    private void historySynchronized(HistoryOptions opts, ResultCallback<HistoryResult> cb) {
        CompletableFuture<Throwable> f = new CompletableFuture<>();
        String uuid = UUID.randomUUID().toString();
        this.futures.put(uuid, f);
        f.thenAccept(err -> {
            if (err != null) {
                cb.onDone(err, null);
                return;
            }
            this.futures.remove(uuid);
            this.client.history(this.getChannel(), opts, cb);
        }).orTimeout(this.client.getOpts().getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            Subscription.this.futures.remove(uuid);
            cb.onDone(e, null);
            return null;
        });
        if (this.getState() == SubscriptionState.SUBSCRIBED) {
            f.complete(null);
        }
    }

    public void presence(ResultCallback<PresenceResult> cb) {
        this.client.getExecutor().submit(() -> Subscription.this.presenceSynchronized(cb));
    }

    private void presenceSynchronized(ResultCallback<PresenceResult> cb) {
        CompletableFuture<Throwable> f = new CompletableFuture<>();
        String uuid = UUID.randomUUID().toString();
        this.futures.put(uuid, f);
        f.thenAccept(err -> {
            if (err != null) {
                cb.onDone(err, null);
                return;
            }
            this.futures.remove(uuid);
            this.client.presence(this.getChannel(), cb);
        }).orTimeout(this.client.getOpts().getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            Subscription.this.futures.remove(uuid);
            cb.onDone(e, null);
            return null;
        });
        if (this.getState() == SubscriptionState.SUBSCRIBED) {
            f.complete(null);
        }
    }

    public void presenceStats(ResultCallback<PresenceStatsResult> cb) {
        this.client.getExecutor().submit(() -> Subscription.this.presenceStatsSynchronized(cb));
    }

    private void presenceStatsSynchronized(ResultCallback<PresenceStatsResult> cb) {
        CompletableFuture<Throwable> f = new CompletableFuture<>();
        String uuid = UUID.randomUUID().toString();
        this.futures.put(uuid, f);
        f.thenAccept(err -> {
            if (err != null) {
                cb.onDone(err, null);
                return;
            }
            this.futures.remove(uuid);
            this.client.presenceStats(this.getChannel(), cb);
        }).orTimeout(this.client.getOpts().getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            Subscription.this.futures.remove(uuid);
            cb.onDone(e, null);
            return null;
        });
        if (this.getState() == SubscriptionState.SUBSCRIBED) {
            f.complete(null);
        }
    }

    private byte[] getPrevData() {
        return prevData;
    }

    private void setPrevData(byte[] prevData) {
        this.prevData = prevData;
    }
}
