package ai.lmctl.lmoci;

import io.lmctl.lmcode.Context;
import io.lmctl.lmcode.InvocationResults;
import io.lmctl.lmcode.Program;
import io.lmctl.lmcode.Registry;
import io.lmctl.lmcode.Reports;
import io.lmctl.lmcode.ResultContract;
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

    public static final String VERSION = "0.2.0";
    private static final String USAGE = "usage: lmoci --lmcode FILE [--lmcode FILE ...]\n"
            + "       lmoci --lmcode-schema [--json] | --lmcode-signatures [--without-std]\n";
    private static final String HELP = """
            lmoci 0.2.0 — Oracle Cloud Infrastructure lmcode host
            usage: lmoci --lmcode FILE [--lmcode FILE ...]
                   lmoci --lmcode-schema [--json] | --lmcode-signatures [--without-std]
                   lmoci cred <set|get|list|delete|path> [options]
                   lmoci <os|iam|queue|stream|vault|inventory> <action> [options]
                   lmoci --version | --notices | --help | --self-test
                   lmoci --sweep LEDGER_DIR --older-than-hours N

            Invocation preview mode is carried by the lmscript manifest.
            Direct OCI areas remain offline with --demo; live calls require explicit endpoint/configuration.

            lmcode context:
              oci.demo()                        Offline deterministic OCI fixture session
              oci.region("us-ashburn-1")        Live OCI session for the specified region
              oci.endpoint("us-ashburn-1", URL) Live OCI session targeting an explicit endpoint (e.g. ocisim http://127.0.0.1:4599)

            Credentials:
              Shared SQLite credential store at ~/.lmconn/creds.db (override via LMOCI_DB / LMCLIENT_DB / LMCONN_DB).
              Secret values and private keys are never printed unless `lmoci cred get ... --reveal` is explicitly passed.

            Safety:
              LMOCI_MAX_COST_USD:  dollar cap between 0.00 and 100.00 (default 2.00).
              Every sandbox run (oci.with_sandbox(name, fn)) tears down owned resources in reverse order on exit or error.
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "Json");
        result.put("nullable", true);
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("dsl.schema", 2);
        all.put("name", "lmoci");
        all.put("functions", functions);
        all.put("types", types);
        all.put("result", result);
        all.put("context", context);
        return (Jsons.stringify(all) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static int run(String[] args) {
        try {
            return runChecked(args);
        } catch (OutOfMemoryError error) {
            return Reports.failureFrame(System.out, "run", VmException.run("memory exhausted"));
        } catch (VmException error) {
            return Reports.failureFrame(System.out, phase(error), error);
        } catch (Exception error) {
            return Reports.failureFrame(System.out, "run", VmException.host(message(error)));
        }
    }

    private static int runChecked(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            return Reports.failureFrame(System.out, "cli", new VmException("CLI_USAGE", USAGE));
        }
        for (String arg : args) {
            if ("--dry-run".equals(arg)) {
                return Reports.failureFrame(System.out, "cli", new VmException("CLI_USAGE", USAGE));
            }
        }

        // Product-specific direct command areas are separate from lmcode invocation flags.
        if (CliArea.isArea(args[0])) {
            return CliArea.run(args[0], Arrays.asList(args).subList(1, args.length));
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            int config = validateUtilityConfig();
            if (config != 0) {
                return config;
            }
            System.out.println("lmoci " + VERSION);
            return 0;
        }
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            int config = validateUtilityConfig();
            if (config != 0) {
                return config;
            }
            System.out.print(HELP);
            return 0;
        }
        if (args.length == 1 && "--notices".equals(args[0])) {
            int config = validateUtilityConfig();
            if (config != 0) {
                return config;
            }
            try (InputStream in = Main.class.getResourceAsStream("/THIRD_PARTY_NOTICES")) {
                if (in == null) {
                    throw new IOException("THIRD_PARTY_NOTICES resource missing");
                }
                in.transferTo(System.out);
            }
            return 0;
        }
        if (args.length == 1 && "--self-test".equals(args[0])) {
            return runSelfTest();
        }
        if (args.length == 4 && "--sweep".equals(args[0]) && "--older-than-hours".equals(args[2])) {
            int config = validateUtilityConfig();
            if (config != 0) {
                return config;
            }
            long hours;
            try {
                hours = Long.parseLong(args[3]);
            } catch (NumberFormatException error) {
                return Reports.failureFrame(System.out, "cli", new VmException("CLI_USAGE", "--older-than-hours must be an integer"));
            }
            System.out.println(Jsons.stringify(Sandbox.leftovers(Path.of(args[1]), hours)));
            return 0;
        }

        Map<String, String> env = System.getenv();
        if (args[0].equals("--lmcode-schema")) {
            if (args.length > 2 || (args.length == 2 && !args[1].equals("--json"))) {
                return Reports.failureFrame(System.out, "cli", new VmException("CLI_USAGE", USAGE));
            }
            try (CloudHost host = new CloudHost(env)) {
                byte[] bytes = schema(host.registry());
                System.out.write(bytes);
                return System.out.checkError() ? 4 : 0;
            }
        }
        if (args[0].equals("--lmcode-signatures")) {
            if (args.length > 2 || (args.length == 2 && !args[1].equals("--without-std"))) {
                return Reports.failureFrame(System.out, "cli", new VmException("CLI_USAGE", USAGE));
            }
            try (CloudHost host = new CloudHost(env)) {
                Registry registry = host.registry();
                List<String> signatures = args.length == 2 ? registry.hostSignatures() : registry.signatures();
                signatures.stream().sorted().forEach(System.out::println);
                return System.out.checkError() ? 4 : 0;
            }
        }

        List<String> files = parseFiles(args);
        final InvocationResults results;
        try {
            // Strict manifest validation precedes host construction, schema setup, bytecode loading, and callbacks.
            results = InvocationResults.fromCommand(files);
        } catch (VmException error) {
            return Reports.failureFrame(System.out, "cli", error);
        }

        CloudHost host = null;
        String stage = "setup";
        Integer currentPart = null;
        try {
            Map<String, String> currentEnv = System.getenv();
            SafetyChecks.rejectUnapprovedCiLiveCredentials(currentEnv);
            host = new CloudHost(currentEnv);
            Registry registry = host.registry();
            byte[] schemaBytes = schema(registry);
            registry.checkSchemaSignatures(schemaBytes);
            ResultContract contract = ResultContract.fromSchema(new String(schemaBytes, StandardCharsets.UTF_8));

            stage = "load";
            List<Program> programs = new ArrayList<>(files.size());
            for (int index = 0; index < files.size(); index++) {
                InvocationResults.Registration registration = results.registrations().get(index);
                currentPart = registration.index();
                Path path = Path.of(files.get(index));
                if (!Files.isRegularFile(path)) {
                    results.fail(registration.index(), "load", VmException.badBytecode("cannot read lmcode file: " + files.get(index)));
                    return results.writeFrame(System.out);
                }
                try {
                    programs.add(Program.load(Files.readAllBytes(path), schemaBytes, registry));
                } catch (IOException error) {
                    results.fail(registration.index(), "load", VmException.badBytecode("cannot read lmcode file: " + files.get(index)));
                    return results.writeFrame(System.out);
                } catch (VmException error) {
                    results.fail(registration.index(), "load", error);
                    return results.writeFrame(System.out);
                }
            }

            Context context = host.context(results.dry());
            context.setDry(results.dry());
            stage = "run";
            for (int index = 0; index < files.size(); index++) {
                InvocationResults.Registration registration = results.registrations().get(index);
                currentPart = registration.index();
                try {
                    results.select(registration.index(), registry, contract);
                    context.resetBudget();
                    programs.get(index).run(context, registry);
                    results.complete(registration.index());
                } catch (VmException error) {
                    results.fail(registration.index(), phase(error), error);
                    break;
                } catch (RuntimeException error) {
                    results.fail(registration.index(), "run", VmException.host(message(error)));
                    break;
                }
            }
        } catch (VmException error) {
            results.fail(currentPart, stage.equals("setup") ? "cli" : phaseForStage(stage, error),
                    stage.equals("setup") ? new VmException("CLI_USAGE", error.getMessage()) : error);
        } catch (IllegalArgumentException | IllegalStateException error) {
            if (stage.equals("setup")) {
                results.fail(null, "cli", new VmException("CLI_USAGE", message(error)));
            } else if (stage.equals("load")) {
                results.fail(currentPart, "load", VmException.badBytecode(message(error)));
            } else {
                results.fail(currentPart, "run", VmException.host(message(error)));
            }
        } catch (RuntimeException error) {
            results.fail(currentPart, stage.equals("load") ? "load" : "run",
                    stage.equals("load") ? VmException.badBytecode(message(error)) : VmException.host(message(error)));
        } catch (OutOfMemoryError error) {
            results.fail(currentPart, stage.equals("setup") ? "cli" : stage.equals("load") ? "load" : "run",
                    VmException.run("memory exhausted"));
        } finally {
            if (host != null) {
                try {
                    host.close();
                } catch (RuntimeException closeError) {
                    results.fail(null, "run", VmException.host("lmoci teardown incomplete: " + message(closeError)));
                }
            }
        }
        return results.writeFrame(System.out);
    }

    private static List<String> parseFiles(String[] args) {
        List<String> files = new ArrayList<>();
        for (int index = 0; index < args.length;) {
            String option = args[index++];
            if (!"--lmcode".equals(option)) {
                throw new VmException("CLI_USAGE", USAGE);
            }
            if (index >= args.length || args[index].isEmpty() || args[index].startsWith("--")) {
                throw new VmException("CLI_USAGE", USAGE);
            }
            files.add(args[index++]);
            while (index < args.length && !args[index].startsWith("--")) {
                files.add(args[index++]);
            }
        }
        if (files.isEmpty()) {
            throw new VmException("CLI_USAGE", USAGE);
        }
        return List.copyOf(files);
    }

    private static int runSelfTest() {
        Map<String, String> env = System.getenv();
        try {
            SafetyChecks.rejectUnapprovedCiLiveCredentials(env);
        } catch (IllegalStateException error) {
            System.err.println(error.getMessage());
            return 2;
        }
        try (CloudHost host = new CloudHost(env)) {
            Registry registry = host.registry();
            byte[] schemaBytes = schema(registry);
            registry.checkSchemaSignatures(schemaBytes);
            Map<String, Object> analysis = PolicyAnalyzer.analyze("Allow any-user to manage all-resources in tenancy");
            if (!"CRITICAL".equals(analysis.get("risk_level"))) {
                throw new IllegalStateException("PolicyAnalyzer self-test failed");
            }
            Context context = host.context(false);
            if (context.get("oci") == null || registry.signatures().isEmpty()) {
                throw new IllegalStateException("lmcode host self-test failed");
            }
            return 0;
        } catch (RuntimeException error) {
            return Reports.failureFrame(System.out, "run", VmException.host(message(error)));
        } catch (Exception error) {
            return Reports.failureFrame(System.out, "run", VmException.host(message(error)));
        }
    }

    private static String phase(VmException error) {
        if (error.code.equals("CLI_USAGE")) {
            return "cli";
        }
        if (error.code.equals("APP_FAIL")) {
            return "app";
        }
        if (error.code.startsWith("LOAD_")) {
            return "load";
        }
        return "run";
    }

    private static int validateUtilityConfig() {
        Map<String, String> env = System.getenv();
        try {
            CostLedger.fromEnv(env);
            return 0;
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            return 2;
        }
    }

    private static String phaseForStage(String stage, VmException error) {
        if (stage.equals("setup")) {
            return "cli";
        }
        if (stage.equals("load")) {
            return "load";
        }
        return phase(error);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        int status;
        try {
            status = run(args);
        } catch (OutOfMemoryError error) {
            status = Reports.failureFrame(System.out, "run", VmException.run("memory exhausted"));
        } catch (Exception error) {
            status = Reports.failureFrame(System.out, "run", VmException.host(message(error)));
        }
        if (status != 0) {
            System.exit(status);
        }
    }
}
