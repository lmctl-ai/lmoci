package ai.lmctl.lmoci;

import io.lmctl.lmcode.Context;
import io.lmctl.lmcode.Registry;
import io.lmctl.lmcode.Value;
import io.lmctl.lmcode.VmException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed OCI SDK host registry and sandbox lifecycle manager for lmscript. */
public final class CloudHost implements AutoCloseable {
    private final Map<String, String> env;
    private final RuntimeBudget runtimeBudget;
    private final CostLedger costLedger;
    private final List<Sandbox> runs = new ArrayList<>();
    private boolean closed;

    public final record Session(OciBackend backend, String region) {}

    public CloudHost() {
        this(System.getenv());
    }

    public CloudHost(Map<String, String> env) {
        this.env = env;
        this.runtimeBudget = RuntimeBudget.fromEnv(env);
        this.costLedger = CostLedger.fromEnv(env);
    }

    public RuntimeBudget runtimeBudget() {
        return runtimeBudget;
    }

    public CostLedger costLedger() {
        return costLedger;
    }

    private Session openSession(String region, boolean demo, String endpointOverride) {
        OciBackend backend = new OciBackend(env, costLedger, runtimeBudget);
        String sysProfile = System.getProperty("lmoci.profile", "");
        if (!sysProfile.isBlank()) {
            backend.setProfile(sysProfile);
        }
        String sysRegion = System.getProperty("lmoci.region", "");
        String targetRegion = (region == null || region.isBlank())
                ? (!sysRegion.isBlank() ? sysRegion : backend.defaultRegion())
                : region.trim();
        backend.setDefaultRegion(targetRegion);

        String sysEndpoint = System.getProperty("lmoci.endpoint", "");
        String ep = (endpointOverride != null && !endpointOverride.isBlank())
                ? endpointOverride.trim()
                : (!sysEndpoint.isBlank() ? sysEndpoint.trim() : backend.endpointFor(targetRegion));
        if (!ep.isBlank()) {
            backend.setEndpoint(targetRegion, ep);
        }

        boolean dryRun = Boolean.getBoolean("lmoci.dryRun");
        if (demo || dryRun) {
            backend.setMode("demo");
        } else {
            backend.setMode("live");
        }
        return new Session(backend, targetRegion);
    }

    private Session session(Value value) {
        Object obj = value.asObject();
        if (obj instanceof Session sess) {
            return sess;
        }
        return openSession("", true, "");
    }

    public Registry registry() {
        Registry r = new Registry();
        r.context("oci", "Cloud");
        r.function("check", List.of("bool"), "void", (c, s, a) -> {
            if (!a[0].asBool()) {
                throw VmException.host("check failed");
            }
            return Value.none();
        });

        r.method("Cloud", "demo", List.of(), "Cloud",
                (c, s, a) -> Value.object("Cloud", openSession("us-ashburn-1", true, "")));
        r.method("Cloud", "region", List.of("string"), "Cloud",
                (c, s, a) -> Value.object("Cloud", openSession(text(a[0]), false, "")));
        r.method("Cloud", "endpoint", List.of("string", "string"), "Cloud",
                (c, s, a) -> Value.object("Cloud", openSession(text(a[0]), false, text(a[1]))));
        r.method("Cloud", "profile", List.of("string"), "Cloud", (c, s, a) -> {
            Session current = session(s);
            current.backend().setProfile(text(a[0]));
            return Value.object("Cloud", current);
        });
        r.method("Cloud", "is_demo", List.of(), "bool",
                (c, s, a) -> Value.ofBool("demo".equals(session(s).backend().mode())));
        r.method("Cloud", "snapshot", List.of(), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().snapshot(sess.region(), Map.of()));
        });

        for (String section : OciBackend.INVENTORY_SECTIONS) {
            r.method("Cloud", section, List.of(), "Rows", (c, s, a) -> {
                Session sess = session(s);
                return rowsValue(c, sess.backend().inventory(section, sess.region(), Map.of()));
            });
        }

        // Direct Object Storage operations
        r.method("Cloud", "namespace", List.of(), "string", (c, s, a) -> {
            Session sess = session(s);
            return Value.ofString(sess.backend().resolveNamespace(sess.region(), Map.of()));
        });
        r.method("Cloud", "bucket_create", List.of("string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().bucketCreate(sess.region(), text(a[0]), Map.of()));
        });
        r.method("Cloud", "bucket_head", List.of("string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().bucketHead(sess.region(), text(a[0]), Map.of()));
        });
        r.method("Cloud", "bucket_delete", List.of("string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().bucketDelete(sess.region(), text(a[0]), Map.of()));
        });
        r.method("Cloud", "object_put", List.of("string", "string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().objectPut(sess.region(), text(a[0]), text(a[1]), text(a[2]), Map.of()));
        });
        r.method("Cloud", "object_get", List.of("string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().objectGet(sess.region(), text(a[0]), text(a[1]), Map.of()));
        });
        r.method("Cloud", "object_head", List.of("string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().objectHead(sess.region(), text(a[0]), text(a[1]), Map.of()));
        });
        r.method("Cloud", "object_list", List.of("string", "string"), "Rows", (c, s, a) -> {
            Session sess = session(s);
            Map<String, Object> listed = sess.backend().objectList(sess.region(), text(a[0]), text(a[1]), Map.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> objects = (List<Map<String, Object>>) listed.getOrDefault("objects", List.of());
            return rowsValue(c, objects);
        });
        r.method("Cloud", "object_rename", List.of("string", "string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().objectRename(sess.region(), text(a[0]), text(a[1]), text(a[2]), Map.of()));
        });
        r.method("Cloud", "object_delete", List.of("string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().objectDelete(sess.region(), text(a[0]), text(a[1]), Map.of()));
        });

        // Direct Queue operations
        r.method("Cloud", "queue_put", List.of("string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().queuePut(sess.region(), text(a[0]), text(a[1]), Map.of()));
        });
        r.method("Cloud", "queue_receive", List.of("string"), "Rows", (c, s, a) -> {
            Session sess = session(s);
            Map<String, Object> res = sess.backend().queueReceive(sess.region(), text(a[0]), Map.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) res.getOrDefault("messages", List.of());
            return rowsValue(c, messages);
        });
        r.method("Cloud", "queue_delete_message", List.of("string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().queueDeleteMessage(sess.region(), text(a[0]), text(a[1]), Map.of()));
        });

        // Direct Streaming operations
        r.method("Cloud", "stream_put", List.of("string", "string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().streamPut(sess.region(), text(a[0]), text(a[1]), text(a[2]), Map.of()));
        });
        r.method("Cloud", "stream_cursor", List.of("string", "string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().streamCursor(sess.region(), text(a[0]), text(a[1]), text(a[2]), Map.of()));
        });
        r.method("Cloud", "stream_get_messages", List.of("string", "string"), "Rows", (c, s, a) -> {
            Session sess = session(s);
            Map<String, Object> res = sess.backend().streamGetMessages(sess.region(), text(a[0]), text(a[1]), Map.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) res.getOrDefault("messages", List.of());
            return rowsValue(c, messages);
        });

        // Direct Vault / KMS / Secrets operations
        r.method("Cloud", "vault_create", List.of("string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().vaultCreate(sess.region(), text(a[0]), Map.of()));
        });
        r.method("Cloud", "key_create", List.of("string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().keyCreate(sess.region(), text(a[0]), Map.of()));
        });
        r.method("Cloud", "kms_encrypt", List.of("string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().kmsEncrypt(sess.region(), text(a[0]), text(a[1]), Map.of()));
        });
        r.method("Cloud", "kms_decrypt", List.of("string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().kmsDecrypt(sess.region(), text(a[0]), text(a[1]), Map.of()));
        });
        r.method("Cloud", "secret_create", List.of("string", "string", "string", "string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().secretCreate(
                    sess.region(), text(a[0]), text(a[1]), text(a[2]), text(a[3]), Map.of()));
        });
        r.method("Cloud", "secret_get", List.of("string"), "Row", (c, s, a) -> {
            Session sess = session(s);
            return rowValue(c, sess.backend().secretGet(sess.region(), text(a[0]), false, Map.of()));
        });

        // Policy, Cost, and Sandbox Run
        r.method("Cloud", "analyze_policy", List.of("string"), "Row",
                (c, s, a) -> rowValue(c, PolicyAnalyzer.analyze(text(a[0]))));
        r.method("Cloud", "cost_summary", List.of(), "Row",
                (c, s, a) -> rowValue(c, costLedger.summary()));
        r.method("Cloud", "sweep", List.of("string", "int"), "Rows",
                (c, s, a) -> rowsValue(c, Sandbox.leftovers(Path.of(text(a[0])), a[1].asInt())));
        r.method("Cloud", "run", List.of("string", "string"), "Run", (c, s, a) -> {
            Session sess = session(s);
            boolean dryRun = Boolean.getBoolean("lmoci.dryRun");
            Sandbox sandbox = new Sandbox(sess.backend(), sess.region(), text(a[0]), Path.of(text(a[1])), dryRun);
            runs.add(sandbox);
            return Value.object("Run", sandbox);
        });

        for (String kind : OciBackend.OWNED_RESOURCE_TYPES) {
            r.method("Run", kind, List.of("string"), "Row", (c, s, a) -> {
                Sandbox sandbox = (Sandbox) s.asObject();
                return rowValue(c, sandbox.create(kind, text(a[0]), Map.of()));
            });
        }
        r.method("Run", "describe", List.of("Row"), "Row", (c, s, a) -> {
            Sandbox sandbox = (Sandbox) s.asObject();
            return rowValue(c, sandbox.describe(row(a[0])));
        });
        r.method("Run", "delete", List.of("Row"), "Row", (c, s, a) -> {
            Sandbox sandbox = (Sandbox) s.asObject();
            return rowValue(c, sandbox.delete(row(a[0])));
        });
        r.method("Run", "cleanup", List.of(), "void", (c, s, a) -> {
            ((Sandbox) s.asObject()).cleanup();
            return Value.none();
        });
        r.method("Run", "remaining", List.of(), "int",
                (c, s, a) -> Value.ofInt(((Sandbox) s.asObject()).remaining()));
        r.method("Run", "estimated_cost", List.of(), "float", (c, s, a) -> {
            Object est = costLedger.summary().get("estimated_usd");
            double val = est instanceof Number num ? num.doubleValue() : 0.0;
            return Value.ofFloat(val);
        });

        // Rows & Row methods
        r.method("Rows", "len", List.of(), "int",
                (c, s, a) -> Value.ofInt(((Rows) s.asObject()).rows.size()));
        r.method("Rows", "at", List.of("int"), "Row", (c, s, a) -> {
            Rows rows = (Rows) s.asObject();
            long index = a[0].asInt();
            if (index < 0 || index >= rows.rows.size()) {
                throw VmException.host("row index outside inventory: " + index);
            }
            return Value.object("Row", rows.rows.get((int) index));
        });
        r.method("Rows", "csv", List.of("string"), "void", (c, s, a) -> {
            ((Rows) s.asObject()).write(text(a[0]));
            return Value.none();
        });
        r.method("Rows", "json", List.of(), "string",
                (c, s, a) -> Value.ofString(Jsons.stringify(((Rows) s.asObject()).rows)));
        r.method("Rows", "count", List.of("string", "string"), "int", (c, s, a) -> {
            String key = text(a[0]);
            String val = text(a[1]);
            long count = ((Rows) s.asObject()).rows.stream()
                    .filter(row -> Rows.get(row, key).equals(val))
                    .count();
            return Value.ofInt(count);
        });
        r.method("Rows", "filter", List.of("string", "string"), "Rows", (c, s, a) -> {
            String key = text(a[0]);
            String val = text(a[1]);
            List<Map<String, Object>> filtered = ((Rows) s.asObject()).rows.stream()
                    .filter(row -> Rows.get(row, key).equals(val))
                    .toList();
            return rowsValue(c, filtered);
        });

        r.method("Row", "get", List.of("string"), "string",
                (c, s, a) -> Value.ofString(Rows.get(row(s), text(a[0]))));
        r.method("Row", "json", List.of(), "string",
                (c, s, a) -> Value.ofString(Jsons.stringify(row(s))));
        r.method("Row", "number", List.of("string"), "int", (c, s, a) -> {
            String val = Rows.get(row(s), text(a[0]));
            try {
                return Value.ofInt(val.isEmpty() ? 0L : Long.parseLong(val));
            } catch (NumberFormatException exc) {
                throw VmException.host("field is not an integer: " + val);
            }
        });

        return r;
    }

    public Context context(long budgetBytes) {
        Context c = new Context();
        c.memoryLimitBytes = budgetBytes;
        c.setObject("oci", "Cloud", openSession("us-ashburn-1", true, ""));
        return c;
    }

    private static Value rowsValue(Context c, List<Map<String, Object>> list) {
        return Value.object("Rows", new Rows(list, c));
    }

    private static Value rowValue(Context c, Map<String, Object> source) {
        long size = 128L;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String k = String.valueOf(entry.getKey());
            String v = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            size = Math.addExact(size, 128L + 2L * (k.length() + v.length()));
        }
        c.charge(size);
        return Value.object("Row", Collections.unmodifiableMap(new LinkedHashMap<>(source)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Value value) {
        return (Map<String, Object>) value.asObject();
    }

    private static String text(Value value) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value.asString().bytes()))
                    .toString();
        } catch (CharacterCodingException exc) {
            throw VmException.host("OCI host strings must contain valid UTF-8");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        RuntimeException failure = null;
        for (int index = runs.size() - 1; index >= 0; index--) {
            try {
                runs.get(index).close();
            } catch (RuntimeException exc) {
                if (failure == null) {
                    failure = exc;
                }
            }
        }
        closed = true;
        if (failure != null) {
            throw failure;
        }
    }
}
