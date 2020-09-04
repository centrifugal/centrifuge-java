package io.github.centrifugal.centrifuge;

public abstract class EventListener {

    public void onConnect(Client client, ConnectEvent event) {

    };

    public void onDisconnect(Client client, DisconnectEvent event) {

    };

    public void onError(Client client, ErrorEvent event) {

    };

    public void onMessage(Client client, MessageEvent event) {

    };

    public void onSubscribe(Client client, ServerSubscribeEvent event) {

    };

    public void onUnsubscribe(Client client, ServerUnsubscribeEvent event) {

    };

    public void onPublish(Client client, ServerPublishEvent event) {

    };

    public void onJoin(Client client, ServerJoinEvent event) {

    };

    public void onLeave(Client client, ServerLeaveEvent event) {

    };

    public void onRefresh(Client client, RefreshEvent event, TokenCallback cb) {
        cb.Fail(new UnsupportedOperationException());
    };

    public void onPrivateSub(Client client, PrivateSubEvent event, TokenCallback cb) {
        cb.Fail(new UnsupportedOperationException());
    };
}
