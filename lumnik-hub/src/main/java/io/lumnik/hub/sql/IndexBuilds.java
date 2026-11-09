package io.lumnik.hub.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * What the hub's background {@code CREATE INDEX CONCURRENTLY} builders share ({@code IngestedAtIndex}, #945;
 * {@link ManagedIndex}, #1838): a session advisory lock that serialises the nodes on one index name, and a
 * digest to keep a name inside PostgreSQL's limit without two names colliding.
 */
public final class IndexBuilds {

    private IndexBuilds() { }

    /** True when this session now holds the lock on {@code name}; false when another node is on it. */
    public static boolean tryLock(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    public static void unlock(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            ps.setString(1, name);
            ps.execute();
        }
    }

    /** The first 8 bytes of SHA-256, hex-encoded (as {@code Identifiers.digest} does with 5). */
    public static String digest(String raw) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
