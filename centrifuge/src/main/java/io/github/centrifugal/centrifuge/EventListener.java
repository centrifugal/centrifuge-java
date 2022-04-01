package io.github.centrifugal.centrifuge;

public abstract class EventListener {

    public void onConnect(Client client, ConnectEvent event) {

    }

    public void onDisconnect(Client client, DisconnectEvent event) {

    }

    public void onFail(Client client, FailEvent event) {

    }

    public void onError(Client client, ErrorEvent event) {

    }

    public void onMessage(Client client, MessageEvent event) {

    }

    public void onSubscribe(Client client, ServerSubscribeEvent event) {

    }

    public void onUnsubscribe(Client client, ServerUnsubscribeEvent event) {

    }

    public void onPublication(Client client, ServerPublicationEvent event) {

    }

    public void onJoin(Client client, ServerJoinEvent event) {

    }

    public void onLeave(Client client, ServerLeaveEvent event) {

    }

    public void onConnectionToken(Client client, ConnectionTokenEvent event, TokenCallback cb) {
        cb.Done(null,"");
    }

    public void onSubscriptionToken(Client client, SubscriptionTokenEvent event, TokenCallback cb) {
        cb.Done(null,"");
    }
}
