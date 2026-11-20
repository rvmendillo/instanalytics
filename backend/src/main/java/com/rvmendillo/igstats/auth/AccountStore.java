package com.rvmendillo.igstats.auth;

import com.rvmendillo.igstats.ig.IgMetricCatalog.LoginType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Connected accounts. Plain JDBC - the schema is narrow and JPA adds nothing here. */
@Repository
public class AccountStore {

    private final JdbcTemplate jdbc;
    private final TokenCipher cipher;

    public AccountStore(JdbcTemplate jdbc, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public List<Account> active() {
        return jdbc.query("""
                select id, ig_user_id, username, login_type, access_token_enc,
                       token_expires_at, token_refreshed_at, connected_at
                from ig_account where active order by id
                """, (rs, i) -> new Account(
                rs.getLong("id"),
                rs.getString("ig_user_id"),
                rs.getString("username"),
                LoginType.valueOf(rs.getString("login_type")),
                cipher.decrypt(rs.getString("access_token_enc")),
                rs.getTimestamp("token_expires_at").toInstant(),
                toInstant(rs.getTimestamp("token_refreshed_at")),
                rs.getTimestamp("connected_at").toInstant()));
    }

    public long upsert(String igUserId, String username, LoginType login,
                       String token, Instant expiresAt) {
        return jdbc.queryForObject("""
                insert into ig_account (ig_user_id, username, login_type, access_token_enc,
                                        token_expires_at, token_refreshed_at)
                values (?, ?, ?, ?, ?, now())
                on conflict (ig_user_id) do update set
                    username = excluded.username,
                    login_type = excluded.login_type,
                    access_token_enc = excluded.access_token_enc,
                    token_expires_at = excluded.token_expires_at,
                    token_refreshed_at = now(),
                    active = true
                returning id
                """, Long.class,
                igUserId, username, login.name(), cipher.encrypt(token), Timestamp.from(expiresAt));
    }

    public void updateToken(long accountId, String token, Instant expiresAt) {
        jdbc.update("""
                update ig_account set access_token_enc = ?, token_expires_at = ?,
                                      token_refreshed_at = now()
                where id = ?
                """, cipher.encrypt(token), Timestamp.from(expiresAt), accountId);
    }

    /** Token is dead. Stop collecting until the user reconnects. */
    public void deactivate(long accountId) {
        jdbc.update("update ig_account set active = false where id = ?", accountId);
    }

    public void touchSynced(long accountId) {
        jdbc.update("update ig_account set last_synced_at = now() where id = ?", accountId);
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public record Account(long id, String igUserId, String username, LoginType loginType,
                          String token, Instant tokenExpiresAt, Instant tokenRefreshedAt,
                          Instant connectedAt) {

        /** When this token was last issued - refresh age is measured from here. */
        public Instant tokenIssuedAt() {
            return tokenRefreshedAt != null ? tokenRefreshedAt : connectedAt;
        }
    }
}
