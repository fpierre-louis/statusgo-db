package io.sitprep.sitprepapi.constant;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Post-kind discriminator for the community feed.
 *
 * <p>Note: "Post" here means the conceptual community-feed entry, not
 * {@link io.sitprep.sitprepapi.domain.GroupPost} (group chat posts).
 * The community-feed entity is currently stored as {@code Task} and
 * will be renamed to {@code Post} in Phase 3b Session 2 — see
 * {@code docs/WIP_POST_RENAME.md}.</p>
 *
 * <p>The feed entity is stored as {@code Task} for historical reasons,
 * but {@code Task.kind} is the discriminator that determines how the
 * row is rendered + which composer flow created it. A "post" is the
 * canonical user-facing concept; "task" is the persistence shape that
 * happens to back it.</p>
 *
 * <p>Pre-2026-05-03 the kind vocabulary was a free-form string
 * validated only at the service layer (see {@code TaskService.ALLOWED_KINDS}).
 * That worked but offered no IDE auto-completion, no compile-time
 * misspelling protection, and no central reference for what kinds
 * exist. This enum is the typed source of truth — the service
 * validation is now a thin wrapper around {@link #isValid(String)}.</p>
 *
 * <p>The enum carries the wire string ({@link #wire()}) explicitly
 * because some kinds use kebab-case ({@code lost-found},
 * {@code blog-promo}) which can't be Java enum names. The wire string
 * is what FE + DB + REST surfaces all see.</p>
 */
public enum PostKind {

    /** Plain note — the catch-all when no other kind fits. */
    POST("post"),

    /** Neighbor needs help. Carries priority chip (URGENT/HIGH/...). */
    ASK("ask"),

    /** Neighbor is offering something free. */
    OFFER("offer"),

    /** Short prep tip / advice. */
    TIP("tip"),

    /** Recommend a service / vendor / location. */
    RECOMMENDATION("recommendation"),

    /** Lost-or-found item. */
    LOST_FOUND("lost-found"),

    /** Update to an active alert (system-authored or user follow-up). */
    ALERT_UPDATE("alert-update"),

    /** Promo link to a SitPrep curated guide. */
    BLOG_PROMO("blog-promo"),

    /** Buy/sell listing. Carries price badge + "FREE" pill for gifts. */
    MARKETPLACE("marketplace");

    private final String wire;

    PostKind(String wire) {
        this.wire = wire;
    }

    /** The wire-format string sent over REST + STOMP and stored in the DB. */
    public String wire() {
        return wire;
    }

    /**
     * Wire-string set used by the validation guard in TaskService. Built
     * once and held statically so each request doesn't rebuild it.
     */
    public static final Set<String> ALLOWED_WIRE_VALUES = Stream.of(values())
            .map(PostKind::wire)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * True when {@code value} matches a known wire-format kind. Null +
     * blank return false (callers should default to {@link #POST} or
     * {@link #ASK} themselves; this method doesn't make that choice).
     */
    public static boolean isValid(String value) {
        return value != null && ALLOWED_WIRE_VALUES.contains(value);
    }

    /**
     * Look up the enum by wire string. Returns null when the value is
     * unknown — callers can fall back to a default.
     */
    public static PostKind fromWire(String wire) {
        if (wire == null) return null;
        for (PostKind k : values()) {
            if (k.wire.equals(wire)) return k;
        }
        return null;
    }

    /**
     * True when this kind has an explicit user-entered title (composer
     * exposes a title field separate from the body). Kinds that fail
     * this check ({@link #POST}, {@link #TIP}) are body-only — the
     * service layer accepts a null title rather than synthesizing one
     * from the description's first line.
     *
     * <p>Pre-2026-05-04 the FE composer synthesized a title from the
     * first 80 chars of description for {@code post}/{@code tip} so
     * {@code Task.title}'s {@code nullable=false} constraint was
     * satisfied. That produced a visible "bold-title-then-same-text-
     * in-body" duplicate on every neighbor share, since the synthesized
     * title was just the start of the description rendered twice. The
     * column is now nullable, the FE composer no longer synthesizes,
     * and this method draws the line at validation time.</p>
     */
    public boolean requiresTitle() {
        switch (this) {
            case POST:
            case TIP:
                return false;
            default:
                return true;
        }
    }
}
