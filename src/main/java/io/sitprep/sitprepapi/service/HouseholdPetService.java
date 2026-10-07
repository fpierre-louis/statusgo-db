package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PetSpecies;
import io.sitprep.sitprepapi.domain.HouseholdPet;
import io.sitprep.sitprepapi.dto.HouseholdPetDto;
import io.sitprep.sitprepapi.repo.HouseholdPetRepo;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Named pets. Like manual members, the plan's pet counts move here (V100): a
 * named pet fills a placeholder of its species or raises the count, a delete
 * lowers it, a species change moves it — via {@link HouseholdCompositionService}.
 */
@Service
public class HouseholdPetService {

    private final HouseholdPetRepo repo;
    private final HouseholdAccessService access;
    private final HouseholdCompositionService composition;

    public HouseholdPetService(HouseholdPetRepo repo,
                               HouseholdAccessService access,
                               HouseholdCompositionService composition) {
        this.repo = repo;
        this.access = access;
        this.composition = composition;
    }

    public List<HouseholdPetDto> list(String caller, String householdId) {
        access.requireCanReadHousehold(caller, householdId);
        return repo.findByHouseholdIdOrderByCreatedAtAsc(householdId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public HouseholdPetDto add(String caller, String householdId, UpsertRequest body) {
        access.requireCanAdminHousehold(caller, householdId);
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        }

        HouseholdPet pet = new HouseholdPet();
        pet.setId(body.id() == null || body.id().isBlank() ? UUID.randomUUID().toString() : body.id());
        pet.setHouseholdId(householdId);
        pet.setName(body.name().trim());
        pet.setSpecies(clean(body.species()));
        pet.setNotes(clean(body.notes()));
        pet.setPhotoUrl(clean(body.photoUrl()));
        HouseholdPet saved = repo.save(pet);
        composition.raiseToNamed(householdId, true, caller);
        return toDto(saved);
    }

    @Transactional
    public HouseholdPetDto update(String caller, String householdId, String id, UpsertRequest body) {
        access.requireCanAdminHousehold(caller, householdId);
        HouseholdPet pet = loadOr404(householdId, id);
        PetSpecies before = PetSpecies.of(pet.getSpecies());
        if (body.name() != null && !body.name().isBlank()) pet.setName(body.name().trim());
        if (body.species() != null) pet.setSpecies(clean(body.species()));
        if (body.notes() != null) pet.setNotes(clean(body.notes()));
        if (body.photoUrl() != null) pet.setPhotoUrl(clean(body.photoUrl()));
        HouseholdPet saved = repo.save(pet);
        if (PetSpecies.of(saved.getSpecies()) != before) {
            repo.flush();
            composition.lowerSpecies(householdId, before);
            composition.raiseToNamed(householdId, true, caller);
        }
        return toDto(saved);
    }

    @Transactional
    public void remove(String caller, String householdId, String id) {
        access.requireCanAdminHousehold(caller, householdId);
        HouseholdPet pet = loadOr404(householdId, id);
        PetSpecies species = PetSpecies.of(pet.getSpecies());
        repo.delete(pet);
        repo.flush();
        composition.lowerSpecies(householdId, species);
    }

    private HouseholdPet loadOr404(String householdId, String id) {
        HouseholdPet pet = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!householdId.equals(pet.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return pet;
    }

    private HouseholdPetDto toDto(HouseholdPet pet) {
        return new HouseholdPetDto(
                pet.getId(),
                pet.getHouseholdId(),
                pet.getName(),
                pet.getSpecies(),
                pet.getNotes(),
                pet.getPhotoUrl(),
                pet.getCreatedAt(),
                pet.getUpdatedAt()
        );
    }

    private static String clean(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    public record UpsertRequest(
            String id,
            String name,
            String species,
            String notes,
            String photoUrl
    ) {}
}
