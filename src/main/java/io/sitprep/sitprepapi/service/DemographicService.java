package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.dto.DemographicDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DemographicService {

    private static final Logger log = LoggerFactory.getLogger(DemographicService.class);

    private final DemographicRepo demographicRepository;
    private final HouseholdResolver householdResolver;
    private final WebSocketMessageSender ws;
    private final HouseholdAccessService access;
    private final HouseholdCompositionService composition;

    public DemographicService(DemographicRepo demographicRepository,
                              HouseholdResolver householdResolver,
                              WebSocketMessageSender ws,
                              HouseholdAccessService access,
                              HouseholdCompositionService composition) {
        this.demographicRepository = demographicRepository;
        this.householdResolver = householdResolver;
        this.ws = ws;
        this.access = access;
        this.composition = composition;
    }

    /**
     * Save the caller's household head count.
     *
     * <p><b>Tightened 2026-10-07 (household roster EXEC-B).</b> The row is
     * resolved on the server, always: the household being edited (the
     * {@code X-Household-Id} header, admin-gated) or else the caller's base
     * household while they belong to it — and then that household's ONE row,
     * the same row every reader picks ({@code findFirstByHouseholdIdOrderByIdDesc}).
     * A client-sent {@code id} is ignored: it used to fall through to a bare
     * {@code save()} of the request body, i.e. a merge onto whatever row that
     * id named. And the counts may not go below what the household has NAMED
     * in any band: 409 with the band and its floor
     * ({@link HouseholdCompositionService#requireAtLeastNamed}).</p>
     *
     * <p>Only a caller with no household at all still writes an owner-keyed
     * row (the pre-household legacy shape).</p>
     */
    public Demographic saveDemographic(Demographic demographic) {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        demographic.setOwnerEmail(currentUserEmail);

        String targetHh = householdResolver.writableTargetHousehold(currentUserEmail);
        if (targetHh == null) {
            String base = householdResolver.baseHouseholdIdFor(currentUserEmail);
            if (base != null && access.canReadHousehold(currentUserEmail, base)) targetHh = base;
        }

        if (targetHh != null) {
            composition.requireAtLeastNamed(targetHh, HouseholdCompositionService.countsOf(demographic));
            final String hh = targetHh;
            Demographic row = demographicRepository
                    .findFirstByHouseholdIdOrderByIdDesc(hh)
                    // Adopt the caller's pre-household row rather than orphan it.
                    .or(() -> demographicRepository.findFirstByOwnerEmailIgnoreCaseOrderByIdDesc(currentUserEmail)
                            .filter(d -> d.getHouseholdId() == null))
                    .orElseGet(Demographic::new);
            copyCounts(demographic, row);
            // Preserve admin-emails unless the caller sent a list; an edit to the
            // counts shouldn't silently wipe them.
            if (demographic.getAdminEmails() != null) row.setAdminEmails(demographic.getAdminEmails());
            row.setHouseholdId(hh);
            if (row.getOwnerEmail() == null) row.setOwnerEmail(currentUserEmail);
            return saveIdempotent(row, currentUserEmail, hh);
        }

        Demographic row = demographicRepository.findFirstByOwnerEmailIgnoreCaseOrderByIdDesc(currentUserEmail)
                .orElseGet(Demographic::new);
        copyCounts(demographic, row);
        if (demographic.getAdminEmails() != null) row.setAdminEmails(demographic.getAdminEmails());
        row.setOwnerEmail(currentUserEmail);
        return saveIdempotent(row, currentUserEmail, row.getHouseholdId());
    }

    private static void copyCounts(Demographic from, Demographic to) {
        to.setInfants(from.getInfants());
        to.setAdults(from.getAdults());
        to.setTeens(from.getTeens());
        to.setKids(from.getKids());
        to.setDogs(from.getDogs());
        to.setCats(from.getCats());
        to.setPets(from.getPets());
    }

    /**
     * Persist the demographic row, treating a UNIQUE-constraint clash on
     * {@code uk_demographic_owner_household} / {@code uk_demographic_owner_no_household}
     * (V5 migration) as idempotent success — re-fetch the winning row and return
     * it. Closes the race window where two concurrent saveDemographic calls both
     * see "no existing row" and both insert. Falls through to rethrow when the
     * row truly cannot be located afterward (would indicate a schema drift).
     */
    private Demographic saveIdempotent(Demographic row, String ownerEmail, String householdId) {
        Demographic saved;
        try {
            saved = demographicRepository.save(row);
        } catch (DataIntegrityViolationException ex) {
            log.info("demographic upsert lost a race for owner={} household={}; returning the winner",
                    ownerEmail, householdId);
            Optional<Demographic> winner = householdId == null
                    ? demographicRepository.findByOwnerEmailIgnoreCase(ownerEmail)
                    : demographicRepository.findFirstByHouseholdIdOrderByIdDesc(householdId);
            saved = winner.orElseThrow(() -> ex);
        }
        // Every save branch funnels through here, so this is the one place to
        // broadcast the demographic change to the household's other devices.
        broadcastDemographic(saved);
        return saved;
    }

    /**
     * Push the updated head-count to {@code /topic/households/{hid}/demographic}
     * so every member's dashboard, readiness score, and food planner reflect the
     * new counts without a manual reload. Sent AFTER commit when a transaction is
     * active; otherwise immediately (this service isn't @Transactional, so a
     * plain save() has already committed by the time we're here). No-op if the
     * row has no household id yet.
     */
    private void broadcastDemographic(Demographic saved) {
        if (saved == null) return;
        final String householdId = saved.getHouseholdId();
        if (householdId == null || householdId.isBlank()) return;
        final Map<String, Object> frame = Map.of(
                "type", "demographic", "demographic", DemographicDto.from(saved));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { ws.sendHouseholdDemographic(householdId, frame); }
            });
        } else {
            ws.sendHouseholdDemographic(householdId, frame);
        }
    }

    // getAllDemographics() removed with its only caller, the deleted dump-all
    // route. See DemographicResource for why.

    public Optional<Demographic> getDemographicForCurrentUser() {
        return demographicRepository.findByOwnerEmailIgnoreCase(AuthUtils.getCurrentUserEmail());
    }

    public List<Demographic> getDemographicsForCurrentAdmin() {
        return demographicRepository.findByAdminEmail(AuthUtils.getCurrentUserEmail());
    }

    public Optional<Demographic> getDemographicByOwnerEmail(String ownerEmail) {
        return demographicRepository.findByOwnerEmailIgnoreCase(ownerEmail);
    }

    public List<Demographic> getDemographicsByAdminEmail(String adminEmail) {
        return demographicRepository.findByAdminEmail(adminEmail);
    }
}
