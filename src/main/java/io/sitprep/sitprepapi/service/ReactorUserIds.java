package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Lowercased email to {@code UserInfo.id} for a reaction roster (FD-08, additive:
 * the roster keeps {@code userEmail}; build 9 reads it). ONE batched lookup per
 * roster load, never one per reactor. A null repo (a service built by hand in a
 * test) or an email with no profile row simply yields no id.
 */
final class ReactorUserIds {
    private ReactorUserIds() {}

    static Map<String, String> resolve(UserInfoRepo repo, Collection<String> emails) {
        if (repo == null || emails == null || emails.isEmpty()) return Map.of();
        List<String> distinct = emails.stream().filter(Objects::nonNull)
                .map(e -> e.trim().toLowerCase(Locale.ROOT)).filter(e -> !e.isEmpty())
                .distinct().collect(Collectors.toList());
        if (distinct.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (UserInfo u : repo.findByUserEmailIn(distinct)) {
            if (u.getUserEmail() != null && u.getId() != null) {
                out.putIfAbsent(u.getUserEmail().toLowerCase(Locale.ROOT), u.getId());
            }
        }
        return out;
    }

    static String of(Map<String, String> ids, String email) {
        return email == null ? null : ids.get(email.trim().toLowerCase(Locale.ROOT));
    }
}
