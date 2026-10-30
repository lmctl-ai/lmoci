package ai.lmctl.lmoci;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

public final class CostLedger {
    public static final String ENV_NAME = "LMOCI_MAX_COST_USD";
    public static final double DEFAULT_MAX_COST_USD = 2.00;
    public static final double MIN_MAX_COST_USD = 0.00;
    public static final double MAX_MAX_COST_USD = 100.00;

    private final double maxCostUsd;
    private final DoubleAdder estimatedUsd = new DoubleAdder();
    private final AtomicLong totalRequests = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> serviceRequests = new ConcurrentHashMap<>();

    public CostLedger(double maxCostUsd) {
        if (!Double.isFinite(maxCostUsd) || maxCostUsd < MIN_MAX_COST_USD || maxCostUsd > MAX_MAX_COST_USD) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, "%s must be between %.2f and %.2f USD", ENV_NAME, MIN_MAX_COST_USD, MAX_MAX_COST_USD));
        }
        this.maxCostUsd = maxCostUsd;
    }

    public static CostLedger fromEnv(Map<String, String> env) {
        String raw = env.getOrDefault(ENV_NAME, "").trim();
        if (raw.isEmpty()) {
            return new CostLedger(DEFAULT_MAX_COST_USD);
        }
        final double parsed;
        try {
            parsed = Double.parseDouble(raw);
        } catch (NumberFormatException exc) {
            throw new IllegalArgumentException("invalid " + ENV_NAME + ": " + raw);
        }
        return new CostLedger(parsed);
    }

    public void recordRequest(String service, String operation) {
        String normalizedService = service == null || service.isBlank() ? "oci" : service.toLowerCase(Locale.ROOT);
        double increment = unitCostUsd(normalizedService, operation == null ? "" : operation);
        double next = estimatedUsd.sum() + increment;
        if (next > maxCostUsd + 1e-9) {
            throw new IllegalStateException(String.format(
                    Locale.ROOT,
                    "cost cap exceeded before %s.%s: %.6f USD > %.2f USD (%s)",
                    normalizedService,
                    operation,
                    next,
                    maxCostUsd,
                    ENV_NAME));
        }
        estimatedUsd.add(increment);
        totalRequests.incrementAndGet();
        serviceRequests.computeIfAbsent(normalizedService, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static double unitCostUsd(String service, String operation) {
        String op = operation.toLowerCase(Locale.ROOT);
        return switch (service) {
            case "objectstorage", "os" -> (op.contains("put") || op.contains("create") || op.contains("list") || op.contains("rename"))
                    ? 0.000005
                    : 0.0000004;
            case "kms", "keymanagement", "vault", "secrets" -> 0.000003;
            case "queue", "streaming", "stream" -> 0.000001;
            case "functions", "containerengine", "oke", "psql", "postgresql" -> 0.000002;
            default -> 0.0000005;
        };
    }

    public Map<String, Object> summary() {
        List<Map<String, Object>> byService = new ArrayList<>();
        serviceRequests.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> byService.add(Rows.row(
                        "service", entry.getKey(),
                        "requests", entry.getValue().get())));
        return Rows.row(
                "max_cost_usd", round6(maxCostUsd),
                "estimated_usd", round6(estimatedUsd.sum()),
                "total_requests", totalRequests.get(),
                "services", List.copyOf(byService));
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
