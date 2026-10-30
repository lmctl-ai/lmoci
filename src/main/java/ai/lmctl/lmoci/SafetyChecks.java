package ai.lmctl.lmoci;

import java.util.List;
import java.util.Map;

public final class SafetyChecks {
    private static final List<String> LIVE_CREDENTIAL_ENV_VARS = List.of(
            "OCI_CLI_USER",
            "OCI_CLI_TENANCY",
            "OCI_CLI_FINGERPRINT",
            "OCI_CLI_KEY_FILE",
            "OCI_CLI_KEY_CONTENT",
            "OCI_CONFIG_FILE",
            "OCI_RESOURCE_PRINCIPAL_VERSION"
    );

    private SafetyChecks() {}

    public static void rejectUnapprovedCiLiveCredentials(Map<String, String> env) {
        boolean inCi = isTruthy(env.get("CI")) || isTruthy(env.get("GITHUB_ACTIONS"));
        if (!inCi || isTruthy(env.get("LMOCI_ALLOW_LIVE_IN_CI"))) {
            return;
        }
        for (String name : LIVE_CREDENTIAL_ENV_VARS) {
            String value = env.get(name);
            if (value != null && !value.trim().isEmpty()) {
                throw new IllegalStateException(
                        "Refusing to run in CI with live OCI credential environment variable " + name
                                + " set; unset credentials for offline CI or set LMOCI_ALLOW_LIVE_IN_CI=1 for an approved live run.");
            }
        }
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return !normalized.isEmpty() && !normalized.equals("0") && !normalized.equals("false") && !normalized.equals("no");
    }
}
