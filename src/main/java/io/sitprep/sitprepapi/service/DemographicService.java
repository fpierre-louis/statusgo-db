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

    public DemographicService(DemographicRepo demographicRepository,
                              HouseholdResolver householdResolver,
                              WebSocketMessageSender ws) {
        this.demographicRepository = demographicRepository;
        this.householdResolver = householdResolver;
        this.ws = ws;
    }

    public Demographic saveDemographic(Demographic demographic) {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        demographic.setOwnerEmail(currentUserEmail);

        // Cross-household edit: when an admin edits a NON-base household's plan
        // the FE sends X-Household-Id. Upsert THAT household's single
        // demographic (gated by canWriteHousehold). No header → null → the
        // unchanged base path below runs.
        String targetHh = householdResolver.writableTargetHousehold(currentUserEmail);
        if (targetHh != null) {
            Demographic row = demographicRepository
                    .findFirstByHouseholdIdOrderByIdDesc(targetHh)
                    .orElseGet(Demographic::new);
            row.setInfants(demographic.getInfants());
            row.setAdults(demographic.getAdults());
            row.setTeens(demographic.getTeens());
            row.setKids(demographic.getKids());
            row.setDogs(demographic.getDogs());
            row.setCats(demographic.getCats());
            row.setPets(demographic.getPets());
            // Preserve admin-emails on a cross-household edit too (the base path
            // already does); an admin editing counts shouldn't silently wipe them.
            if (demographic.getAdminEmails() != null) row.setAdminEmails(demographic.getAdminEmails());
            row.setHouseholdId(targetHh);
            if (row.getOwnerEmail() == null) row.setOwnerEmail(currentUserEmail);
            return saveIdempotent(row, currentUserEmail, targetHh);
        }

        Optional<Demographic> existing = demographicRepository.findByOwnerEmailIgnoreCase(currentUserEmail);

        if (existing.isPresent() && demographic.getId() == null) {
            Demographic updated = existing.get();
            updated.setInfants(demographic.getInfants());
            updated.setAdults(demographic.getAdults());
            updated.setTeens(demographic.getTeens());
            updated.setKids(demographic.getKids());
            updated.setDogs(demographic.getDogs());
            updated.setCats(demographic.getCats());
            updated.setPets(demographic.getPets());
            updated.setAdminEmails(demographic.getAdminEmails());
            if (updated.getHouseholdId() == null) {
                updated.setHouseholdId(householdResolver.baseHouseholdIdFor(currentUserEmail));
            }
            return saveIdempotent(updated, currentUserEmail, updated.getHouseholdId());
        }

        if (demographic.getHouseholdId() == null) {
            demographic.setHouseholdId(householdResolver.baseHouseholdIdFor(currentUserEmail));
        }
        return saveIdempotent(demographic, currentUserEmail, demographic.getHouseholdId());
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

    public List<Demographic> getAllDemographics() {
        return demographicRepository.findAll();
    }

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
