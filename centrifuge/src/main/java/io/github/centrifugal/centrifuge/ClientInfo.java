package io.github.centrifugal.centrifuge;

import io.github.centrifugal.centrifuge.internal.proto.Protocol;

public class ClientInfo {
    private String user;

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        this.client = client;
    }

    private String client;

    public byte[] getConnInfo() {
        return connInfo;
    }

    public void setConnInfo(byte[] connInfo) {
        this.connInfo = connInfo;
    }

    public byte[] getChanInfo() {
        return chanInfo;
    }

    public void setChanInfo(byte[] chanInfo) {
        this.chanInfo = chanInfo;
    }

    private byte[] connInfo;
    private byte[] chanInfo;

    static ClientInfo fromProtocolClientInfo(Protocol.ClientInfo protoClientInfo) {
        ClientInfo clientInfo = new ClientInfo();
        clientInfo.setUser(protoClientInfo.getUser());
        clientInfo.setClient(protoClientInfo.getClient());
        clientInfo.setConnInfo(protoClientInfo.getConnInfo().toByteArray());
        clientInfo.setChanInfo(protoClientInfo.getChanInfo().toByteArray());
        return clientInfo;
    };
}
