package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.HouseholdPet;
import io.sitprep.sitprepapi.dto.HouseholdPetDto;
import io.sitprep.sitprepapi.repo.HouseholdPetRepo;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class HouseholdPetService {

    private final HouseholdPetRepo repo;
    private final HouseholdAccessService access;

    public HouseholdPetService(HouseholdPetRepo repo,
                               HouseholdAccessService access) {
        this.repo = repo;
        this.access = access;
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
        return toDto(repo.save(pet));
    }

    @Transactional
    public HouseholdPetDto update(String caller, String householdId, String id, UpsertRequest body) {
        access.requireCanAdminHousehold(caller, householdId);
        HouseholdPet pet = loadOr404(householdId, id);
        if (body.name() != null && !body.name().isBlank()) pet.setName(body.name().trim());
        if (body.species() != null) pet.setSpecies(clean(body.species()));
        if (body.notes() != null) pet.setNotes(clean(body.notes()));
        if (body.photoUrl() != null) pet.setPhotoUrl(clean(body.photoUrl()));
        return toDto(repo.save(pet));
    }

    @Transactional
    public void remove(String caller, String householdId, String id) {
        access.requireCanAdminHousehold(caller, householdId);
        repo.delete(loadOr404(householdId, id));
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
