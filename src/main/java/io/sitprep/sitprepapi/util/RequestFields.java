package io.sitprep.sitprepapi.util;

/**
 * Loose JSON-map field readers for the plan {@code /bulk} and {@code /one}
 * endpoints, which bind {@code Map<String, Object>} rather than a DTO. One
 * place for the {@code ((Number) data.get("lat")).doubleValue()} pattern that
 * was copied into five call sites — and which threw a ClassCastException on a
 * numeric string.
 */
public final class RequestFields {

    private RequestFields() {}

    public static Double doubleOrNull(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** A row id: a JSON number or numeric string; anything else (a client temp id) is null. */
    public static Long longOrNull(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? n.longValue() : null;
        }
        if (v instanceof String s && s.trim().matches("\\d{1,18}")) return Long.valueOf(s.trim());
        return null;
    }
}
