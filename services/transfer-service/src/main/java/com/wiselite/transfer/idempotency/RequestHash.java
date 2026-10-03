package com.wiselite.transfer.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fingerprint of a request, used to detect a key being reused for a different request. */
public final class RequestHash {

    private RequestHash() {}

    public static String of(String method, String path, String canonicalBody) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((method + " " + path + "\n" + canonicalBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
