package com.insidejoke.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

/** One embedded PostgreSQL per test JVM (io.zonky.test:embedded-postgres), started lazily on first use. */
public final class EmbeddedPg {

    private EmbeddedPg() {}

    public static String jdbcUrl() {
        return Holder.POSTGRES.getJdbcUrl("postgres", "postgres");
    }

    /** Initialization-on-demand holder: the JVM guarantees thread-safe, one-time startup. */
    private static final class Holder {
        static final EmbeddedPostgres POSTGRES = start();

        private static EmbeddedPostgres start() {
            try {
                return EmbeddedPostgres.builder()
                        .setPGStartupWait(Duration.ofSeconds(90))
                        .setServerConfig("fsync", "off")
                        .setServerConfig("synchronous_commit", "off")
                        .setServerConfig("full_page_writes", "off")
                        .setServerConfig("max_connections", "100")
                        .start();
            } catch (IOException e) {
                throw new UncheckedIOException("Embedded PostgreSQL failed to start", e);
            }
        }
    }
}
