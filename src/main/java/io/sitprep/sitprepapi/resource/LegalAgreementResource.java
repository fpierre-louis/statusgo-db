package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.LegalAgreementDtos.LegalAgreementDto;
import io.sitprep.sitprepapi.dto.LegalAgreementDtos.RecordLegalAgreementRequest;
import io.sitprep.sitprepapi.service.LegalAgreementService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LegalAgreementResource {
    private final LegalAgreementService service;

    public LegalAgreementResource(LegalAgreementService service) {
        this.service = service;
    }

    @PostMapping("/api/legal/agreements")
    public ResponseEntity<List<LegalAgreementDto>> record(@RequestBody RecordLegalAgreementRequest request) {
        String uid = AuthUtils.requireAuthenticatedUid();
        String email = AuthUtils.getCurrentUserEmail();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.record(request, uid, email));
    }
}
