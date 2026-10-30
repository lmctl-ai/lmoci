package ai.lmctl.lmoci;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared local SQLite credential store at {@code ~/.lmconn/creds.db}.
 *
 * <p>Uses the same table schema as {@code lmconn} and {@code lmclient} so credentials saved once can
 * be resolved by service and profile name without duplicating secrets in scripts or command lines.
 */
public final class CredStore {
    private static final Set<String> SECRET_KEYS = Set.of(
            "password",
            "secret",
            "secret_key",
            "secretaccesskey",
            "token",
            "access_token",
            "refresh_token",
            "api_key",
            "apikey",
            "private_key",
            "privatekey",
            "passphrase",
            "client_secret",
            "connection_string",
            "uri",
            "url");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CredStore() {}

    public static Path dbPath(String overridePath) {
        if (overridePath != null && !overridePath.isBlank()) {
            return Path.of(overridePath);
        }
        for (String envName : new String[] {"LMOCI_DB", "LMCLIENT_DB", "LMCONN_DB"}) {
            String value = System.getenv(envName);
            if (value != null && !value.isBlank()) {
                return Path.of(value);
            }
        }
        Path home = Path.of(System.getProperty("user.home", "."));
        Path preferred = home.resolve(".lmconn").resolve("creds.db");
        Path legacy = home.resolve(".lmconnectors").resolve("creds.db");
        if (!Files.exists(preferred) && Files.exists(legacy)) {
            return legacy;
        }
        return preferred;
    }

    public static Map<String, Object> put(
            String overridePath, String service, String name, String kind, Map<String, Object> fields) {
        validatePart("service", service);
        validatePart("name", name);
        String normalizedService = service.toLowerCase(Locale.ROOT);
        String normalizedKind = (kind == null || kind.isBlank() ? normalizedService : kind).toLowerCase(Locale.ROOT);
        String now = Instant.now().toString();
        String json = Jsons.stringify(fields);
        try (Connection connection = open(overridePath);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO credentials(service, name, kind, payload, updated_at) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT(service, name) DO UPDATE SET kind = excluded.kind, "
                                + "payload = excluded.payload, updated_at = excluded.updated_at")) {
            statement.setString(1, normalizedService);
            statement.setString(2, name);
            statement.setString(3, normalizedKind);
            statement.setString(4, json);
            statement.setString(5, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw Envelope.fail("CRED_DB_ERROR", exception.getMessage(), exception);
        }
        return recordView(normalizedService, name, normalizedKind, now, fields, false);
    }

    public static Map<String, Object> get(String overridePath, String service, String name, boolean reveal) {
        Map<String, Object> raw = getRawRecord(overridePath, service, name);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) raw.get("fields");
        return recordView(
                String.valueOf(raw.get("service")),
                String.valueOf(raw.get("name")),
                String.valueOf(raw.get("kind")),
                String.valueOf(raw.get("updated_at")),
                fields,
                reveal);
    }

    public static Map<String, Object> getRawFields(String overridePath, String service, String name) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) getRawRecord(overridePath, service, name).get("fields");
        return fields;
    }

    public static Map<String, Object> findRawFields(String overridePath, String service, String name) {
        Path path = dbPath(overridePath);
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            return getRawFields(overridePath, service, name);
        } catch (Envelope.AreaException ignored) {
            return Map.of();
        }
    }

    public static List<Map<String, Object>> list(String overridePath, String service) {
        String sql = service == null || service.isBlank()
                ? "SELECT service, name, kind, payload, updated_at FROM credentials ORDER BY service, name"
                : "SELECT service, name, kind, payload, updated_at FROM credentials WHERE service = ? ORDER BY name";
        try (Connection connection = open(overridePath);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (service != null && !service.isBlank()) {
                statement.setString(1, service.toLowerCase(Locale.ROOT));
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rows.next()) {
                    Map<String, Object> fields = parseJsonObject(rows.getString(4));
                    out.add(recordView(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(5),
                            fields,
                            false));
                }
                return out;
            }
        } catch (SQLException exception) {
            throw Envelope.fail("CRED_DB_ERROR", exception.getMessage(), exception);
        }
    }

    public static Map<String, Object> delete(String overridePath, String service, String name) {
        validatePart("service", service);
        validatePart("name", name);
        String normalizedService = service.toLowerCase(Locale.ROOT);
        try (Connection connection = open(overridePath);
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM credentials WHERE service = ? AND name = ?")) {
            statement.setString(1, normalizedService);
            statement.setString(2, name);
            int changes = statement.executeUpdate();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("service", normalizedService);
            result.put("name", name);
            result.put("deleted", changes > 0);
            return result;
        } catch (SQLException exception) {
            throw Envelope.fail("CRED_DB_ERROR", exception.getMessage(), exception);
        }
    }

    private static Map<String, Object> getRawRecord(String overridePath, String service, String name) {
        validatePart("service", service);
        validatePart("name", name);
        String normalizedService = service.toLowerCase(Locale.ROOT);
        try (Connection connection = open(overridePath);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT service, name, kind, payload, updated_at FROM credentials WHERE service = ? AND name = ?")) {
            statement.setString(1, normalizedService);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw Envelope.fail("CRED_NOT_FOUND", "credential not found: " + normalizedService + "/" + name);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("service", rows.getString(1));
                out.put("name", rows.getString(2));
                out.put("kind", rows.getString(3));
                out.put("fields", parseJsonObject(rows.getString(4)));
                out.put("updated_at", rows.getString(5));
                return out;
            }
        } catch (SQLException exception) {
            throw Envelope.fail("CRED_DB_ERROR", exception.getMessage(), exception);
        }
    }

    private static Map<String, Object> recordView(
            String service,
            String name,
            String kind,
            String updatedAt,
            Map<String, Object> fields,
            boolean reveal) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", service);
        out.put("name", name);
        out.put("kind", kind);
        out.put("updated_at", updatedAt);
        out.put("fields", redact(fields, reveal));
        return out;
    }

    public static Map<String, Object> redact(Map<String, Object> fields, boolean reveal) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!reveal && isSecretKey(key) && value != null && !String.valueOf(value).isEmpty()) {
                out.put(key, "***REDACTED***");
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (SECRET_KEYS.contains(lower)) {
            return true;
        }
        return lower.endsWith("_password")
                || lower.endsWith("_secret")
                || lower.endsWith("_token")
                || lower.endsWith("_key")
                || lower.endsWith("_passphrase");
    }

    private static void validatePart(String label, String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw Envelope.usage("invalid " + label + " '" + value + "'; use letters, digits, '.', '_', or '-'");
        }
    }

    private static Connection open(String overridePath) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
        }
        Path file = dbPath(overridePath).toAbsolutePath();
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
                chmodQuietly(dir, true);
            }
        } catch (Exception exception) {
            throw new SQLException("failed to create credential directory: " + exception.getMessage(), exception);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        chmodQuietly(file, false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS credentials ("
                            + "service TEXT NOT NULL,"
                            + "name TEXT NOT NULL,"
                            + "kind TEXT NOT NULL,"
                            + "payload TEXT NOT NULL,"
                            + "updated_at TEXT NOT NULL,"
                            + "PRIMARY KEY (service, name))");
        }
        return connection;
    }

    private static void chmodQuietly(Path path, boolean dir) {
        try {
            Set<PosixFilePermission> perms = dir
                    ? EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception ignored) {
            // Non-POSIX filesystems such as Windows skip chmod.
        }
    }

    private static Map<String, Object> parseJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception exc) {
            throw Envelope.fail("CRED_JSON_ERROR", "invalid credential payload JSON: " + exc.getMessage(), exc);
        }
    }
}
