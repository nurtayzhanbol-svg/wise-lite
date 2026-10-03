package com.wiselite.rails.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 over the raw body, so the receiver can prove a webhook came from the rail. */
public final class WebhookSignature {

    private WebhookSignature() {}

    public static String sign(String secret, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison: a plain equals() leaks how many leading characters matched. */
    public static boolean verify(String secret, String body, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(secret, body).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }
}
