package io.sitprep.sitprepapi.dto;

/**
 * Lightweight identity for a member face-stack — shared across every DTO
 * that renders a small avatar + name (circle-card member preview on
 * {@link MeDto}, mutual face stack on {@link CommunityDiscoverDto}, and any
 * future face row). Previously this exact record was copy-pasted into both
 * DTOs; unified 2026-06-11 so the FE has one shape and the BE one definition.
 *
 * <p>{@code userId} is the stable opaque identifier the FE routes to
 * {@code /profile/:userId} (added 2026-06-03) — <em>never</em> the email,
 * which is private to a group's own members and must not leak to strangers on
 * privacy-sensitive surfaces like community-discover. Null is tolerated for
 * legacy back-compat; the FE skips the profile tap when it's absent.
 *
 * <p>{@code profileImageUrl} is already resolved through
 * {@link DtoImages#avatar(String)} at every build site — pass it through to
 * the {@code <img>} tag as-is.
 */
public record MemberAvatar(String userId, String firstName, String profileImageUrl) {}
