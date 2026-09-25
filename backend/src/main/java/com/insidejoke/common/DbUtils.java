package com.insidejoke.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

/** JDBC conversions for PostgreSQL {@code timestamp with time zone} and uuid columns. */
public final class DbUtils {

    private DbUtils() {}

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    public static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** An enum constant as an SQL string literal, for fixed values in queries ('REFUNDED'); never user input. */
    public static String sql(Enum<?> value) {
        return "'" + value.name() + "'";
    }

    /** Enum constants as an SQL list for {@code IN}: ('ERROR', 'TIMEOUT'). */
    public static String sqlList(Collection<? extends Enum<?>> values) {
        return values.stream().map(DbUtils::sql).collect(Collectors.joining(", ", "(", ")"));
    }
}
