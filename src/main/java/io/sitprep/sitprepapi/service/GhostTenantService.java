package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GhostDemandVote;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GhostDemandVoteRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Ghost Tenant claim engine (Phase 3). Two responsibilities:
 *
 * <ol>
 *   <li><b>Demand signal</b> — a resident registers interest in an unclaimed
 *       ("GHOST") civic group; we count DISTINCT residents idempotently.</li>
 *   <li><b>Outreach</b> — a scheduled worker emails the group's ONE
 *       human-verified official contact, at a strict weekly cadence, capped at a
 *       lifetime maximum, honouring a permanent opt-out.</li>
 * </ol>
 *
 * <p><b>Privacy (hard rules):</b> there is NO scraping and NO social-media
 * targeting here. Outreach is only ever sent to {@code officialContactEmail},
 * which a human sets. The email send is currently SIMULATED via the logger
 * (no {@code EmailService} exists yet); wiring a real transport is a later,
 * transport-only change.</p>
 */
@Service
public class GhostTenantService {

    private static final Logger log = LoggerFactory.getLogger(GhostTenantService.class);

    /** Never email an unclaimed civic contact more than this many times, ever. */
    static final int OUTREACH_LIFETIME_CAP = 3;
    /** Minimum spacing between outreach emails to the same group. */
    static final Duration OUTREACH_CADENCE = Duration.ofDays(7);

    private static final String GHOST = "GHOST";

    private final GroupRepo groupRepo;
    private final GhostDemandVoteRepo voteRepo;

    public GhostTenantService(GroupRepo groupRepo, GhostDemandVoteRepo voteRepo) {
        this.groupRepo = groupRepo;
        this.voteRepo = voteRepo;
    }

    /**
     * Register one resident's demand for a GHOST group. Idempotent per resident:
     * a second call from the same email is a no-op that returns the current
     * count. Returns the group's demand signal after the call.
     *
     * @throws IllegalArgumentException if the group doesn't exist / no voter email
     * @throws IllegalStateException    if the group is not in the GHOST claim state
     */
    @Transactional
    public int recordDemandSignal(String groupId, String voterEmail) {
        if (voterEmail == null || voterEmail.isBlank()) {
            throw new IllegalArgumentException("voter email is required");
        }
        Group group = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));
        if (!GHOST.equalsIgnoreCase(group.getClaimState())) {
            throw new IllegalStateException("Group " + groupId + " is not in the GHOST claim state");
        }

        String email = voterEmail.trim().toLowerCase();
        // Idempotency: one +1 per distinct resident.
        if (voteRepo.existsByGroupIdAndVoterEmailIgnoreCase(groupId, email)) {
            return group.getGhostDemandSignal();
        }
        GhostDemandVote vote = new GhostDemandVote();
        vote.setGroupId(groupId);
        vote.setVoterEmail(email);
        try {
            voteRepo.saveAndFlush(vote);
        } catch (DataIntegrityViolationException raced) {
            // Concurrent duplicate vote — already counted by the winning request.
            return group.getGhostDemandSignal();
        }

        group.setGhostDemandSignal(group.getGhostDemandSignal() + 1);
        groupRepo.save(group);
        return group.getGhostDemandSignal();
    }

    /**
     * Send (simulate) one round of claim outreach to every eligible GHOST group,
     * advancing each group's outreach state. Eligibility (GHOST + demand &gt; 0 +
     * not opted-out + under the lifetime cap + past the weekly cadence + has an
     * official contact) is enforced in the repo query. Returns the number of
     * groups contacted this round.
     */
    @Transactional
    public int processOutreach() {
        Instant cutoff = Instant.now().minus(OUTREACH_CADENCE);
        List<Group> eligible = groupRepo.findGhostOutreachEligible(OUTREACH_LIFETIME_CAP, cutoff);

        for (Group g : eligible) {
            int contactNumber = g.getOutreachCount() + 1;
            // SIMULATED send — no real email/scraping/social. Official channel only.
            log.info("[ghost-outreach] SIMULATED email -> official contact <{}> for ghost group {} ('{}'): "
                            + "{} resident(s) are waiting for you to claim this page. "
                            + "Weekly cadence; contact {} of {} lifetime; one-click opt-out honoured.",
                    g.getOfficialContactEmail(), g.getGroupId(), g.getGroupName(),
                    g.getGhostDemandSignal(), contactNumber, OUTREACH_LIFETIME_CAP);

            g.setOutreachCount(contactNumber);
            g.setLastOutreachDate(Instant.now());
            groupRepo.save(g);
        }

        if (!eligible.isEmpty()) {
            log.info("[ghost-outreach] processed {} eligible ghost group(s).", eligible.size());
        }
        return eligible.size();
    }
}
