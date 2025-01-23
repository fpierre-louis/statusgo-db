package io.sitprep.sitprepapi.service;


import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.domain.Demographic;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DemographicService {

    private final DemographicRepo demographicRepository;

    public DemographicService(DemographicRepo demographicRepository) {
        this.demographicRepository = demographicRepository;
    }

    public Demographic saveDemographic(Demographic demographic) {
        return demographicRepository.save(demographic);
    }

    public List<Demographic> getAllDemographics() {
        return demographicRepository.findAll();
    }

    public List<Demographic> getDemographicsByOwnerEmail(String ownerEmail) {
        return demographicRepository.findByOwnerEmail(ownerEmail);
    }

    public List<Demographic> getDemographicsByAdminEmail(String adminEmail) {
        return demographicRepository.findByAdminEmail(adminEmail);
    }
}