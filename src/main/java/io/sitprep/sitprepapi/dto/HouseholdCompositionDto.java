package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.constant.PetSpecies;

import java.time.Instant;
import java.util.List;

/**
 * Who a household plans for — ONE backend derivation that the household page,
 * the "Add a person or a pet" drawer and Home all print from
 * ({@code GET /api/households/{id}/composition}).
 *
 * <p>The rows are the plan's demographic counts made concrete: every counted
 * person is a row — an ACCOUNT, a MANUAL member, or an unnamed PLACEHOLDER —
 * and every counted pet is a NAMED pet or a PLACEHOLDER. Placeholders per band
 * are {@code max(0, count − named)}; the write paths keep {@code count ≥ named}
 * so in practice they are exactly {@code count − named}. Ordering: accounts
 * (you first, then roster order) → manual (oldest first) → placeholders
 * (ADULT, TEEN, KID, INFANT); pets: named (oldest first) → placeholders
 * (DOG, CAT, OTHER).</p>
 *
 * <p>Invariants a client may rely on: {@code people.size() == summary.people},
 * {@code pets.size() == summary.pets}, {@code summary.named + summary.unnamed
 * == summary.total}, and — when {@code planned} — {@code summary.total} equals
 * the sum of {@code counts} (the head count Home's "Household Demographics"
 * essential and {@code EssentialsReadinessService} test for &gt; 0).</p>
 *
 * @param version   wire version; bumped on a breaking shape change
 * @param planned   a demographic row exists (the household answered "who are
 *                  you planning for?"). False → {@code counts} equal
 *                  {@code minimum} (the named totals) and only named rows are
 *                  listed — also the state after {@code DELETE …/composition/counts}.
 * @param counts    the plan demographic
 * @param minimum   per-band floor for a counts write: the named total in that
 *                  band. A write below it is 409.
 */
public record HouseholdCompositionDto(
        int version,
        String householdId,
        Viewer viewer,
        boolean planned,
        Counts counts,
        Counts minimum,
        List<Person> people,
        List<Pet> pets,
        Summary summary
) {
    public static final int VERSION = 1;

    public enum PersonKind { ACCOUNT, MANUAL, PLACEHOLDER }

    public enum PetKind { NAMED, PLACEHOLDER }

    /**
     * @param role          OWNER | ADMIN | MEMBER
     * @param canEditCounts may write {@code PUT …/composition/counts} (admin)
     */
    public record Viewer(String role, boolean canEditCounts) {}

    /** {@code otherPets} is the demographic's {@code pets} column. */
    public record Counts(int adults, int teens, int kids, int infants,
                         int dogs, int cats, int otherPets) {
        public int people() { return adults + teens + kids + infants; }
        public int pets() { return dogs + cats + otherPets; }

        public int of(HouseholdBand b) {
            return switch (b) {
                case ADULT -> adults;
                case TEEN -> teens;
                case KID -> kids;
                case INFANT -> infants;
            };
        }

        public int of(PetSpecies s) {
            return switch (s) {
                case DOG -> dogs;
                case CAT -> cats;
                case OTHER -> otherPets;
            };
        }
    }

    /**
     * One counted person.
     *
     * @param slotKey        stable row key: {@code user:<email>} |
     *                       {@code manual:<id>} | {@code placeholder:<BAND>:<n>}
     *                       (n is 1-based within the band). The user/manual
     *                       forms match {@code EmergencySupportService.subjectKey}.
     * @param name           display name; null for a placeholder and for an
     *                       account with no name on file (never derived from email)
     * @param email          ACCOUNT only (members already see each other's email)
     * @param userId         ACCOUNT only, when the account has a profile row
     * @param manualMemberId MANUAL only
     * @param role           ACCOUNT only: OWNER | ADMIN | MEMBER
     * @param claim          MANUAL only
     * @param relationship   MANUAL only, as typed
     * @param age            MANUAL only, as typed
     */
    public record Person(
            String slotKey,
            PersonKind kind,
            HouseholdBand band,
            String name,
            String email,
            String userId,
            String manualMemberId,
            boolean isYou,
            String role,
            Claim claim,
            String relationship,
            Integer age,
            String photoUrl,
            Capabilities capabilities
    ) {}

    /**
     * A live claim link for a manual member.
     *
     * @param pending true while an unconsumed, unrevoked, unexpired link exists
     */
    public record Claim(boolean pending, Instant invitedAt, Instant expiresAt) {
        public static final Claim NONE = new Claim(false, null, null);
    }

    /**
     * What the viewer may do to this row — the server's own gates, so the FE
     * never decides. {@code canRemove} on a PLACEHOLDER means "lower this band's
     * count by one"; on an ACCOUNT it means "remove from household" (never true
     * for the owner or for yourself — leaving is its own action).
     */
    public record Capabilities(boolean canRename, boolean canRemove,
                               boolean canInviteToClaim, boolean canName) {}

    /** @param slotKey {@code pet:<id>} | {@code placeholder:<SPECIES>:<n>} */
    public record Pet(
            String slotKey,
            PetKind kind,
            PetSpecies species,
            String name,
            String petId,
            String photoUrl,
            Capabilities capabilities
    ) {}

    /**
     * @param people  rows in {@code people[]}
     * @param pets    rows in {@code pets[]}
     * @param total   people + pets — Home's head count
     * @param named   accounts + manual + named pets
     * @param unnamed placeholder rows, people and pets
     */
    public record Summary(int people, int pets, int total, int named, int unnamed,
                          int accounts, int manual, int namedPets) {}
}
