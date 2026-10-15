package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One shareable link to a group, as the share sheet and the admin's invite
 * list show it (JN-10, B5 2026-10-14).
 *
 * <p>{@code path} is relative ({@code /share/i/{token}},
 * {@code /share/household/{token}} or {@code /share/group/{groupId}}); the
 * client puts its own origin in front, the way {@code shareCopy.js} already
 * does, so a native shell and the web build share one answer.</p>
 *
 * @param id        the token; null for the non-approving group link
 * @param approving true when redeeming it lets the person straight in: a
 *                  household token, or an org token whose issuer is STILL an
 *                  owner/admin (D19, read at answer time, the same rule the
 *                  redeem applies); false for the plain group link a member
 *                  shares (D20) and for a token from a former admin, both of
 *                  which are only a request for a private group
 * @param current   the group's one share link (the one the sheet shows and
 *                  "Reset" replaces)
 * @param canReset  whether the caller may reset it (owner/admin)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupInviteLinkDto(
        String id,
        String path,
        boolean approving,
        boolean current,
        boolean canReset,
        Instant issuedAt,
        Instant expiresAt,
        Integer maxUses,
        Integer usedCount
) {}
