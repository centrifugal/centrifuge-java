package io.github.centrifugal.centrifuge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tiny HS256 JWT signer used only by integration tests. Hand-rolled so the
 * library has no extra runtime/test dependency on a JWT lib.
 */
final class JwtUtil {

    private JwtUtil() {}

    static String connectionToken(String user, String secret) {
        return connectionToken(user, secret, 0L);
    }

    /**
     * Issue a connection token. {@code expSecondsFromNow == 0} omits {@code exp}
     * (no expiry); positive values give a future expiry; negative values give
     * an already-expired token (useful for testing refresh paths).
     */
    static String connectionToken(String user, String secret, long expSecondsFromNow) {
        long iat = System.currentTimeMillis() / 1000L;
        StringBuilder payload = new StringBuilder("{\"sub\":\"")
                .append(escape(user))
                .append("\",\"iat\":").append(iat);
        if (expSecondsFromNow != 0) {
            payload.append(",\"exp\":").append(iat + expSecondsFromNow);
        }
        payload.append('}');
        return signHs256(payload.toString(), secret);
    }

    static String subscriptionToken(String user, String channel, String secret) {
        long iat = System.currentTimeMillis() / 1000L;
        String payload = "{\"sub\":\"" + escape(user)
                + "\",\"channel\":\"" + escape(channel)
                + "\",\"iat\":" + iat + "}";
        return signHs256(payload, secret);
    }

    private static String signHs256(String payloadJson, String secret) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String encodedHeader = enc.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + enc.encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 unavailable", e);
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
