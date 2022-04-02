package io.github.centrifugal.centrifuge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
    private SubscriptionState state = SubscriptionState.UNSUBSCRIBED;
    final private Map<String, CompletableFuture<Throwable>> futures = new ConcurrentHashMap<>();
    private final Backoff backoff;
    private ScheduledFuture<?> refreshTask;
    private ScheduledFuture<?> resubscribeTask;
    private int resubscribeAttempts = 0;
    private String token;
    private com.google.protobuf.ByteString data;

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
    }

    Subscription(final Client client, final String channel, final SubscriptionEventListener listener) {
        this(client, channel, listener, new SubscriptionOptions());
    }

    public SubscriptionState getState() throws InterruptedException, ExecutionException {
        Future<SubscriptionState> result = this.client.getExecutor().submit(() -> Subscription.this.state);
        return result.get();
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

    void setOffset(long offset) {
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
        if (this.state != SubscriptionState.SUBSCRIBING) {
            return;
        }
        this.sendSubscribe();
    }

    void sendRefresh() {
        this.client.getExecutor().submit(() -> Subscription.this.client.getListener().onSubscriptionToken(Subscription.this.client, new SubscriptionTokenEvent(this.getChannel()), (err, token) -> {
            if (Subscription.this.state != SubscriptionState.SUBSCRIBED) {
                return;
            }
            if (err != null) {
                Subscription.this.listener.onError(Subscription.this, new SubscriptionErrorEvent(new SubscriptionTokenError(err)));
                Subscription.this.refreshTask = Subscription.this.client.getScheduler().schedule(
                        Subscription.this::sendRefresh,
                        Subscription.this.backoff.duration(0, 10000, 20000),
                        TimeUnit.MILLISECONDS
                );
                return;
            }
            if (token.equals("")) {
                this.failUnauthorized();
                return;
            }
            Subscription.this.token = token;
            Subscription.this.client.subRefreshSynchronized(Subscription.this.channel, token, (error, result) -> {
                if (Subscription.this.state != SubscriptionState.SUBSCRIBED) {
                    return;
                }
                if (error != null) {
                    Subscription.this.listener.onError(Subscription.this, new SubscriptionErrorEvent(new SubscriptionRefreshError(error)));
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
                            Subscription.this.refreshFailed();
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

    void moveToSubscribing() {
        SubscriptionState previousSubState = this.state;
        this.state = SubscriptionState.SUBSCRIBING;
        if (previousSubState == SubscriptionState.SUBSCRIBED) {
            this.listener.onUnsubscribe(this, new UnsubscribeEvent());
        }
    }

    void moveToSubscribed(Protocol.SubscribeResult result) {
        this.state = SubscriptionState.SUBSCRIBED;
        if (result.getRecoverable()) {
            this.recover = true;
        }
        this.setEpoch(result.getEpoch());

        SubscribeEvent event = new SubscribeEvent(result.getRecovered());
        this.listener.onSubscribe(this, event);

        if (result.getPublicationsCount() > 0) {
            for (Protocol.Publication publication : result.getPublicationsList()) {
                PublicationEvent publicationEvent = new PublicationEvent();
                publicationEvent.setData(publication.getData().toByteArray());
                publicationEvent.setOffset(publication.getOffset());
                this.listener.onPublication(this, publicationEvent);
                this.setOffset(publication.getOffset());
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
            this.scheduleResubscribe();
        } else if (err.getCode() == 112) { // Unrecoverable position.
            this.failUnrecoverable();
        } else if (err.isTemporary()) {
            this.scheduleResubscribe();
        } else {
            this.subscribeFailed();
        }
    }

    public void subscribe() {
        this.client.getExecutor().submit(() -> {
            if (Subscription.this.state == SubscriptionState.SUBSCRIBED || Subscription.this.state == SubscriptionState.SUBSCRIBING) {
                return;
            }
            Subscription.this.state = SubscriptionState.SUBSCRIBING;
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

        return builder.build();
    }

    void sendSubscribe() {
        final boolean isRecover = this.getRecover();
        StreamPosition streamPosition = new StreamPosition();

        if (isRecover) {
            streamPosition.setOffset(this.getOffset());
            streamPosition.setEpoch(this.getEpoch());
        }

        if (this.channel.startsWith(this.client.getOpts().getPrivateChannelPrefix()) && this.token.equals("")) {
            SubscriptionTokenEvent subscriptionTokenEvent = new SubscriptionTokenEvent(this.channel);
            this.client.getListener().onSubscriptionToken(this.client, subscriptionTokenEvent, (err, token) -> Subscription.this.client.getExecutor().submit(() -> {
                if (Subscription.this.state != SubscriptionState.SUBSCRIBING) {
                    return;
                }
                if (err != null) {
                    Subscription.this.listener.onError(Subscription.this, new SubscriptionErrorEvent(new SubscriptionTokenError(err)));
                    Subscription.this.scheduleResubscribe();
                    return;
                }
                if (token.equals("")) {
                    Subscription.this.failUnauthorized();
                    return;
                }
                Subscription.this.token = token;
                Subscription.this.client.sendSubscribe(Subscription.this, Subscription.this.createSubscribeRequest());
            }));
        } else {
            this.client.sendSubscribe(this, this.createSubscribeRequest());
        }
    }

    public void unsubscribe() {
        this._unsubscribe(true);
    }

    void unsubscribeNoResubscribe() {
        this._unsubscribe(false);
    }

    private void _unsubscribe(boolean shouldSendUnsubscribe) {
        SubscriptionState previousState = this.state;
        this.state = SubscriptionState.UNSUBSCRIBED;
        if (previousState == SubscriptionState.SUBSCRIBED) {
            this.listener.onUnsubscribe(this, new UnsubscribeEvent());
        }
        if (shouldSendUnsubscribe) {
            this.client.sendUnsubscribe(this.getChannel());
        }
        if (this.resubscribeTask != null) {
            this.resubscribeTask.cancel(true);
            this.resubscribeTask = null;
        }
        if (this.refreshTask != null) {
            this.refreshTask.cancel(true);
            this.refreshTask = null;
        }
        for(Map.Entry<String, CompletableFuture<Throwable>> entry: this.futures.entrySet()) {
            CompletableFuture<Throwable> f = entry.getValue();
            f.complete(new SubscriptionStateError(this.state));
        }
        this.futures.clear();
    }

    private void scheduleResubscribe() {
        if (this.state != SubscriptionState.SUBSCRIBING) {
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

    private void fail(SubscriptionFailReason reason, boolean sendUnsubscribe) {
        if (this.state == SubscriptionState.FAILED) {
            return;
        }
        this._unsubscribe(sendUnsubscribe);
        this.state = SubscriptionState.FAILED;
        this.listener.onFail(this, new SubscriptionFailEvent(reason));
    }

    private void subscribeFailed() {
        this.fail(SubscriptionFailReason.SUBSCRIBE_FAILED, false);
    }

    private void refreshFailed() {
        this.fail(SubscriptionFailReason.REFRESH_FAILED, true);
    }

    void failServer() {
        this.fail(SubscriptionFailReason.SERVER, false);
    }

    private void failUnauthorized() {
        this.fail(SubscriptionFailReason.UNAUTHORIZED, this.state == SubscriptionState.SUBSCRIBED);
    }

    void failUnrecoverable() {
        this.clearPositionState();
        this.fail(SubscriptionFailReason.UNRECOVERABLE, false);
    }

    private void clearPositionState() {
        this.recover = false;
        this.offset = 0;
        this.epoch = "";
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
        if (this.state == SubscriptionState.SUBSCRIBED) {
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
        if (this.state == SubscriptionState.SUBSCRIBED) {
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
        if (this.state == SubscriptionState.SUBSCRIBED) {
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
        if (this.state == SubscriptionState.SUBSCRIBED) {
            f.complete(null);
        }
    }
}
