package com.wiselite.transfer.idempotency;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes an operation safe to retry: the first request with a given (owner, key) runs the
 * operation; later requests with the same key and body get the stored response back.
 *
 * <p>How concurrency works: the key row is inserted first, inside the same transaction as
 * the operation. A concurrent request with the same key blocks on that INSERT (unique
 * primary key) until the first transaction ends:
 * <ul>
 *   <li>first commits → the INSERT inserts nothing, and the stored response is replayed;</li>
 *   <li>first rolls back (error, crash) → the INSERT succeeds and this request runs the operation.</li>
 * </ul>
 * So there is no "in progress" state to clean up after a crash. The cost is that the
 * transaction stays open for the whole operation, which is fine because the operation only
 * touches this database. External calls happen asynchronously (M4/M6).
 *
 * <p>Only successful outcomes are stored. A failed request (e.g. insufficient funds) rolls
 * back and leaves no trace, so retrying it with the same key re-runs it. That is safe because
 * the failed attempt had no side effects.
 */
@Service
public class IdempotencyService {

    public record StoredResponse(int status, String body, boolean replayed) {}

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public StoredResponse execute(UUID ownerId, String key, String requestHash, Supplier<StoredResponse> operation) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-255 characters");
        }
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (owner_id, idempotency_key, request_hash, response_status, response_body)
                        VALUES (?, ?, ?, 0, '')
                        ON CONFLICT (owner_id, idempotency_key) DO NOTHING""")
                .params(ownerId, key, requestHash)
                .update();

        if (inserted == 0) {
            var existing = jdbc.sql("SELECT request_hash, response_status, response_body FROM idempotency_keys WHERE owner_id = ? AND idempotency_key = ?")
                    .params(ownerId, key)
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getString(3)})
                    .single();
            if (!existing[0].equals(requestHash)) {
                throw new IdempotencyKeyReusedException(key);
            }
            return new StoredResponse((Integer) existing[1], (String) existing[2], true);
        }

        var response = operation.get();
        jdbc.sql("UPDATE idempotency_keys SET response_status = ?, response_body = ? WHERE owner_id = ? AND idempotency_key = ?")
                .params(response.status(), response.body(), ownerId, key)
                .update();
        return response;
    }
}
