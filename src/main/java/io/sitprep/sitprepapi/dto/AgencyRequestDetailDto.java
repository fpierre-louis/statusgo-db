package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.VerificationApplication;
import io.sitprep.sitprepapi.domain.VerificationApplicationNote;

import java.time.Instant;
import java.util.List;

public record AgencyRequestDetailDto(
        AgencyRequestDto request,
        String legalName,
        String publicName,
        String website,
        String accountType,
        String serviceArea,
        String reviewerNotes,
        String reviewerEmail,
        String publisherServiceArea,
        String publisherPermanentAddress,
        String publisherTemporaryEventAddress,
        String logoImageUrl,
        Double groupJurisdictionLat,
        Double groupJurisdictionLng,
        Double groupJurisdictionRadiusMiles,
        Boolean groupAgencyAuthorized,
        List<NoteDto> notes
) {
    public static AgencyRequestDetailDto from(VerificationApplication app,
                                              Group group,
                                              List<VerificationApplicationNote> notes) {
        return new AgencyRequestDetailDto(
                AgencyRequestDto.from(app, group),
                app.getLegalName(),
                app.getPublicName(),
                app.getWebsite(),
                app.getAccountType(),
                app.getServiceArea(),
                app.getReviewerNotes(),
                app.getReviewerEmail(),
                app.getPublisherServiceArea(),
                app.getPublisherPermanentAddress(),
                app.getPublisherTemporaryEventAddress(),
                app.getLogoImageUrl(),
                group == null ? null : group.getJurisdictionLat(),
                group == null ? null : group.getJurisdictionLng(),
                group == null ? null : group.getJurisdictionRadiusMiles(),
                group == null ? null : group.isAgencyAuthorized(),
                notes == null ? List.of() : notes.stream().map(NoteDto::from).toList());
    }

    public record NoteDto(
            Long id,
            String authorEmail,
            String note,
            Instant createdAt
    ) {
        public static NoteDto from(VerificationApplicationNote note) {
            return new NoteDto(
                    note.getId(),
                    note.getAuthorEmail(),
                    note.getNote(),
                    note.getCreatedAt());
        }
    }
}
