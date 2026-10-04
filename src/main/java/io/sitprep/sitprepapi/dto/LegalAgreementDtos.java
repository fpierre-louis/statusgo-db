package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.LegalAgreement;

import java.time.Instant;
import java.util.List;

public final class LegalAgreementDtos {
    private LegalAgreementDtos() {}

    public record RecordLegalAgreementRequest(
            List<String> documentTypes,
            String policyVersion,
            String effectiveDate,
            String authenticationState,
            String acceptanceSurface
    ) {}

    public record LegalAgreementDto(
            Long id,
            String userId,
            String firebaseUid,
            String userEmail,
            String documentType,
            String policyVersion,
            String effectiveDate,
            Instant acceptedAt,
            String authenticationState,
            String acceptanceSurface
    ) {
        public static LegalAgreementDto from(LegalAgreement row) {
            return new LegalAgreementDto(
                    row.getId(),
                    row.getUserId(),
                    row.getFirebaseUid(),
                    row.getUserEmail(),
                    row.getDocumentType(),
                    row.getPolicyVersion(),
                    row.getEffectiveDate(),
                    row.getAcceptedAt(),
                    row.getAuthenticationState(),
                    row.getAcceptanceSurface()
            );
        }
    }
}
