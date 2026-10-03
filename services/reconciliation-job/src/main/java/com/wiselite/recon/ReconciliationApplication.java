package com.wiselite.recon;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Compares our books (ledger, payouts) with the rail's statement and reports every break. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ReconProperties.class)
public class ReconciliationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Primary: our own schema. Flyway, JdbcClient and the transaction manager use this one. */
    @Bean
    @Primary
    DataSource dataSource(ReconProperties p) {
        return hikari(p.reconDb(), false);
    }

    @Bean
    @Primary
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    DataSource transfersDataSource(ReconProperties p) {
        return hikari(p.transfersDb(), true);
    }

    @Bean
    DataSource payoutsDataSource(ReconProperties p) {
        return hikari(p.payoutsDb(), true);
    }

    @Bean
    JdbcClient payoutsJdbc(@Qualifier("payoutsDataSource") DataSource ds) {
        return JdbcClient.create(ds);
    }

    /** Read-only connections: the job must never be able to "fix" another service's data. */
    private static HikariDataSource hikari(ReconProperties.Db db, boolean readOnly) {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(db.url());
        ds.setUsername(db.username());
        ds.setPassword(db.password());
        ds.setReadOnly(readOnly);
        ds.setMaximumPoolSize(4);
        return ds;
    }
}
