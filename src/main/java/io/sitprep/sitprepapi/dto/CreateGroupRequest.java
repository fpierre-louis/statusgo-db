package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of {@code POST /api/groups}: the fields a creator may choose, and
 * nothing the server owns.
 *
 * <p>Until 2026-10-13 create bound the raw {@code Group} entity, so a body
 * could carry a roster (who then got the group's alert pushes), billing and
 * seat fields, sub/parent links, an alert state, and an existing
 * {@code groupId} plus its {@code version}, the shape of a merge over someone
 * else's group (AUDIT JN-01). The server now mints the id and the join code,
 * puts only the caller on the roster, and starts every group on FREE with no
 * check-in running.</p>
 *
 * <p>{@code groupId} is read for one reason: a body naming a group that
 * already exists is refused with 409 rather than silently answered with a new
 * group beside it. It is never used as the new group's id. There is no
 * {@code version} here, so it can't be bound.</p>
 *
 * <p>Unknown keys are ignored on purpose. The frontend's create flows still
 * send a whole group (roster, owner, alert, code, timestamps); refusing them
 * would break create for every client already shipped, and ignoring them is
 * the point.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateGroupRequest(
        String groupId,
        String groupName,
        String groupType,
        String privacy,
        String description,
        String address,
        String zipCode,
        Double latitude,
        Double longitude,
        String logoImageUrl,
        String businessCategory,
        String websiteUrl
) {}
