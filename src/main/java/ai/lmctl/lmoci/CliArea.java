package ai.lmctl.lmoci;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Direct JSON-envelope CLI areas ({@code lmoci os ...}, {@code lmoci iam ...},
 * {@code lmoci queue ...}, {@code lmoci stream ...}, {@code lmoci vault ...},
 * {@code lmoci inventory ...}).
 */
public final class CliArea {
    private CliArea() {}

    public static boolean isArea(String token) {
        if (token == null) {
            return false;
        }
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "cred", "os", "objectstorage", "iam", "identity", "queue", "stream", "streaming", "vault", "kms", "inventory" -> true;
            default -> false;
        };
    }

    public static int run(String area, List<String> args) {
        String normalized = area.toLowerCase(Locale.ROOT);
        if ("cred".equals(normalized)) {
            return CredArea.run(args);
        }
        try {
            Parsed parsed = parse(args);
            OciBackend backend = createBackend(parsed);
            String region = parsed.opt("region", backend.defaultRegion());
            Map<String, Object> options = new LinkedHashMap<>(parsed.options);
            return switch (normalized) {
                case "os", "objectstorage" -> runOs(backend, region, parsed, options);
                case "iam", "identity" -> runIam(backend, region, parsed, options);
                case "queue" -> runQueue(backend, region, parsed, options);
                case "stream", "streaming" -> runStream(backend, region, parsed, options);
                case "vault", "kms" -> runVault(backend, region, parsed, options);
                case "inventory" -> runInventory(backend, region, parsed, options);
                default -> throw Envelope.usage("unknown area: " + area);
            };
        } catch (Envelope.AreaException exc) {
            return Envelope.error(exc);
        } catch (IllegalArgumentException exc) {
            return Envelope.error(2, "USAGE", exc.getMessage());
        } catch (Exception exc) {
            return Envelope.error(3, "OCI_ERROR", exc.getMessage());
        }
    }

    private static OciBackend createBackend(Parsed parsed) {
        Map<String, String> env = System.getenv();
        RuntimeBudget budget = RuntimeBudget.fromEnv(env);
        CostLedger cost = CostLedger.fromEnv(env);
        OciBackend backend = new OciBackend(env, cost, budget);
        String profile = parsed.opt("profile", System.getProperty("lmoci.profile", ""));
        if (profile != null && !profile.isBlank()) {
            backend.setProfile(profile);
        }
        String region = parsed.opt("region", System.getProperty("lmoci.region", ""));
        if (region != null && !region.isBlank()) {
            backend.setDefaultRegion(region);
        }
        String endpoint = parsed.opt("endpoint", System.getProperty("lmoci.endpoint", ""));
        if (endpoint != null && !endpoint.isBlank()) {
            backend.setEndpoint(backend.defaultRegion(), endpoint);
        }
        boolean demo = "true".equals(parsed.opt("demo", null)) || Boolean.getBoolean("lmoci.dryRun");
        if (demo) {
            backend.setMode("demo");
        } else {
            if (backend.endpointFor(backend.defaultRegion()).isBlank() && !"true".equals(parsed.opt("live", null))) {
                backend.setEndpoint(backend.defaultRegion(), "http://127.0.0.1:4599");
            }
            backend.setMode("live");
        }
        return backend;
    }

    private static int runOs(OciBackend backend, String region, Parsed parsed, Map<String, Object> options) throws Exception {
        String action = action(parsed, "os");
        return switch (action) {
            case "namespace", "ns" -> Envelope.ok(Rows.row("namespace", backend.resolveNamespace(region, options)));
            case "list-buckets", "buckets" -> Envelope.ok(backend.inventory("buckets", region, options));
            case "create-bucket", "bucket-create", "mb" -> {
                String bucket = requireArg(parsed, "bucket", 1, "bucket name");
                yield Envelope.ok(backend.bucketCreate(region, bucket, options));
            }
            case "head-bucket", "bucket-head" -> {
                String bucket = requireArg(parsed, "bucket", 1, "bucket name");
                yield Envelope.ok(backend.bucketHead(region, bucket, options));
            }
            case "delete-bucket", "bucket-delete", "rb" -> {
                String bucket = requireArg(parsed, "bucket", 1, "bucket name");
                yield Envelope.ok(backend.bucketDelete(region, bucket, options));
            }
            case "put-object", "object-put", "put" -> {
                String bucket = requireArg(parsed, "bucket", 1, "bucket name");
                String key = requireArg(parsed, "name", 2, "object name");
                String content = parsed.opt("content", null);
                String file = parsed.opt("file", null);
                if (content == null && file != null) {
                    content = Files.readString(Path.of(file), StandardCharsets.UTF_8);
                }
                if (content == null) {
                    content = parsed.positionals.size() > 3 ? parsed.positionals.get(3) : "";
                }
                yield Envelope.ok(backend.objectPut(region, bucket, key, content, options));
            }
            case "get-object", "object-get", "get" -> {
                String bucket = requireArg(parsed, "bucket", 1, "bucket name");
                String key = requireArg(parsed, "name", 2, "object name");
                yield Envelope.ok(backend.objectGet(region, bucket, key, options));
            }
            case "head-object", "object-head", "head" -> {
                String bucket = requireArg(parsed, "bucket", 1, "bucket name");
                String key = requireArg(parsed, "name", 2, "object name");
                yield Envelope.ok(backend.objectHead(region, bucket, key, options));
            }
            case "list-objects", "object-list", "ls", "list" -> {
                String bucket = requireArg(parsed, "bucket", 1, "bucket name");
                String prefix = parsed.opt("prefix", parsed.positionals.size() > 2 ? parsed.positionals.get(2) : "");
                yield Envelope.ok(backend.objectList(region, bucket, prefix, options));
            }
            case "rename-object", "object-rename", "mv", "rename" -> {
                String bucket = requireArg(parsed, "bucket", 1, "bucket name");
                String src = requireArg(parsed, "source", 2, "source object name");
                String dst = requireArg(parsed, "new-name", 3, "new object name");
                yield Envelope.ok(backend.objectRename(region, bucket, src, dst, options));
            }
            case "delete-object", "object-delete", "rm", "delete" -> {
                String bucket = requireArg(parsed, "bucket", 1, "bucket name");
                String key = requireArg(parsed, "name", 2, "object name");
                yield Envelope.ok(backend.objectDelete(region, bucket, key, options));
            }
            default -> throw Envelope.usage("unknown os action: " + action);
        };
    }

    private static int runIam(OciBackend backend, String region, Parsed parsed, Map<String, Object> options) {
        String action = action(parsed, "iam");
        return switch (action) {
            case "tenancy", "identity" -> Envelope.ok(backend.inventory("identity", region, options));
            case "regions" -> Envelope.ok(backend.inventory("regions", region, options));
            case "availability-domains", "ads" -> Envelope.ok(backend.inventory("availability_domains", region, options));
            case "compartments", "list-compartments" -> Envelope.ok(backend.inventory("compartments", region, options));
            case "users", "list-users" -> Envelope.ok(backend.inventory("users", region, options));
            case "groups", "list-groups" -> Envelope.ok(backend.inventory("groups", region, options));
            case "policies", "list-policies" -> Envelope.ok(backend.inventory("policies", region, options));
            case "create-compartment" -> {
                String name = requireArg(parsed, "name", 1, "compartment name");
                yield Envelope.ok(backend.createOwnedResource("compartment", region, name, Map.of("lmoci-managed", "true"), options));
            }
            case "delete-compartment" -> {
                String id = requireArg(parsed, "id", 1, "compartment id");
                yield Envelope.ok(backend.deleteOwnedResource("compartment", region, id, options));
            }
            case "analyze-policy" -> {
                String policy = requireArg(parsed, "policy", 1, "policy statement or document");
                yield Envelope.ok(PolicyAnalyzer.analyze(policy));
            }
            default -> throw Envelope.usage("unknown iam action: " + action);
        };
    }

    private static int runQueue(OciBackend backend, String region, Parsed parsed, Map<String, Object> options) {
        String action = action(parsed, "queue");
        return switch (action) {
            case "list", "ls" -> Envelope.ok(backend.inventory("queues", region, options));
            case "create" -> {
                String name = requireArg(parsed, "name", 1, "queue name");
                yield Envelope.ok(backend.createOwnedResource("queue", region, name, Map.of("lmoci-managed", "true"), options));
            }
            case "get", "describe" -> {
                String id = requireArg(parsed, "id", 1, "queue id");
                yield Envelope.ok(backend.describeOwnedResource("queue", region, id, options));
            }
            case "delete", "rm" -> {
                String id = requireArg(parsed, "id", 1, "queue id");
                yield Envelope.ok(backend.deleteOwnedResource("queue", region, id, options));
            }
            case "put", "send" -> {
                String id = requireArg(parsed, "id", 1, "queue id");
                String content = requireArg(parsed, "content", 2, "message content");
                yield Envelope.ok(backend.queuePut(region, id, content, options));
            }
            case "receive", "get-messages" -> {
                String id = requireArg(parsed, "id", 1, "queue id");
                yield Envelope.ok(backend.queueReceive(region, id, options));
            }
            case "delete-message", "ack" -> {
                String id = requireArg(parsed, "id", 1, "queue id");
                String receipt = requireArg(parsed, "receipt", 2, "message receipt");
                yield Envelope.ok(backend.queueDeleteMessage(region, id, receipt, options));
            }
            default -> throw Envelope.usage("unknown queue action: " + action);
        };
    }

    private static int runStream(OciBackend backend, String region, Parsed parsed, Map<String, Object> options) {
        String action = action(parsed, "stream");
        return switch (action) {
            case "list", "ls" -> Envelope.ok(backend.inventory("streams", region, options));
            case "create" -> {
                String name = requireArg(parsed, "name", 1, "stream name");
                yield Envelope.ok(backend.createOwnedResource("stream", region, name, Map.of("lmoci-managed", "true"), options));
            }
            case "get", "describe" -> {
                String id = requireArg(parsed, "id", 1, "stream id");
                yield Envelope.ok(backend.describeOwnedResource("stream", region, id, options));
            }
            case "delete", "rm" -> {
                String id = requireArg(parsed, "id", 1, "stream id");
                yield Envelope.ok(backend.deleteOwnedResource("stream", region, id, options));
            }
            case "put", "publish" -> {
                String id = requireArg(parsed, "id", 1, "stream id");
                String key = parsed.opt("key", parsed.positionals.size() > 2 ? parsed.positionals.get(2) : "k0");
                String value = requireArg(parsed, "value", 3, "message value");
                yield Envelope.ok(backend.streamPut(region, id, key, value, options));
            }
            case "cursor", "create-cursor" -> {
                String id = requireArg(parsed, "id", 1, "stream id");
                String partition = parsed.opt("partition", parsed.positionals.size() > 2 ? parsed.positionals.get(2) : "0");
                String type = parsed.opt("type", parsed.positionals.size() > 3 ? parsed.positionals.get(3) : "TRIM_HORIZON");
                yield Envelope.ok(backend.streamCursor(region, id, partition, type, options));
            }
            case "get-messages", "consume" -> {
                String id = requireArg(parsed, "id", 1, "stream id");
                String cursor = requireArg(parsed, "cursor", 2, "stream cursor");
                yield Envelope.ok(backend.streamGetMessages(region, id, cursor, options));
            }
            default -> throw Envelope.usage("unknown stream action: " + action);
        };
    }

    private static int runVault(OciBackend backend, String region, Parsed parsed, Map<String, Object> options) {
        String action = action(parsed, "vault");
        return switch (action) {
            case "list", "list-vaults" -> Envelope.ok(backend.inventory("vaults", region, options));
            case "create-vault", "create" -> {
                String name = requireArg(parsed, "name", 1, "vault name");
                yield Envelope.ok(backend.vaultCreate(region, name, options));
            }
            case "create-key" -> {
                String name = requireArg(parsed, "name", 1, "key name");
                yield Envelope.ok(backend.keyCreate(region, name, options));
            }
            case "encrypt" -> {
                String keyId = requireArg(parsed, "key-id", 1, "key id");
                String plaintext = requireArg(parsed, "plaintext", 2, "plaintext");
                yield Envelope.ok(backend.kmsEncrypt(region, keyId, plaintext, options));
            }
            case "decrypt" -> {
                String keyId = requireArg(parsed, "key-id", 1, "key id");
                String ciphertext = requireArg(parsed, "ciphertext", 2, "ciphertext");
                yield Envelope.ok(backend.kmsDecrypt(region, keyId, ciphertext, options));
            }
            case "list-secrets", "secrets" -> Envelope.ok(backend.inventory("secrets", region, options));
            case "create-secret" -> {
                String name = requireArg(parsed, "name", 1, "secret name");
                String vaultId = requireArg(parsed, "vault-id", 2, "vault id");
                String keyId = requireArg(parsed, "key-id", 3, "key id");
                String secretText = requireArg(parsed, "secret", 4, "secret text");
                yield Envelope.ok(backend.secretCreate(region, name, vaultId, keyId, secretText, options));
            }
            case "get-secret" -> {
                String secretId = requireArg(parsed, "secret-id", 1, "secret id");
                boolean reveal = "true".equals(parsed.opt("reveal", null));
                yield Envelope.ok(backend.secretGet(region, secretId, reveal, options));
            }
            default -> throw Envelope.usage("unknown vault action: " + action);
        };
    }

    private static int runInventory(OciBackend backend, String region, Parsed parsed, Map<String, Object> options) {
        if (parsed.positionals.isEmpty() || "all".equalsIgnoreCase(parsed.positionals.get(0)) || "snapshot".equalsIgnoreCase(parsed.positionals.get(0))) {
            return Envelope.ok(backend.snapshot(region, options));
        }
        String section = parsed.positionals.get(0);
        return Envelope.ok(backend.inventory(section, region, options));
    }

    private static String action(Parsed parsed, String area) {
        if (parsed.positionals.isEmpty()) {
            throw Envelope.usage(area + " requires an action subcommand");
        }
        return parsed.positionals.get(0).toLowerCase(Locale.ROOT);
    }

    private static String requireArg(Parsed parsed, String flagName, int posIndex, String description) {
        String val = parsed.opt(flagName, null);
        if (val == null && parsed.positionals.size() > posIndex) {
            val = parsed.positionals.get(posIndex);
        }
        if (val == null || val.isBlank()) {
            throw Envelope.usage("missing required " + description + " (use --" + flagName + " <val> or positional argument)");
        }
        return val;
    }

    private static final class Parsed {
        final Map<String, String> options = new LinkedHashMap<>();
        final List<String> positionals = new ArrayList<>();

        String opt(String key, String fallback) {
            return options.getOrDefault(key, fallback);
        }
    }

    private static Parsed parse(List<String> args) {
        Parsed out = new Parsed();
        for (int index = 0; index < args.size(); index++) {
            String token = args.get(index);
            if (!token.startsWith("--")) {
                out.positionals.add(token);
                continue;
            }
            String body = token.substring(2);
            if ("demo".equals(body) || "live".equals(body) || "reveal".equals(body)) {
                out.options.put(body, "true");
                continue;
            }
            int eq = body.indexOf('=');
            if (eq >= 0) {
                out.options.put(body.substring(0, eq), body.substring(eq + 1));
            } else {
                if (index + 1 >= args.size()) {
                    throw Envelope.usage("flag --" + body + " requires a value");
                }
                out.options.put(body, args.get(++index));
            }
        }
        return out;
    }
}
