package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.MentionToken;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MentionDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ONE place a mention token becomes a name.
 *
 * <p><b>Why one resolver rather than a helper each caller reaches for.</b> The
 * token format's accepted cost is that unresolved content prints a raw token
 * (see {@link MentionToken}). That trade is only acceptable while the number of
 * places that know how to resolve is exactly one — two implementations is how a
 * vocabulary forks, and this codebase has three recorded instances of that
 * (ResourceCategory, post-kind titles, and the hazard set where
 * {@code extreme_heat} silently dropped data). So both the DTO fold and the
 * notification body builder come through here.</p>
 *
 * <p><b>Deleted accounts resolve to a tombstone, not to nothing.</b> An id with
 * no {@code UserInfo} yields {@code deleted=true} and
 * {@link MentionToken#TOMBSTONE_NAME}. Dropping it instead would silently change
 * what a sentence said; rendering the raw uuid would leak an internal id at the
 * one moment the person it referred to is gone.</p>
 */
@Service
public class MentionService {

    private final UserInfoRepo userInfoRepo;

    public MentionService(UserInfoRepo userInfoRepo) {
        this.userInfoRepo = userInfoRepo;
    }

    /**
     * Resolve ids to display rows, preserving the caller's order. Unknown ids
     * come back as tombstones rather than being filtered out, so the returned
     * list always lines up 1:1 with what the content references.
     */
    @Transactional(readOnly = true)
    public List<MentionDto> resolve(Collection<String> userIds) {
        Set<String> ids = normalize(userIds);
        if (ids.isEmpty()) return List.of();

        Map<String, UserInfo> byId = new HashMap<>();
        for (UserInfo u : userInfoRepo.findAllById(ids)) {
            if (u.getId() != null) byId.put(u.getId().toLowerCase(Locale.ROOT), u);
        }

        List<MentionDto> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            UserInfo u = byId.get(id);
            out.add(u == null
                    ? new MentionDto(id, MentionToken.TOMBSTONE_NAME, true)
                    : new MentionDto(id, displayName(u), false));
        }
        return out;
    }

    /** Resolve straight from content — the shape the DTO fold wants. */
    @Transactional(readOnly = true)
    public List<MentionDto> resolveFrom(String content) {
        return resolve(MentionToken.extractIds(content));
    }

    /**
     * Content with every token replaced by {@code @Name}, for surfaces that
     * cannot render structure: push bodies, feed snippets, inbox titles.
     *
     * <p>This is the second of the two callers the class doc promises. A push
     * body that printed {@code @[uid:338ea7ea-...]} would be the token cost
     * landing on the one surface the user cannot fix by scrolling.</p>
     */
    @Transactional(readOnly = true)
    public String toPlainText(String content) {
        if (!MentionToken.hasMention(content)) return content;
        Map<String, String> names = new HashMap<>();
        for (MentionDto m : resolveFrom(content)) names.put(m.userId(), m.displayName());
        return MentionToken.toPlainText(content, names);
    }

    /**
     * Ids present in {@code updated} but not in {@code previous}.
     *
     * <p><b>Edits notify only what was added.</b> Re-notifying everyone on every
     * edit would make a typo fix indistinguishable from being mentioned, and
     * would hand anyone a way to ping a person repeatedly by editing one
     * comment. Removing a mention notifies nobody — there is no event there to
     * tell someone about.</p>
     */
    public List<String> newlyMentioned(String previousContent, String updatedContent) {
        Set<String> before = new LinkedHashSet<>(MentionToken.extractIds(previousContent));
        List<String> after = MentionToken.extractIds(updatedContent);
        List<String> added = new ArrayList<>();
        for (String id : after) if (!before.contains(id)) added.add(id);
        return added;
    }

    /**
     * Emails for a set of mentioned ids, skipping ids with no account. Used by
     * the notify path, which addresses recipients by email everywhere else.
     */
    @Transactional(readOnly = true)
    public List<String> emailsFor(Collection<String> userIds) {
        Set<String> ids = normalize(userIds);
        if (ids.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (UserInfo u : userInfoRepo.findAllById(ids)) {
            if (u.getUserEmail() != null && !u.getUserEmail().isBlank()) out.add(u.getUserEmail());
        }
        return out;
    }

    private static Set<String> normalize(Collection<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (String id : ids) {
            if (id == null) continue;
            String k = id.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    private static String displayName(UserInfo u) {
        String f = u.getUserFirstName() == null ? "" : u.getUserFirstName().trim();
        String l = u.getUserLastName() == null ? "" : u.getUserLastName().trim();
        String name = (f + " " + l).trim();
        if (!name.isEmpty()) return name;
        // Never fall back to the raw email — a mention chip is a PUBLIC surface
        // and the email is not. An account with no name set renders as the
        // tombstone phrase, which says "we cannot name this person" without
        // disclosing how to reach them.
        return MentionToken.TOMBSTONE_NAME;
    }
}
