package ai.lmctl.lmoci;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Map;

public final class RuntimeBudget {
    public static final String ENV_NAME = "LMOCI_MAX_MEMORY_MB";
    public static final long DEFAULT_MAX_MEMORY_MB = 3072L;
    public static final long MIN_MAX_MEMORY_MB = 1L;
    public static final long MAX_MAX_MEMORY_MB = 65536L;

    private final long maxMemoryBytes;
    private final MemoryMXBean memoryBean;

    public RuntimeBudget(long maxMemoryMb) {
        if (maxMemoryMb < MIN_MAX_MEMORY_MB || maxMemoryMb > MAX_MAX_MEMORY_MB) {
            throw new IllegalArgumentException(
                    ENV_NAME + " must be a positive whole MiB between " + MIN_MAX_MEMORY_MB + " and " + MAX_MAX_MEMORY_MB);
        }
        this.maxMemoryBytes = maxMemoryMb * 1024L * 1024L;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
    }

    public static RuntimeBudget fromEnv(Map<String, String> env) {
        String raw = env.getOrDefault(ENV_NAME, "").trim();
        if (raw.isEmpty()) {
            return new RuntimeBudget(DEFAULT_MAX_MEMORY_MB);
        }
        if (!raw.matches("[0-9]+")) {
            throw new IllegalArgumentException(ENV_NAME + " must be a positive whole MiB: " + raw);
        }
        final long parsed;
        try {
            parsed = Long.parseLong(raw);
        } catch (NumberFormatException exc) {
            throw new IllegalArgumentException(ENV_NAME + " must be a positive whole MiB: " + raw);
        }
        return new RuntimeBudget(parsed);
    }

    public long maxMemoryBytes() {
        return maxMemoryBytes;
    }

    public long maxMemoryMb() {
        return maxMemoryBytes / (1024L * 1024L);
    }

    public long usedBytes() {
        long heap = Math.max(0L, memoryBean.getHeapMemoryUsage().getUsed());
        long nonHeap = Math.max(0L, memoryBean.getNonHeapMemoryUsage().getUsed());
        return heap + nonHeap;
    }

    public void check(String context) {
        long used = usedBytes();
        if (used <= maxMemoryBytes) {
            return;
        }
        System.gc();
        used = usedBytes();
        if (used > maxMemoryBytes) {
            throw new IllegalStateException(
                    "memory budget exceeded during " + context + ": " + (used / (1024L * 1024L))
                            + " MB used > " + maxMemoryMb() + " MB cap (" + ENV_NAME + ")");
        }
    }
}
