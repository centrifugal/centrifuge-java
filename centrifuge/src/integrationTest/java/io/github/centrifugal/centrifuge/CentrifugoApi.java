package io.github.centrifugal.centrifuge;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin wrapper around Centrifugo's HTTP API used to drive server-side actions
 * from integration tests (publish, disconnect, unsubscribe). Assumes the API
 * is exposed in insecure mode at {@code http://localhost:8000/api/...}, which
 * matches docker-compose.yml.
 */
final class CentrifugoApi {

    private static final MediaType JSON = MediaType.get("application/json");
    private final OkHttpClient http;
    private final String baseUrl;

    CentrifugoApi() {
        this(System.getProperty("centrifuge.apiUrl", "http://localhost:8000/api"));
    }

    CentrifugoApi(String baseUrl) {
        this.http = new OkHttpClient();
        this.baseUrl = baseUrl;
    }

    /** Server-side publish to {@code channel}; data is sent as a JSON string literal. */
    void publish(String channel, String data) throws IOException {
        String body = "{\"channel\":\"" + escape(channel) + "\","
                + "\"data\":\"" + escape(data) + "\"}";
        post("/publish", body);
    }

    /** Disconnect every connection for {@code user} with the given code/reason. */
    void disconnectUser(String user, int code, String reason) throws IOException {
        String body = "{\"user\":\"" + escape(user) + "\","
                + "\"disconnect\":{\"code\":" + code
                + ",\"reason\":\"" + escape(reason) + "\"}}";
        post("/disconnect", body);
    }

    /**
     * Disconnect a single connection identified by user + client id.
     * Centrifugo's API requires {@code user} to be non-empty even when
     * targeting a specific {@code clientId}.
     */
    void disconnectClient(String user, String clientId, int code, String reason) throws IOException {
        String body = "{\"user\":\"" + escape(user) + "\","
                + "\"client\":\"" + escape(clientId) + "\","
                + "\"disconnect\":{\"code\":" + code
                + ",\"reason\":\"" + escape(reason) + "\"}}";
        post("/disconnect", body);
    }

    /** Force-unsubscribe a user from {@code channel} server-side. */
    void unsubscribe(String user, String channel) throws IOException {
        String body = "{\"user\":\"" + escape(user) + "\","
                + "\"channel\":\"" + escape(channel) + "\"}";
        post("/unsubscribe", body);
    }

    private void post(String path, String body) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                ResponseBody rb = resp.body();
                throw new IOException("Centrifugo API " + path + " HTTP " + resp.code()
                        + ": " + (rb != null ? rb.string() : ""));
            }
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
