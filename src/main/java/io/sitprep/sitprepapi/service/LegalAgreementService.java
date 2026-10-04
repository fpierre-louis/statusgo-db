package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.LegalAgreement;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.LegalAgreementDtos.LegalAgreementDto;
import io.sitprep.sitprepapi.dto.LegalAgreementDtos.RecordLegalAgreementRequest;
import io.sitprep.sitprepapi.repo.LegalAgreementRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class LegalAgreementService {
    private static final Set<String> ALLOWED_DOCS = Set.of(
            "TERMS",
            "PRIVACY",
            "EMERGENCY_DISCLAIMER",
            "COMMUNITY_GUIDELINES"
    );
    private static final Set<String> ALLOWED_AUTH_STATES = Set.of("ACCOUNT", "GUEST");

    private final LegalAgreementRepo repo;
    private final UserInfoRepo users;

    public LegalAgreementService(LegalAgreementRepo repo, UserInfoRepo users) {
        this.repo = repo;
        this.users = users;
    }

    @Transactional
    public List<LegalAgreementDto> record(RecordLegalAgreementRequest request, String firebaseUid, String tokenEmail) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated Firebase uid required");
        }
        String version = clean(request == null ? null : request.policyVersion(), 120);
        String effectiveDate = clean(request == null ? null : request.effectiveDate(), 32);
        String surface = clean(request == null ? null : request.acceptanceSurface(), 120);
        if (version == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "policyVersion required");
        if (effectiveDate == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "effectiveDate required");
        if (surface == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "acceptanceSurface required");

        String authState = token(request == null ? null : request.authenticationState());
        if (!ALLOWED_AUTH_STATES.contains(authState)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authenticationState must be ACCOUNT or GUEST");
        }

        LinkedHashSet<String> docs = new LinkedHashSet<>();
        List<String> requestedDocs = request == null ? null : request.documentTypes();
        if (requestedDocs != null) {
            for (String raw : requestedDocs) {
                String doc = token(raw);
                if (!ALLOWED_DOCS.contains(doc)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported legal document type");
                }
                docs.add(doc);
            }
        }
        if (docs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentTypes required");
        }

        UserInfo user = users.findByFirebaseUid(firebaseUid.trim()).orElse(null);
        String email = tokenEmail != null && !tokenEmail.isBlank()
                ? tokenEmail.trim().toLowerCase(Locale.ROOT)
                : user == null ? null : user.getUserEmail();
        Instant acceptedAt = Instant.now();

        return docs.stream().map(doc -> {
            LegalAgreement row = new LegalAgreement();
            row.setUserId(user == null ? null : user.getId());
            row.setFirebaseUid(firebaseUid.trim());
            row.setUserEmail(email);
            row.setDocumentType(doc);
            row.setPolicyVersion(version);
            row.setEffectiveDate(effectiveDate);
            row.setAcceptedAt(acceptedAt);
            row.setAuthenticationState(authState);
            row.setAcceptanceSurface(surface);
            return LegalAgreementDto.from(repo.save(row));
        }).toList();
    }

    private static String clean(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String token(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
