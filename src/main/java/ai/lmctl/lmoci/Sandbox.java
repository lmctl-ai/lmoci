package ai.lmctl.lmoci;

import io.lmctl.lmcode.Json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Owns the OCI resources created by a single sandbox run, logs every intent/created/deleted
 * transition to a JSONL ledger, and guarantees reverse-creation teardown on normal exit or error.
 */
public final class Sandbox implements AutoCloseable {
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern SUFFIX_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,48}");

    private final OciBackend backend;
    private final String region;
    private final String runId;
    private final Path ledger;
    private final boolean dryRun;
    private final Map<String, Entry> resources = new LinkedHashMap<>();
    private boolean closed;

    public Sandbox(OciBackend backend, String region, String runId, Path ledgerPath, boolean dryRun) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.region = backend.resolveRegion(region);
        if (runId == null || !RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new IllegalArgumentException("runId must be 1-32 lowercase letters, digits, '_' or '-'");
        }
        this.runId = runId;
        this.dryRun = dryRun;
        this.ledger = openLedger(Objects.requireNonNull(ledgerPath, "ledgerPath"), runId);
    }

    public String runId() {
        return runId;
    }

    public Path ledgerPath() {
        return ledger;
    }

    public synchronized Map<String, Object> create(String kind, String suffix, Map<?, ?> options) {
        ensureOpen();
        String normalizedKind = requireKind(kind);
        String fullName = resourceName(normalizedKind, suffix);
        Map<String, String> tags = Map.of(
                "lmoci-managed", "true",
                "lmoci-run-id", runId,
                "lmoci-kind", normalizedKind);
        append(record("intent", normalizedKind, fullName, fullName, "pending", "create"));
        if (dryRun) {
            String dryId = "dryrun-" + normalizedKind + "-" + fullName;
            Entry entry = new Entry(normalizedKind, dryId, fullName);
            resources.put(key(normalizedKind, dryId), entry);
            append(record("created", normalizedKind, dryId, fullName, "active", "create"));
            return Rows.row(
                    "kind", normalizedKind,
                    "type", normalizedKind,
                    "id", dryId,
                    "name", fullName,
                    "region", region,
                    "run_id", runId,
                    "dry_run", true,
                    "status", "DRY_RUN");
        }
        try {
            Map<String, Object> created = backend.createOwnedResource(normalizedKind, region, fullName, tags, options);
            String id = Rows.str(created, "id", "");
            if (id.isBlank()) {
                throw new IllegalStateException("create returned no resource id for " + normalizedKind);
            }
            Entry entry = new Entry(normalizedKind, id, fullName);
            resources.put(key(normalizedKind, id), entry);
            append(record("created", normalizedKind, id, fullName, "active", "create"));
            Map<String, Object> result = new LinkedHashMap<>(created);
            result.put("kind", normalizedKind);
            result.put("run_id", runId);
            return Collections.unmodifiableMap(result);
        } catch (RuntimeException exc) {
            append(record("failure", normalizedKind, fullName, fullName, "pending", "create:" + exc.getClass().getSimpleName()));
            throw exc;
        }
    }

    public synchronized Map<String, Object> describe(Map<String, Object> resource) {
        ensureOpen();
        Entry entry = requireOwnedEntry(resource);
        if (dryRun) {
            return Rows.row(
                    "kind", entry.kind,
                    "type", entry.kind,
                    "id", entry.id,
                    "name", entry.name,
                    "region", region,
                    "status", "DRY_RUN");
        }
        return backend.describeOwnedResource(entry.kind, region, entry.id, Map.of());
    }

    public synchronized Map<String, Object> delete(Map<String, Object> resource) {
        ensureOpen();
        Entry entry = requireOwnedEntry(resource);
        return deleteEntry(entry);
    }

    public synchronized int remaining() {
        return resources.size();
    }

    public synchronized Map<String, Object> cleanup() {
        if (closed) {
            return Rows.row("cleaned", 0, "remaining", resources.size(), "run_id", runId);
        }
        RuntimeException firstFailure = null;
        int cleaned = 0;
        List<Entry> copy = new ArrayList<>(resources.values());
        Collections.reverse(copy);
        for (Entry entry : copy) {
            try {
                deleteEntry(entry);
                cleaned++;
            } catch (RuntimeException exc) {
                if (firstFailure == null) {
                    firstFailure = exc;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return Rows.row("cleaned", cleaned, "remaining", resources.size(), "run_id", runId);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            cleanup();
        } finally {
            closed = true;
        }
    }

    private Map<String, Object> deleteEntry(Entry entry) {
        append(record("intent", entry.kind, entry.id, entry.name, "active", "delete"));
        try {
            Map<String, Object> res = dryRun
                    ? Rows.row("type", entry.kind, "id", entry.id, "deleted", true, "status", "DELETED")
                    : backend.deleteOwnedResource(entry.kind, region, entry.id, Map.of());
            append(record("deleted", entry.kind, entry.id, entry.name, "deleted", "delete"));
            resources.remove(key(entry.kind, entry.id));
            return res;
        } catch (RuntimeException exc) {
            append(record("failure", entry.kind, entry.id, entry.name, "active", "delete:" + exc.getClass().getSimpleName()));
            throw exc;
        }
    }

    public static List<Map<String, Object>> leftovers(Path directory, long olderHours) {
        if (olderHours < 0) {
            throw new IllegalArgumentException("olderHours must be non-negative");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return out;
        }
        Instant cutoff = Instant.now().minus(olderHours, ChronoUnit.HOURS);
        try (var files = Files.list(directory)) {
            files.filter(p -> {
                        String fileName = p.getFileName().toString();
                        return fileName.startsWith("lmoci-") && fileName.endsWith(".jsonl");
                    })
                    .sorted()
                    .forEach(p -> scanLedger(p, cutoff, out));
        } catch (IOException exc) {
            throw new IllegalStateException("cannot scan ledger directory: " + exc.getMessage(), exc);
        }
        return out;
    }

    private static void scanLedger(Path path, Instant cutoff, List<Map<String, Object>> out) {
        try {
            if (Files.getLastModifiedTime(path).toInstant().isAfter(cutoff)) {
                return;
            }
            Map<String, Map<String, String>> latestById = new LinkedHashMap<>();
            Map<String, String> nameToId = new LinkedHashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Map<String, String> row = parseRow(trimmed);
                String kind = row.get("kind");
                String id = row.get("id");
                String name = row.getOrDefault("name", id);
                if (kind == null || id == null) {
                    continue;
                }
                if ("created".equals(row.get("event"))) {
                    nameToId.put(kind + "\u0000" + name, id);
                    latestById.remove(kind + "\u0000" + name);
                }
                String canonicalId = nameToId.getOrDefault(kind + "\u0000" + id, id);
                latestById.put(kind + "\u0000" + canonicalId, row);
            }
            for (Map<String, String> record : latestById.values()) {
                String state = record.getOrDefault("state", "");
                if ("active".equals(state) || "pending".equals(state)) {
                    out.add(Rows.row(
                            "ledger", path.toString(),
                            "kind", record.getOrDefault("kind", ""),
                            "id", record.getOrDefault("id", ""),
                            "name", record.getOrDefault("name", ""),
                            "run_id", record.getOrDefault("run_id", ""),
                            "state", state,
                            "record", Jsons.stringify(record)));
                }
            }
        } catch (Exception exc) {
            out.add(Rows.row(
                    "ledger", path.toString(),
                    "record", "manual review: unreadable or malformed ledger"));
        }
    }

    private static Map<String, String> parseRow(String jsonLine) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : Json.object(Json.parse(jsonLine)).entrySet()) {
            out.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return out;
    }

    private Entry requireOwnedEntry(Map<String, Object> resource) {
        if (resource == null) {
            throw new IllegalArgumentException("resource is required");
        }
        String kind = Rows.str(resource, "kind", Rows.str(resource, "type", ""));
        String id = Rows.str(resource, "id", "");
        Entry entry = resources.get(key(kind, id));
        if (entry == null) {
            throw new IllegalArgumentException("resource is not owned by this sandbox: " + kind + "/" + id);
        }
        return entry;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("sandbox is closed");
        }
    }

    private static String requireKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!OciBackend.OWNED_RESOURCE_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported sandbox resource kind: " + kind + " (expected one of "
                            + String.join(", ", OciBackend.OWNED_RESOURCE_TYPES) + ")");
        }
        return normalized;
    }

    private String resourceName(String kind, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("resource name is required");
        }
        String prefix = "lmoci-" + runId + "-";
        String suffix = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        if (!SUFFIX_PATTERN.matcher(suffix).matches()) {
            throw new IllegalArgumentException("resource name suffix contains unsafe characters: " + name);
        }
        String full = (prefix + suffix).toLowerCase(Locale.ROOT);
        if (full.length() > 63) {
            full = full.substring(0, 63);
        }
        return full;
    }

    private static String key(String kind, String id) {
        return kind + "\u0000" + id;
    }

    private static Path openLedger(Path requested, String runId) {
        try {
            Path target = requested;
            if (Files.isDirectory(target) || !target.getFileName().toString().endsWith(".jsonl")) {
                Files.createDirectories(target);
                target = target.resolve("lmoci-" + runId + "-" + System.nanoTime() + ".jsonl");
            } else if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.newByteChannel(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close();
            return target;
        } catch (IOException exc) {
            throw new IllegalStateException("cannot create sandbox ledger file: " + exc.getMessage(), exc);
        }
    }

    private void append(Map<String, String> record) {
        try (FileChannel channel = FileChannel.open(ledger, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            byte[] bytes = (Jsons.stringify(record) + "\n").getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException exc) {
            throw new IllegalStateException("cannot append sandbox ledger: " + exc.getMessage(), exc);
        }
    }

    private Map<String, String> record(String event, String kind, String id, String name, String state, String action) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("time", Instant.now().toString());
        row.put("event", event);
        row.put("kind", kind);
        row.put("id", id);
        row.put("name", name);
        row.put("run_id", runId);
        row.put("state", state);
        row.put("action", action);
        return row;
    }

    private record Entry(String kind, String id, String name) {}
}
