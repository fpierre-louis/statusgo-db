package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.domain.Demographic;
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
        // Check if a demographic record already exists for this ownerEmail
        Optional<Demographic> existingDemographic = demographicRepository.findByOwnerEmail(demographic.getOwnerEmail());

        if (existingDemographic.isPresent()) {
            // Update the existing record
            Demographic existing = existingDemographic.get();
            existing.setInfants(demographic.getInfants());
            existing.setAdults(demographic.getAdults());
            existing.setKids(demographic.getKids());
            existing.setDogs(demographic.getDogs());
            existing.setCats(demographic.getCats());
            existing.setPets(demographic.getPets());
            return demographicRepository.save(existing);
        } else {
            // Save as a new record
            return demographicRepository.save(demographic);
        }
    }

    public List<Demographic> getAllDemographics() {
        return demographicRepository.findAll();
    }

    public Optional<Demographic> getDemographicByOwnerEmail(String ownerEmail) {
        return demographicRepository.findByOwnerEmail(ownerEmail);
    }

    public List<Demographic> getDemographicsByAdminEmail(String adminEmail) {
        return demographicRepository.findByAdminEmail(adminEmail);
    }
}
