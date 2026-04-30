package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserAlertPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAlertPreferenceRepo extends JpaRepository<UserAlertPreference, String> {

    /**
     * Lookup by lowercased email. The PK is the email itself (per the
     * entity), so this is a thin pass-through to {@code findById} that
     * also makes the case-insensitive normalization explicit at the
     * call site. {@code PushPolicyService} normalizes incoming emails
     * before lookup.
     */
    default Optional<UserAlertPreference> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return findById(email.trim().toLowerCase());
    }
}
