package io.sitprep.sitprepapi.dto;

/**
 * Canonical response envelope for HTTP endpoints — {@code { data, error, meta }}.
 *
 * <p>Per the P0-5 race-audit gameplan, endpoints opt into this shape one at a
 * time. The FE's axios response interceptor in {@code src/shared/api/http.js}
 * detects the envelope (by the presence of {@code data}+{@code error}+{@code meta})
 * and unwraps {@code response.data} to the inner payload while attaching
 * {@code response.envelope} for consumers that want {@link ApiMeta}
 * (e.g. {@code degradedSections}). That keeps legacy callers byte-for-byte
 * unchanged while new callers can opt into the meta surface.</p>
 *
 * <p>Phase 1 (this commit): {@code GET /api/me/{uid}} and
 * {@code GET /api/userinfo/{idOrEmail}/profile}. Remaining endpoints land in
 * P2-3.</p>
 */
public record ApiResponse<T>(T data, ApiError error, ApiMeta meta) {

    public static <T> ApiResponse<T> ok(T data, ApiMeta meta) {
        return new ApiResponse<>(data, null, meta == null ? ApiMeta.now() : meta);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null, ApiMeta.now());
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(null, new ApiError(code, message), ApiMeta.now());
    }
}
