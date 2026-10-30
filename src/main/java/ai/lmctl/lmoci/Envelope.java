package ai.lmctl.lmoci;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Envelope {
    private Envelope() {}

    public static final class AreaException extends RuntimeException {
        public final int exitCode;
        public final String code;

        public AreaException(int exitCode, String code, String message) {
            super(message);
            this.exitCode = exitCode;
            this.code = code;
        }

        public AreaException(int exitCode, String code, String message, Throwable cause) {
            super(message, cause);
            this.exitCode = exitCode;
            this.code = code;
        }
    }

    public static AreaException usage(String message) {
        return new AreaException(2, "USAGE", message);
    }

    public static AreaException fail(String code, String message) {
        return new AreaException(3, code, message);
    }

    public static AreaException fail(String code, String message, Throwable cause) {
        return new AreaException(3, code, message, cause);
    }

    public static int ok(Object result) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("ok", true);
        env.put("result", result);
        System.out.println(Jsons.stringify(env));
        return 0;
    }

    public static int error(AreaException exc) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", exc.code);
        err.put("message", exc.getMessage() == null ? exc.code : exc.getMessage());
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("ok", false);
        env.put("error", err);
        System.out.println(Jsons.stringify(env));
        return exc.exitCode;
    }

    public static int error(int exitCode, String code, String message) {
        return error(new AreaException(exitCode, code, message));
    }
}
