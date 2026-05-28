package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GroupMutePref;
import io.sitprep.sitprepapi.repo.GroupMutePrefRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Business logic for per-(user, group) notification mutes.
 *
 * <p>Three callers:
 * <ul>
 *   <li>{@code GroupMuteResource} — the FE long-press Mute sheet
 *       PUTs through here.</li>
 *   <li>{@code MeService} — batch-loads a user's mutes when building
 *       the {@code /api/me} payload so the circle cards can render
 *       the muted-bell affordance without an extra round trip.</li>
 *   <li>{@code NotificationService} — calls {@link #isMuted} at
 *       group-scoped dispatch time to skip FCM (with an inbox log
 *       row still written so the missed message is recoverable).</li>
 * </ul></p>
 */
@Service
public class GroupMuteService {

    /**
     * Sentinel for "muted until I turn it back on". Year 9999 keeps
     * the comparison logic uniform (always a deadline check) while
     * being far enough out that nobody will see it as a literal date.
     * The FE detects this sentinel and renders "Muted" without a
     * deadline rather than "Muted until 9999-12-31".
     */
    public static final Instant INDEFINITE = Instant.parse("9999-12-31T23:59:59Z");

    private final GroupMutePrefRepo repo;

    public GroupMuteService(GroupMutePrefRepo repo) {
        this.repo = repo;
    }

    /**
     * @return true when the user has an active mute for {@code groupId}
     *         at the moment of the call. {@code null}/{@code blank}
     *         inputs return false (nothing to enforce against).
     */
    @Transactional(readOnly = true)
    public boolean isMuted(String userEmail, String groupId) {
        if (userEmail == null || userEmail.isBlank()) return false;
        if (groupId == null || groupId.isBlank()) return false;
        Optional<GroupMutePref> opt = repo.findByUserEmailIgnoreCaseAndGroupId(userEmail, groupId);
        if (opt.isEmpty()) return false;
        Instant until = opt.get().getMutedUntil();
        return until != null && until.isAfter(Instant.now());
    }

    /**
     * Upsert mute deadline. Passing {@code mutedUntil = null}
     * un-mutes (row stays for audit; the enforcement check treats
     * a null deadline the same as no row).
     */
    @Transactional
    public GroupMutePref setMute(String userEmail, String groupId, Instant mutedUntil) {
        if (userEmail == null || userEmail.isBlank()) {
            throw new IllegalArgumentException("userEmail is required");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId is required");
        }
        GroupMutePref pref = repo.findByUserEmailIgnoreCaseAndGroupId(userEmail, groupId)
                .orElseGet(() -> {
                    GroupMutePref p = new GroupMutePref();
                    p.setUserEmail(userEmail.trim().toLowerCase());
                    p.setGroupId(groupId);
                    return p;
                });
        pref.setMutedUntil(mutedUntil);
        pref.setUpdatedAt(Instant.now());
        return repo.save(pref);
    }

    /** Convenience: read current pref or empty. */
    @Transactional(readOnly = true)
    public Optional<GroupMutePref> getMute(String userEmail, String groupId) {
        if (userEmail == null || userEmail.isBlank()) return Optional.empty();
        if (groupId == null || groupId.isBlank()) return Optional.empty();
        return repo.findByUserEmailIgnoreCaseAndGroupId(userEmail, groupId);
    }
}
