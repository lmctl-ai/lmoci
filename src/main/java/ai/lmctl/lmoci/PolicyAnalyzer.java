package ai.lmctl.lmoci;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PolicyAnalyzer {
    private static final Pattern ALLOW_STATEMENT = Pattern.compile(
            "^\\s*allow\\s+(.+?)\\s+to\\s+(inspect|read|use|manage)\\s+([a-z0-9_-]+)\\s+in\\s+(.+?)(?:\\s+where\\s+(.+))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private PolicyAnalyzer() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> analyze(Object policyInput) {
        List<String> statements = extractStatements(policyInput);
        List<Map<String, Object>> findings = new ArrayList<>();
        int allowCount = 0;
        int manageAllCount = 0;
        int tenancyWideCount = 0;
        int anyUserCount = 0;

        for (int index = 0; index < statements.size(); index++) {
            String raw = statements.get(index).trim();
            if (raw.isEmpty()) {
                continue;
            }
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.startsWith("allow ")) {
                allowCount++;
            }
            Matcher matcher = ALLOW_STATEMENT.matcher(raw);
            if (matcher.matches()) {
                String subject = matcher.group(1).trim();
                String verb = matcher.group(2).trim().toLowerCase(Locale.ROOT);
                String resource = matcher.group(3).trim().toLowerCase(Locale.ROOT);
                String scope = matcher.group(4).trim().toLowerCase(Locale.ROOT);
                String condition = matcher.group(5);

                boolean anyUser = subject.toLowerCase(Locale.ROOT).contains("any-user")
                        || subject.toLowerCase(Locale.ROOT).contains("any-group")
                        || subject.equals("*");
                boolean manageAll = verb.equals("manage") && resource.equals("all-resources");
                boolean tenancyScope = scope.startsWith("tenancy");
                boolean unconditional = condition == null || condition.isBlank();

                if (anyUser) {
                    anyUserCount++;
                    findings.add(Rows.row(
                            "index", index,
                            "severity", "CRITICAL",
                            "code", "ANY_USER_PRINCIPAL",
                            "statement", raw,
                            "detail", "Statement grants access to any-user/any-group"));
                }
                if (manageAll && tenancyScope) {
                    manageAllCount++;
                    tenancyWideCount++;
                    findings.add(Rows.row(
                            "index", index,
                            "severity", unconditional ? "HIGH" : "MEDIUM",
                            "code", "TENANCY_MANAGE_ALL_RESOURCES",
                            "statement", raw,
                            "detail", "Statement grants manage all-resources across the tenancy"));
                } else if (manageAll) {
                    manageAllCount++;
                    findings.add(Rows.row(
                            "index", index,
                            "severity", "MEDIUM",
                            "code", "COMPARTMENT_MANAGE_ALL_RESOURCES",
                            "statement", raw,
                            "detail", "Statement grants manage all-resources in compartment scope"));
                } else if (tenancyScope && verb.equals("manage")) {
                    tenancyWideCount++;
                    findings.add(Rows.row(
                            "index", index,
                            "severity", "LOW",
                            "code", "TENANCY_MANAGE_FAMILY",
                            "statement", raw,
                            "detail", "Statement grants tenancy-wide manage access to " + resource));
                }
            } else if (lower.contains("\"effect\":\"allow\"") || lower.contains("manage all-resources") || lower.contains("*")) {
                if (lower.contains("any-user") || lower.contains("\"*\"")) {
                    anyUserCount++;
                    findings.add(Rows.row(
                            "index", index,
                            "severity", "HIGH",
                            "code", "WILDCARD_GRANT",
                            "statement", raw,
                            "detail", "Wildcard or unrestricted grant detected"));
                }
            }
        }

        String riskLevel = anyUserCount > 0
                ? "CRITICAL"
                : (manageAllCount > 0 && tenancyWideCount > 0 ? "HIGH" : (manageAllCount > 0 ? "MEDIUM" : "LOW"));
        return Rows.row(
                "statement_count", statements.size(),
                "allow_count", allowCount,
                "manage_all_count", manageAllCount,
                "tenancy_wide_count", tenancyWideCount,
                "any_user_count", anyUserCount,
                "risk_level", riskLevel,
                "finding_count", findings.size(),
                "findings", List.copyOf(findings));
    }

    private static List<String> extractStatements(Object input) {
        if (input == null) {
            return List.of();
        }
        if (input instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (input instanceof Map<?, ?> map) {
            Object stmts = map.get("statements");
            if (stmts == null) {
                stmts = map.get("Statement");
            }
            if (stmts instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
        }
        String text = String.valueOf(input).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return List.copyOf(lines);
    }
}
