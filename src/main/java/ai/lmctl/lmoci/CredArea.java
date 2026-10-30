package ai.lmctl.lmoci;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CLI handler for {@code lmoci cred ...}. */
public final class CredArea {
    private static final List<String> KNOWN_FLAGS = List.of(
            "tenancy",
            "user",
            "fingerprint",
            "private-key",
            "private-key-file",
            "passphrase",
            "region",
            "namespace",
            "compartment-id",
            "endpoint",
            "host",
            "port",
            "username",
            "password",
            "database",
            "url",
            "uri",
            "token",
            "secret",
            "api-key");

    private CredArea() {}

    public static int run(List<String> args) {
        try {
            if (args.isEmpty() || isHelp(args.get(0))) {
                return Envelope.ok(help());
            }
            String action = args.get(0);
            List<String> rest = args.subList(1, args.size());
            return switch (action) {
                case "set", "put", "save" -> set(rest);
                case "get", "show" -> get(rest);
                case "list", "ls" -> list(rest);
                case "delete", "rm", "remove" -> delete(rest);
                case "path" -> path(rest);
                default -> throw Envelope.usage("unknown cred action '" + action + "' (expected: set, get, list, delete, path)");
            };
        } catch (Envelope.AreaException exception) {
            return Envelope.error(exception);
        } catch (Exception exception) {
            return Envelope.error(3, "CRED_ERROR", exception.getMessage());
        }
    }

    private static int set(List<String> args) throws Exception {
        Parsed parsed = parse(args);
        String service = serviceArg(parsed, 0);
        String name = nameArg(parsed, 1);
        Map<String, Object> fields = new LinkedHashMap<>();
        String kind = parsed.opt("kind", service);
        for (String flag : KNOWN_FLAGS) {
            String value = parsed.opt(flag, null);
            if (value != null) {
                if (flag.equals("private-key-file")) {
                    fields.put("private_key", Files.readString(Path.of(value), StandardCharsets.UTF_8));
                } else {
                    fields.put(flag.replace('-', '_'), flag.equals("port") ? Integer.parseInt(value) : value);
                }
            }
        }
        for (Map.Entry<String, String> entry : parsed.fields.entrySet()) {
            fields.put(entry.getKey(), entry.getValue());
        }
        String passwordStdin = parsed.opt("password-stdin", null);
        if ("true".equals(passwordStdin)) {
            fields.put("password", new String(System.in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("[\\r\\n]+$", ""));
        }
        String keyStdin = parsed.opt("private-key-stdin", null);
        if ("true".equals(keyStdin)) {
            fields.put("private_key", new String(System.in.readAllBytes(), StandardCharsets.UTF_8).trim());
        }
        if (fields.isEmpty()) {
            throw Envelope.usage("cred set requires at least one --field key=value or standard flag (--tenancy, --user, --fingerprint, --private-key-file, --region, ...)");
        }
        return Envelope.ok(CredStore.put(parsed.opt("db", null), service, name, kind, fields));
    }

    private static int get(List<String> args) {
        Parsed parsed = parse(args);
        String service = serviceArg(parsed, 0);
        String name = nameArg(parsed, 1);
        boolean reveal = "true".equals(parsed.opt("reveal", null));
        return Envelope.ok(CredStore.get(parsed.opt("db", null), service, name, reveal));
    }

    private static int list(List<String> args) {
        Parsed parsed = parse(args);
        String service = parsed.opt("service", parsed.positionals.isEmpty() ? null : parsed.positionals.get(0));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("db", CredStore.dbPath(parsed.opt("db", null)).toString());
        result.put("credentials", CredStore.list(parsed.opt("db", null), service));
        return Envelope.ok(result);
    }

    private static int delete(List<String> args) {
        Parsed parsed = parse(args);
        String service = serviceArg(parsed, 0);
        String name = nameArg(parsed, 1);
        return Envelope.ok(CredStore.delete(parsed.opt("db", null), service, name));
    }

    private static int path(List<String> args) {
        Parsed parsed = parse(args);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("db", CredStore.dbPath(parsed.opt("db", null)).toString());
        return Envelope.ok(result);
    }

    private static String serviceArg(Parsed parsed, int index) {
        String value = parsed.opt("service", parsed.positionals.size() > index ? parsed.positionals.get(index) : "oci");
        if (value == null || value.isBlank()) {
            throw Envelope.usage("cred action requires <service> <name> or --service <service> --name <name>");
        }
        return value;
    }

    private static String nameArg(Parsed parsed, int index) {
        String value = parsed.opt("name", null);
        if (value == null) {
            if (parsed.positionals.size() > index) {
                value = parsed.positionals.get(index);
            } else if (parsed.positionals.size() == 1 && parsed.opt("service", null) == null) {
                value = parsed.positionals.get(0);
            }
        }
        if (value == null || value.isBlank()) {
            throw Envelope.usage("cred action requires <service> <name> or --name <name>");
        }
        return value;
    }

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg) || "help".equals(arg);
    }

    private static Map<String, Object> help() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("area", "cred");
        out.put("db", CredStore.dbPath(null).toString());
        out.put("actions", List.of("set", "get", "list", "delete", "path"));
        out.put("examples", List.of(
                "lmoci cred set oci default --tenancy ocid1.tenancy.oc1..example --user ocid1.user.oc1..example --fingerprint 11:22:33 --private-key-file ~/.oci/oci_api_key.pem --region us-ashburn-1",
                "lmoci cred list",
                "lmoci cred get oci default",
                "lmoci cred delete oci default"));
        return out;
    }

    private static final class Parsed {
        final Map<String, String> options = new LinkedHashMap<>();
        final Map<String, String> fields = new LinkedHashMap<>();
        final java.util.ArrayList<String> positionals = new java.util.ArrayList<>();

        String opt(String name, String fallback) {
            return options.getOrDefault(name, fallback);
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
            if ("reveal".equals(body) || "password-stdin".equals(body) || "private-key-stdin".equals(body)) {
                out.options.put(body, "true");
                continue;
            }
            String key;
            String value;
            int eq = body.indexOf('=');
            if (eq >= 0) {
                key = body.substring(0, eq);
                value = body.substring(eq + 1);
            } else {
                if (index + 1 >= args.size()) {
                    throw Envelope.usage("flag --" + body + " requires a value");
                }
                key = body;
                value = args.get(++index);
            }
            if ("field".equals(key)) {
                int feq = value.indexOf('=');
                if (feq <= 0) {
                    throw Envelope.usage("--field requires key=value");
                }
                out.fields.put(value.substring(0, feq), value.substring(feq + 1));
            } else {
                out.options.put(key, value);
            }
        }
        return out;
    }
}
