package io.github.centrifugal.centrifuge;

public abstract class EventListener {

    public void onConnecting(Client client, ConnectingEvent event) {

    }

    public void onConnected(Client client, ConnectedEvent event) {

    }

    public void onDisconnected(Client client, DisconnectedEvent event) {

    }
    
    public void onError(Client client, ErrorEvent event) {

    }

    public void onMessage(Client client, MessageEvent event) {

    }

    public void onSubscribed(Client client, ServerSubscribedEvent event) {

    }

    public void onSubscribing(Client client, ServerSubscribingEvent event) {

    }

    public void onUnsubscribed(Client client, ServerUnsubscribedEvent event) {

    }

    public void onPublication(Client client, ServerPublicationEvent event) {

    }

    public void onJoin(Client client, ServerJoinEvent event) {

    }

    public void onLeave(Client client, ServerLeaveEvent event) {

    }
}
