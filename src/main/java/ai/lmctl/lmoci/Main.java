package ai.lmctl.lmoci;

import io.lmctl.lmcode.Program;
import io.lmctl.lmcode.Registry;
import io.lmctl.lmcode.Reports;
import io.lmctl.lmcode.SelfTest;
import io.lmctl.lmcode.VmException;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Main {
    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "date,host,content-length,expect,connection");
    }

    public static final String VERSION = "0.1.0";

    private static final String HELP = """
            lmoci 0.1.0 — Oracle Cloud Infrastructure SDK for Java scripting host
            usage: lmoci [--dry-run] [--apply] [--live] [--profile NAME] [--region NAME] [--endpoint URL] --dsl FILE
                   lmoci --dsl-schema [--json] | --dsl-signatures [--without-std]
                   lmoci cred <set|get|list|delete|path> [options]
                   lmoci <os|iam|queue|stream|vault|inventory> <action> [options]
                   lmoci --version | --notices | --help | --self-test
                   lmoci --sweep LEDGER_DIR --older-than-hours N

            DSL context:
              oci.demo()                        Offline deterministic OCI fixture session
              oci.region("us-ashburn-1")        Live OCI session for the specified region
              oci.endpoint("us-ashburn-1", URL) Live OCI session targeting an explicit endpoint (e.g. ocisim http://127.0.0.1:4599)

            Credentials:
              Shared SQLite credential store at ~/.lmconn/creds.db (override via LMOCI_DB / LMCLIENT_DB / LMCONN_DB).
              Secret values and private keys are never printed unless `lmoci cred get ... --reveal` is explicitly passed.

            Budgets & Safety:
              LMOCI_MAX_MEMORY_MB: whole MiB between 64 and 65536 (default 3072).
              LMOCI_MAX_COST_USD:  dollar cap between 0.00 and 100.00 (default 2.00).
              Every sandbox run (oci.run(run_id, ledger_dir)) tears down owned resources in reverse order on exit or error.
            """;

    private Main() {}

    public static byte[] schema(Registry registry) {
        Map<String, Object> functions = new TreeMap<>();
        Map<String, Object> types = new TreeMap<>();
        Map<String, Object> context = new TreeMap<>();
        for (String sig : registry.hostSignatures()) {
            if (sig.startsWith("context ")) {
                String[] fields = sig.substring(8).split(": ", 2);
                context.put(fields[0], fields[1]);
                continue;
            }
            Registry.Binding binding = registry.resolve(sig);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("params", binding.parameters);
            spec.put("returns", binding.returns);
            Map<String, Object> target = functions;
            if (binding.method) {
                @SuppressWarnings("unchecked")
                Map<String, Object> methods = (Map<String, Object>) ((Map<String, Object>) types.computeIfAbsent(
                        binding.type, k -> new LinkedHashMap<>(Map.of("methods", new TreeMap<String, Object>())))).get("methods");
                target = methods;
            }
            @SuppressWarnings("unchecked")
            List<Object> overloads = (List<Object>) target.computeIfAbsent(binding.name, k -> new ArrayList<>());
            overloads.add(spec);
        }
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("dsl.schema", 2);
        all.put("name", "lmoci");
        all.put("functions", functions);
        all.put("types", types);
        all.put("context", context);
        return (Jsons.stringify(all) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static int run(String[] args) throws Exception {
        if (args.length > 0 && CliArea.isArea(args[0])) {
            return CliArea.run(args[0], Arrays.asList(args).subList(1, args.length));
        }

        List<String> remaining = new ArrayList<>();
        boolean dryRun = false;
        boolean apply = false;
        boolean live = false;
        String profile = null;
        String region = null;
        String endpoint = null;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else if ("--apply".equals(arg)) {
                apply = true;
            } else if ("--live".equals(arg)) {
                live = true;
            } else if ("--profile".equals(arg) && index + 1 < args.length) {
                profile = args[++index];
            } else if ("--region".equals(arg) && index + 1 < args.length) {
                region = args[++index];
            } else if ("--endpoint".equals(arg) && index + 1 < args.length) {
                endpoint = args[++index];
            } else {
                remaining.add(arg);
            }
        }

        if (dryRun) {
            System.setProperty("lmoci.dryRun", "true");
        }
        if (live && !dryRun) {
            System.setProperty("lmoci.live", "true");
        }
        if (apply && !dryRun) {
            System.setProperty("lmoci.apply", "true");
        }
        if (profile != null) {
            System.setProperty("lmoci.profile", profile);
        }
        if (region != null) {
            System.setProperty("lmoci.region", region);
        }
        if (endpoint != null) {
            System.setProperty("lmoci.endpoint", endpoint);
        }

        Map<String, String> env = System.getenv();
        final CloudHost host;
        try {
            SafetyChecks.rejectUnapprovedCiLiveCredentials(env);
            host = new CloudHost(env);
        } catch (IllegalArgumentException | IllegalStateException exc) {
            System.err.println(exc.getMessage());
            return 2;
        }

        String[] cleanArgs = remaining.toArray(new String[0]);
        if (Arrays.equals(cleanArgs, new String[] {"--version"}) || Arrays.equals(cleanArgs, new String[] {"-v"})) {
            System.out.println("lmoci " + VERSION);
            return 0;
        }
        if (Arrays.equals(cleanArgs, new String[] {"--help"}) || Arrays.equals(cleanArgs, new String[] {"-h"})) {
            System.out.print(HELP);
            return 0;
        }
        if (Arrays.equals(cleanArgs, new String[] {"--notices"})) {
            try (InputStream in = Main.class.getResourceAsStream("/THIRD_PARTY_NOTICES")) {
                if (in == null) {
                    throw new IOException("THIRD_PARTY_NOTICES resource missing");
                }
                in.transferTo(System.out);
            }
            return 0;
        }

        Thread shutdown = new Thread(() -> {
            try {
                host.close();
            } catch (RuntimeException exc) {
                System.err.println("lmoci teardown incomplete; inspect the sandbox ledger");
            }
        }, "lmoci-teardown");
        Runtime.getRuntime().addShutdownHook(shutdown);

        try (host) {
            Registry registry = host.registry();
            byte[] schemaBytes = schema(registry);

            if (cleanArgs.length >= 1
                    && "--dsl-schema".equals(cleanArgs[0])
                    && (cleanArgs.length == 1 || (cleanArgs.length == 2 && "--json".equals(cleanArgs[1])))) {
                System.out.write(schemaBytes);
                return 0;
            }
            if (Arrays.equals(cleanArgs, new String[] {"--dsl-signatures", "--without-std"})) {
                registry.hostSignatures().forEach(System.out::println);
                return 0;
            }
            if (Arrays.equals(cleanArgs, new String[] {"--dsl-signatures"})) {
                registry.signatures().forEach(System.out::println);
                return 0;
            }
            if (cleanArgs.length == 4
                    && "--sweep".equals(cleanArgs[0])
                    && "--older-than-hours".equals(cleanArgs[2])) {
                long hours = Long.parseLong(cleanArgs[3]);
                System.out.println(Jsons.stringify(Sandbox.leftovers(Path.of(cleanArgs[1]), hours)));
                return 0;
            }
            if (Arrays.equals(cleanArgs, new String[] {"--self-test"})) {
                runSelfTests(registry, schemaBytes);
                return 0;
            }
            if (cleanArgs.length != 2 || !"--dsl".equals(cleanArgs[0])) {
                System.err.print(HELP);
                return 2;
            }

            long budgetBytes = host.runtimeBudget().maxMemoryBytes();
            Path input = Path.of(cleanArgs[1]);
            if (!Files.isRegularFile(input)) {
                throw VmException.run("DSL input file does not exist: " + input);
            }
            byte[] bytecode = loadBytecode(input, schemaBytes, budgetBytes);
            Program program = Program.load(bytecode, schemaBytes, registry);
            program.run(host.context(budgetBytes), registry);
            host.close();
            return Reports.ok("null");
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    private static byte[] loadBytecode(Path input, byte[] schemaBytes, long budgetBytes) throws Exception {
        if (Files.size(input) > Math.min(budgetBytes / 4, 64L * 1024L * 1024L)) {
            throw VmException.run("LMOCI_MAX_MEMORY_MB bytecode input exceeds memory budget");
        }
        byte[] raw = Files.readAllBytes(input);
        if (input.getFileName().toString().endsWith(".lms")) {
            Path tempDir = Files.createTempDirectory("lmoci-lms-");
            try {
                Path schemaFile = tempDir.resolve("lmoci.dsl.json");
                Path outFile = tempDir.resolve("compiled.dslb");
                Path wrapper = tempDir.resolve("wrapper.lms");
                Files.write(schemaFile, schemaBytes);
                String src = new String(raw, StandardCharsets.UTF_8);
                String wrapped = "p = _dsl_ schema=" + Jsons.stringify(schemaFile.toString()) + ";\n"
                        + src + "\nend _dsl_\nsavedsl(p, " + Jsons.stringify(outFile.toString()) + ");\n";
                Files.writeString(wrapper, wrapped, StandardCharsets.UTF_8);
                Process proc = new ProcessBuilder("lmscript", wrapper.toString())
                        .redirectErrorStream(true)
                        .start();
                String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int code = proc.waitFor();
                if (code != 0 || !Files.isRegularFile(outFile)) {
                    throw VmException.host("lmscript compile failed: " + output.trim());
                }
                return Files.readAllBytes(outFile);
            } finally {
                try (var stream = Files.walk(tempDir)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        }
        return raw;
    }

    private static void runSelfTests(Registry registry, byte[] schemaBytes) throws Exception {
        registry.checkSchemaSignatures(schemaBytes);
        Map<String, Object> analysis = PolicyAnalyzer.analyze(
                "Allow any-user to manage all-resources in tenancy");
        if (!"CRITICAL".equals(analysis.get("risk_level"))) {
            throw new IllegalStateException("PolicyAnalyzer self-test failed");
        }
        SelfTest.main(new String[0]);
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        int status;
        try {
            status = run(args);
        } catch (OutOfMemoryError err) {
            String text = "LMOCI_MAX_MEMORY_MB memory budget exceeded";
            System.err.println(text);
            status = Reports.error("run", VmException.run(text));
        } catch (Exception exc) {
            String text = exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage();
            if (text.contains("memory budget")) {
                text = "LMOCI_MAX_MEMORY_MB: " + text;
            }
            System.err.println(text);
            VmException error = exc instanceof VmException vm
                    ? new VmException(vm.code, text)
                    : VmException.host(text);
            if (exc instanceof VmException vm) {
                error.line = vm.line;
                error.column = vm.column;
            }
            status = Reports.error("run", error);
        }
        if (status != 0) {
            System.exit(status);
        }
    }
}
