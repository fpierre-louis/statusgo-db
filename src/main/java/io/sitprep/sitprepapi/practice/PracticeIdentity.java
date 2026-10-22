package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resolves the caller's stable {@code UserInfo.id} for Practice writes (owner
 * ruling 2026-10-22: stop relying on raw email as the long-term completer
 * identifier). A lookup miss is tolerated and returns null: the write goes
 * through with no id rather than failing a household's practice.
 */
@Component
public class PracticeIdentity {

    /** No lookup at all; for tests that do not care about identity. */
    static final PracticeIdentity NONE = new PracticeIdentity(null);

    private final UserInfoRepo userInfoRepo;

    public PracticeIdentity(UserInfoRepo userInfoRepo) {
        this.userInfoRepo = userInfoRepo;
    }

    /** The caller's {@code UserInfo.id}, or null when the email is blank or has no user row. */
    public String userIdFor(String email) {
        if (userInfoRepo == null || email == null || email.isBlank()) return null;
        return userInfoRepo.findByUserEmailNormalized(email.trim().toLowerCase(Locale.ROOT))
                .map(UserInfo::getId)
                .orElse(null);
    }
}
