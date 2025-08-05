package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DemographicService {

    private final DemographicRepo demographicRepository;

    public DemographicService(DemographicRepo demographicRepository) {
        this.demographicRepository = demographicRepository;
    }

    public Demographic saveDemographic(Demographic demographic) {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        demographic.setOwnerEmail(currentUserEmail);

        Optional<Demographic> existing = demographicRepository.findByOwnerEmailIgnoreCase(currentUserEmail);

        if (existing.isPresent() && demographic.getId() == null) {
            Demographic updated = existing.get();
            updated.setInfants(demographic.getInfants());
            updated.setAdults(demographic.getAdults());
            updated.setKids(demographic.getKids());
            updated.setDogs(demographic.getDogs());
            updated.setCats(demographic.getCats());
            updated.setPets(demographic.getPets());
            updated.setAdminEmails(demographic.getAdminEmails());
            return demographicRepository.save(updated);
        }

        return demographicRepository.save(demographic);
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
