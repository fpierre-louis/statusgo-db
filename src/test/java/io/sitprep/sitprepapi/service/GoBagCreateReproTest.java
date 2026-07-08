package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.dto.GoBagDtos.CreateGoBagRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction for the reported POST /go-bags 500. Exercises
 * GoBagService.createBag with seedItems=true (the wizard's "Save my go bag"
 * path) against the H2 test schema, so any JPA-mapping / persistence fault
 * surfaces here instead of only crashing the live server.
 */
@SpringBootTest
@ActiveProfiles("test")
class GoBagCreateReproTest {

    @Autowired GoBagService goBagService;
    @Autowired DemographicRepo demographicRepo;

    @Test
    void createBagWithSeededItemsDoesNotThrow() {
        String householdId = "hh-repro-" + UUID.randomUUID();

        Demographic d = new Demographic();
        d.setHouseholdId(householdId);
        d.setOwnerEmail("owner@x.com");
        d.setAdults(2);
        d.setKids(1);
        demographicRepo.save(d);

        CreateGoBagRequest req = new CreateGoBagRequest(
                UUID.randomUUID().toString(),
                "Family go bag",
                "home",
                "Hallway closet",
                null, null,
                "hybrid",
                null,
                true,   // seedItems — the crashing path
                false, false,
                Map.of("water", "2031-01-01")
        );

        GoBagDto dto = goBagService.createBag("owner@x.com", householdId, req);

        assertThat(dto).isNotNull();
        assertThat(dto.items()).isNotEmpty();
        assertThat(dto.itemsTotal()).isGreaterThan(0);
    }
}
