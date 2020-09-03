package io.github.centrifugal.centrifuge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import java8.util.concurrent.CompletableFuture;

public class Subscription {

    private Client client;
    private String channel;
    private long lastOffset;
    private boolean recoverable;
    private boolean needRecover;
    private String lastEpoch;
    private SubscriptionEventListener listener;
    private SubscriptionState state = SubscriptionState.UNSUBSCRIBED;
    private Map<String, CompletableFuture<ReplyError>> futures = new ConcurrentHashMap<>();

    Boolean getNeedResubscribe() {
        return needResubscribe;
    }

    private Boolean needResubscribe = true;

    SubscriptionState getState() {
        return state;
    }

    public String getChannel() {
        return channel;
    }

    SubscriptionEventListener getListener() {
        return listener;
    }

    Subscription(final Client client, final String channel, final SubscriptionEventListener listener) {
        this.client = client;
        this.channel = channel;
        this.listener = listener;
    }

    long getLastOffset() {
        return lastOffset;
    }

    void setLastOffset(long lastOffset) {
        this.lastOffset = lastOffset;
    }

    String getLastEpoch() {
        return lastEpoch;
    }

    private void setLastEpoch(String lastEpoch) {
        this.lastEpoch = lastEpoch;
    }

    void moveToUnsubscribed() {
        this.state = SubscriptionState.UNSUBSCRIBED;
    }

    void moveToSubscribeSuccess(Protocol.SubscribeResult result, boolean recover) {
        this.state = SubscriptionState.SUBSCRIBED;
        this.setRecoverable(result.getRecoverable());
        this.setLastEpoch(result.getEpoch());

        SubscribeSuccessEvent event = new SubscribeSuccessEvent(recover, result.getRecovered());
        this.listener.onSubscribeSuccess(this, event);

        if (result.getPublicationsCount() > 0) {
            for (Protocol.Publication publication : result.getPublicationsList()) {
                PublishEvent publishEvent = new PublishEvent();
                publishEvent.setData(publication.getData().toByteArray());
                publishEvent.setOffset(publication.getOffset());
                this.listener.onPublish(this, publishEvent);
                this.setLastOffset(publication.getOffset());
            }
        } else {
            this.setLastOffset(result.getOffset());
        }

        for(Map.Entry<String, CompletableFuture<ReplyError>> entry: this.futures.entrySet()) {
            CompletableFuture<ReplyError> f = entry.getValue();
            f.complete(null);
        }
        this.futures.clear();
    }

    void moveToSubscribeError(ReplyError err) {
        this.state = SubscriptionState.ERROR;
        SubscribeErrorEvent event = new SubscribeErrorEvent();
        event.setCode(err.getCode());
        event.setMessage(err.getMessage());
        this.listener.onSubscribeError(this, event);

        for(Map.Entry<String, CompletableFuture<ReplyError>> entry: this.futures.entrySet()) {
            CompletableFuture<ReplyError> f = entry.getValue();
            f.complete(err);
        }
        this.futures.clear();
    }

    public void subscribe() {
        this.client.getExecutor().submit(() -> {
            Subscription.this.needResubscribe = true;
            if (Subscription.this.state == SubscriptionState.SUBSCRIBED) {
                return;
            }
            Subscription.this.client.subscribe(Subscription.this);
            Subscription.this.setNeedRecover(true);
        });
    }

    public void unsubscribe() {
        this._unsubscribe(true);
        this.setNeedRecover(false);
    }

    void unsubscribeNoResubscribe() {
        this.needResubscribe = false;
        this._unsubscribe(false);
        this.setNeedRecover(false);
    }

    private void _unsubscribe(boolean shouldSendUnsubscribe) {
        SubscriptionState previousState = this.state;
        this.moveToUnsubscribed();
        if (previousState == SubscriptionState.SUBSCRIBED) {
            this.listener.onUnsubscribe(this, new UnsubscribeEvent());
        }
        if (shouldSendUnsubscribe) {
            this.client.sendUnsubscribe(this);
        }
    }

    public void publish(byte[] data, ReplyCallback<PublishResult> cb) {
        this.client.getExecutor().submit(() -> Subscription.this.publishSynchronized(data, cb));
    }

    private void publishSynchronized(byte[] data, ReplyCallback<PublishResult> cb) {
        CompletableFuture<ReplyError> f = new CompletableFuture<>();
        String uuid = UUID.randomUUID().toString();
        this.futures.put(uuid, f);
        f.thenAccept(reply -> {
            if (reply != null) {
                cb.onDone(reply, null);
                return;
            }
            this.futures.remove(uuid);
            this.client.publish(this.getChannel(), data, cb);
        }).orTimeout(this.client.getOpts().getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            Subscription.this.futures.remove(uuid);
            cb.onFailure(e);
            return null;
        });
        if (this.state == SubscriptionState.SUBSCRIBED) {
            f.complete(null);
        }
    }

    public void history(ReplyCallback<HistoryResult> cb) {
        this.client.getExecutor().submit(() -> Subscription.this.historySynchronized(cb));
    }

    private void historySynchronized(ReplyCallback<HistoryResult> cb) {
        CompletableFuture<ReplyError> f = new CompletableFuture<>();
        String uuid = UUID.randomUUID().toString();
        this.futures.put(uuid, f);
        f.thenAccept(reply -> {
            if (reply != null) {
                cb.onDone(reply, null);
                return;
            }
            this.futures.remove(uuid);
            this.client.history(this.getChannel(), cb);
        }).orTimeout(this.client.getOpts().getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            Subscription.this.futures.remove(uuid);
            cb.onFailure(e);
            return null;
        });
        if (this.state == SubscriptionState.SUBSCRIBED) {
            f.complete(null);
        }
    }

    public void presence(ReplyCallback<PresenceResult> cb) {
        this.client.getExecutor().submit(() -> Subscription.this.presenceSynchronized(cb));
    }

    private void presenceSynchronized(ReplyCallback<PresenceResult> cb) {
        CompletableFuture<ReplyError> f = new CompletableFuture<>();
        String uuid = UUID.randomUUID().toString();
        this.futures.put(uuid, f);
        f.thenAccept(reply -> {
            if (reply != null) {
                cb.onDone(reply, null);
                return;
            }
            this.futures.remove(uuid);
            this.client.presence(this.getChannel(), cb);
        }).orTimeout(this.client.getOpts().getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            Subscription.this.futures.remove(uuid);
            cb.onFailure(e);
            return null;
        });
        if (this.state == SubscriptionState.SUBSCRIBED) {
            f.complete(null);
        }
    }

    public void presenceStats(ReplyCallback<PresenceStatsResult> cb) {
        this.client.getExecutor().submit(() -> Subscription.this.presenceStatsSynchronized(cb));
    }

    private void presenceStatsSynchronized(ReplyCallback<PresenceStatsResult> cb) {
        CompletableFuture<ReplyError> f = new CompletableFuture<>();
        String uuid = UUID.randomUUID().toString();
        this.futures.put(uuid, f);
        f.thenAccept(reply -> {
            if (reply != null) {
                cb.onDone(reply, null);
                return;
            }
            this.futures.remove(uuid);
            this.client.presenceStats(this.getChannel(), cb);
        }).orTimeout(this.client.getOpts().getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            Subscription.this.futures.remove(uuid);
            cb.onFailure(e);
            return null;
        });
        if (this.state == SubscriptionState.SUBSCRIBED) {
            f.complete(null);
        }
    }

    boolean isRecoverable() {
        return recoverable;
    }

    public void setRecoverable(boolean recoverable) {
        this.recoverable = recoverable;
    }

    boolean getNeedRecover() {
        return this.needRecover;
    }

    void setNeedRecover(boolean needRecover) {
        this.needRecover = needRecover;
    }
}
