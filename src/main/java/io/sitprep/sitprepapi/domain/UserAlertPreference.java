package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;

/**
 * Per-user push + inbox preferences. Implements the
 * {@code UserAlertPreference} entity proposed in
 * {@code docs/PUSH_NOTIFICATION_POLICY.md}.
 *
 * <p>Read by {@code PushPolicyService.evaluate(...)} at every send
 * decision to map (category, severity, time-of-day) → Lane A push /
 * Lane B inbox-only / Lane C ephemeral / DROP. Default record is
 * auto-created on first push send when one doesn't exist, so existing
 * users are opt-in to everything by default — no migration backfill
 * required.</p>
 *
 * <p><b>Field group A — master kill switches.</b> {@code pushEnabled}
 * and {@code inboxEnabled} short-circuit everything else. When push is
 * disabled, all categories drop to Lane B (still earn an inbox row);
 * when inbox is also disabled, every category drops to Lane C
 * (ephemeral banner only) or DROP.</p>
 *
 * <p><b>Field group B — per-category opt-outs.</b> Mirror the eight
 * Lane-A categories from the policy doc. A user can mute earthquakes
 * but keep wildfires; mute task assignments but keep group alerts.
 * Boolean defaults to {@code true} for every category — opt-in by
 * default, opt-out is explicit.</p>
 *
 * <p><b>Field group C — quiet hours.</b> When {@code quietHoursEnabled},
 * Lane A pushes between {@code quietStart} and {@code quietEnd} (in
 * the user's {@code timezone}) defer to 7am local UNLESS the category
 * is on the critical-bypass list (NWS Extreme, M6.0+, plan activation
 * received, household group alert flip). The bypass list is hard-coded
 * in {@code PushPolicyService} per the spec — not user-configurable.</p>
 */
@Entity
@Getter
@Setter
@Table(name = "user_alert_preference")
public class UserAlertPreference {

    /**
     * Primary key = user's email (lowercased on write). Mirrors the
     * pattern used by {@code NotificationLog.recipientEmail} — keying
     * on email instead of UUID id keeps the table joinable to legacy
     * NotificationService write paths without an extra lookup.
     */
    @Id
    @Column(name = "user_email", nullable = false, length = 255)
    private String userEmail;

    // ----- Master kill switches -----

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled = true;

    @Column(name = "inbox_enabled", nullable = false)
    private boolean inboxEnabled = true;

    // ----- Per-category opt-outs (Lane A categories from spec) -----

    @Column(name = "nws_alerts", nullable = false)
    private boolean nwsAlerts = true;

    @Column(name = "earthquakes", nullable = false)
    private boolean earthquakes = true;

    @Column(name = "wildfires", nullable = false)
    private boolean wildfires = true;

    @Column(name = "group_alerts", nullable = false)
    private boolean groupAlerts = true;

    @Column(name = "plan_activations", nullable = false)
    private boolean planActivations = true;

    @Column(name = "activation_acks", nullable = false)
    private boolean activationAcks = true;

    @Column(name = "task_assignments", nullable = false)
    private boolean taskAssignments = true;

    @Column(name = "pending_members", nullable = false)
    private boolean pendingMembers = true;

    // ----- Quiet hours -----

    @Column(name = "quiet_hours_enabled", nullable = false)
    private boolean quietHoursEnabled = false;

    /** Local-time start of quiet window (default 21:00). */
    @Column(name = "quiet_start", nullable = false)
    private LocalTime quietStart = LocalTime.of(21, 0);

    /** Local-time end of quiet window (default 07:00). */
    @Column(name = "quiet_end", nullable = false)
    private LocalTime quietEnd = LocalTime.of(7, 0);

    /**
     * IANA timezone (e.g. "America/New_York"). Defaults to ET on
     * record creation; FE settings page lets the user pick. Used by
     * {@code PushPolicyService} to evaluate "is now within quiet hours?"
     * against the user's local clock, not server time.
     */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "America/New_York";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onWrite() {
        updatedAt = Instant.now();
        if (userEmail != null) userEmail = userEmail.toLowerCase();
    }
}
