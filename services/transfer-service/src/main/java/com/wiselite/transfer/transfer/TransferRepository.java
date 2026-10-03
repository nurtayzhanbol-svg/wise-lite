package com.wiselite.transfer.transfer;

import com.wiselite.transfer.ledger.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private static final String COLUMNS =
            "id, owner_id, source_account_id, amount_minor, currency, recipient_name, recipient_iban, state, created_at, updated_at";

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Transfer t) {
        jdbc.sql("INSERT INTO transfers (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(t.id(), t.ownerId(), t.sourceAccountId(), t.amount().minor(), t.amount().currency().getCurrencyCode(),
                        t.recipient().name(), t.recipient().iban(), t.state().name(),
                        Timestamp.from(t.createdAt()), Timestamp.from(t.updatedAt()))
                .update();
    }

    public Optional<Transfer> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfers WHERE id = ?").param(id).query(TransferRepository::map).optional();
    }

    /** Row lock: serialises concurrent state changes of one transfer (e.g. two webhooks at once). */
    public Optional<Transfer> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfers WHERE id = ? FOR UPDATE").param(id).query(TransferRepository::map).optional();
    }

    public void updateState(UUID id, TransferState state) {
        jdbc.sql("UPDATE transfers SET state = ?, updated_at = now() WHERE id = ?").params(state.name(), id).update();
    }

    public void appendHistory(UUID transferId, TransferState from, TransferState to, String reason) {
        jdbc.sql("INSERT INTO transfer_state_history (transfer_id, from_state, to_state, reason) VALUES (?, ?, ?, ?)")
                .params(transferId, from == null ? null : from.name(), to.name(), reason)
                .update();
    }

    public List<Transfer.StateChange> history(UUID transferId) {
        return jdbc.sql("SELECT from_state, to_state, reason, created_at FROM transfer_state_history WHERE transfer_id = ? ORDER BY id")
                .param(transferId)
                .query((rs, n) -> new Transfer.StateChange(
                        rs.getString("from_state") == null ? null : TransferState.valueOf(rs.getString("from_state")),
                        TransferState.valueOf(rs.getString("to_state")),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static Transfer map(ResultSet rs, int rowNum) throws SQLException {
        return new Transfer(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("source_account_id", UUID.class),
                new Money(rs.getLong("amount_minor"), Currency.getInstance(rs.getString("currency"))),
                new Transfer.Recipient(rs.getString("recipient_name"), rs.getString("recipient_iban")),
                TransferState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
