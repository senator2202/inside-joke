package com.insidejoke.auth;

import com.insidejoke.common.DbUtils;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String COLUMNS = "id, email, display_name, google_sub, role, created_at, last_login_at";

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserEntity> findActiveById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = ? AND deleted_at IS NULL")
                .param(id)
                .query(UserRepository::map)
                .optional();
    }

    public Optional<UserEntity> findActiveByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE lower(email) = lower(?) AND deleted_at IS NULL")
                .param(email)
                .query(UserRepository::map)
                .optional();
    }

    public Optional<UserEntity> findActiveByGoogleSub(String sub) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE google_sub = ? AND deleted_at IS NULL")
                .param(sub)
                .query(UserRepository::map)
                .optional();
    }

    public List<UserEntity> searchByEmail(String fragment, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE deleted_at IS NULL AND lower(email) LIKE lower(?) "
                        + "ORDER BY created_at DESC LIMIT ?")
                .param("%" + fragment.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                .param(limit)
                .query(UserRepository::map)
                .list();
    }

    public UserEntity insert(String email, String displayName, String googleSub, String role, Instant now) {
        return jdbc.sql("INSERT INTO app_user (email, display_name, google_sub, role, created_at, last_login_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS)
                .params(email, displayName, googleSub, role, DbUtils.ts(now), DbUtils.ts(now))
                .query(UserRepository::map)
                .single();
    }

    public UserEntity recordLogin(UUID id, String displayName, String googleSub, String role, Instant now) {
        return jdbc.sql("UPDATE app_user SET last_login_at = ?, display_name = COALESCE(display_name, ?), "
                        + "google_sub = COALESCE(google_sub, ?), role = ? WHERE id = ? RETURNING " + COLUMNS)
                .params(DbUtils.ts(now), displayName, googleSub, role, id)
                .query(UserRepository::map)
                .single();
    }

    /** Anonymizes the account; purchases stay for accounting and reference the anonymized row. */
    public void anonymize(UUID id, Instant now) {
        jdbc.sql("UPDATE app_user SET email = 'deleted+' || id || '@users.invalid', display_name = NULL, "
                        + "google_sub = NULL, country = NULL, deleted_at = ? WHERE id = ?")
                .params(DbUtils.ts(now), id)
                .update();
    }

    public void setRole(UUID id, String role) {
        jdbc.sql("UPDATE app_user SET role = ? WHERE id = ?").params(role, id).update();
    }

    /**
     * Makes every active account listed in {@code adminEmails} (lower-case) an admin and every other one a host; returns
     * how many rows changed.
     */
    public int syncAdminRoles(Collection<String> adminEmails) {
        String listed =
                adminEmails.isEmpty() ? "NULL" : String.join(", ", Collections.nCopies(adminEmails.size(), "?"));
        String role = "CASE WHEN lower(email) IN (" + listed + ") THEN 'ADMIN' ELSE 'HOST' END";
        List<Object> params = new ArrayList<>(adminEmails);
        params.addAll(adminEmails);
        return jdbc.sql("UPDATE app_user SET role = " + role + " WHERE deleted_at IS NULL AND role <> " + role)
                .params(params)
                .update();
    }

    static UserEntity map(ResultSet rs, int row) throws SQLException {
        return new UserEntity(
                DbUtils.uuid(rs, "id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("google_sub"),
                rs.getString("role"),
                DbUtils.instant(rs, "created_at"),
                DbUtils.instant(rs, "last_login_at"));
    }

    public int countCreated(Instant from, Instant to) {
        return jdbc.sql("SELECT count(*) FROM app_user WHERE created_at >= ? AND created_at < ?")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query(Integer.class)
                .single();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ?)")
                .param(id)
                .query(Boolean.class)
                .single();
    }
}
