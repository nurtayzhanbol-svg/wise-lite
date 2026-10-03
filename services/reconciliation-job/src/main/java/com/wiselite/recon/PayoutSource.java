package com.wiselite.recon;

import com.wiselite.recon.Reconciler.PayoutRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class PayoutSource {

    private final JdbcClient jdbc;

    public PayoutSource(@Qualifier("payoutsJdbc") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PayoutRecord> load() {
        return jdbc.sql("SELECT transfer_id, status FROM payouts")
                .query((rs, n) -> new PayoutRecord(rs.getObject(1, UUID.class), rs.getString(2)))
                .list();
    }
}
