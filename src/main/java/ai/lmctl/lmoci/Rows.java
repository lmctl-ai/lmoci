package ai.lmctl.lmoci;

import io.lmctl.lmcode.Context;
import io.lmctl.lmcode.VmException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Rows {
    public final List<Map<String, Object>> rows;

    public Rows(List<Map<String, Object>> source, Context context) {
        long size = 128L;
        for (Map<String, Object> item : source) {
            for (Map.Entry<String, Object> field : item.entrySet()) {
                String k = String.valueOf(field.getKey());
                String v = field.getValue() == null ? "" : String.valueOf(field.getValue());
                size = Math.addExact(size, 128L + 2L * (k.length() + v.length()));
            }
        }
        if (context != null) {
            context.charge(size);
        }
        this.rows = source.stream()
                .map(r -> Collections.unmodifiableMap(new LinkedHashMap<>(r)))
                .toList();
    }

    public static String get(Map<String, Object> row, String key) {
        if (row == null) {
            return "";
        }
        Object val = row.get(key);
        if (val == null) {
            return "";
        }
        if (val instanceof List<?> || val instanceof Map<?, ?>) {
            return Jsons.stringify(val);
        }
        return String.valueOf(val);
    }

    public String csv() {
        Set<String> columns = new LinkedHashSet<>();
        rows.forEach(r -> columns.addAll(r.keySet()));
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", columns.stream().map(Rows::cell).toList())).append('\n');
        for (Map<String, Object> item : rows) {
            out.append(String.join(",", columns.stream().map(k -> cell(get(item, k))).toList())).append('\n');
        }
        return out.toString();
    }

    private static String cell(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public void write(String path) {
        try {
            Path target = Path.of(path);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, csv(), StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw VmException.host("CSV export failed: " + exc.getMessage());
        }
    }

    public static Map<String, Object> row(Object... keysAndValues) {
        if ((keysAndValues.length & 1) != 0) {
            throw new IllegalArgumentException("Rows.row requires key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put(String.valueOf(keysAndValues[index]), keysAndValues[index + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    public static String str(Map<?, ?> map, String key, String fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public static String requiredStr(Map<?, ?> map, String key) {
        String value = str(map, key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing required option: " + key);
        }
        return value;
    }

    public static long longOpt(Map<?, ?> map, String key, long fallback) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }

    public static boolean boolOpt(Map<?, ?> map, String key, boolean fallback) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    public static Map<String, String> stringMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public static List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (raw == null) {
            return List.of();
        }
        return List.of(String.valueOf(raw));
    }
}
