package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.constant.PetSpecies;
import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdClaimInvite;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.HouseholdMemberBand;
import io.sitprep.sitprepapi.domain.HouseholdPet;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.DemographicDto;
import io.sitprep.sitprepapi.dto.DtoImages;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Capabilities;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Claim;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Counts;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Person;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.PersonKind;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Pet;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.PetKind;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Summary;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdClaimInviteRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.HouseholdMemberBandRepo;
import io.sitprep.sitprepapi.repo.HouseholdPetRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The household's composition — who the plan counts — derived in ONE place.
 *
 * <p><b>Read:</b> {@link #compose} turns the demographic counts plus the named
 * rows (accounts, manual members, pets) into the rows every surface prints.</p>
 *
 * <p><b>Write — the counts invariant.</b> For every band and species,
 * {@code count ≥ named}. It is enforced HERE, at write time, by the services
 * that change a named row (the FE used to "bump" the demographic after an add,
 * which double-counted whenever a placeholder already existed):</p>
 * <ul>
 *   <li>a manual member is created / a pet is named → {@link #raiseToNamed}
 *       (given the invariant held before, that IS "fill a placeholder if one
 *       exists, else raise the count by one");</li>
 *   <li>a manual member / pet is deleted → {@link #lowerBand} / {@link #lowerSpecies}
 *       (the household removed that person explicitly);</li>
 *   <li>a manual member's band / a pet's species changes → lower the old, then
 *       raise to named (the count moves with the person);</li>
 *   <li>an account joins → {@link #raiseToNamed} without creating a row (an
 *       ADULT placeholder fills, else adults + 1);</li>
 *   <li>an account leaves or is removed → nothing: the person becomes an
 *       unnamed placeholder, the plan still sizes for them until an admin
 *       lowers the count;</li>
 *   <li>a direct counts write → {@link #requireAtLeastNamed} (409 below named).</li>
 * </ul>
 *
 * <p>No demographic row means the household has not answered "who are you
 * planning for?" (Home's Household Demographics essential is not done). An
 * account joining does not create one — that would mark the essential done for
 * them. Naming a person or pet does create one, seeded at the named totals:
 * naming someone IS answering the question.</p>
 */
@Service
public class HouseholdCompositionService {

    private static final String HOUSEHOLD = "Household";

    private final GroupRepo groupRepo;
    private final DemographicRepo demographicRepo;
    private final HouseholdManualMemberRepo manualRepo;
    private final HouseholdPetRepo petRepo;
    private final HouseholdMemberBandRepo bandRepo;
    private final HouseholdClaimInviteRepo claimRepo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender ws;

    public HouseholdCompositionService(GroupRepo groupRepo,
                                       DemographicRepo demographicRepo,
                                       HouseholdManualMemberRepo manualRepo,
                                       HouseholdPetRepo petRepo,
                                       HouseholdMemberBandRepo bandRepo,
                                       HouseholdClaimInviteRepo claimRepo,
                                       UserInfoRepo userInfoRepo,
                                       WebSocketMessageSender ws) {
        this.groupRepo = groupRepo;
        this.demographicRepo = demographicRepo;
        this.manualRepo = manualRepo;
        this.petRepo = petRepo;
        this.bandRepo = bandRepo;
        this.claimRepo = claimRepo;
        this.userInfoRepo = userInfoRepo;
        this.ws = ws;
    }

    // ── read ────────────────────────────────────────────────────────────

    /** Composition of a household for a viewer. 404 when it is not a household. */
    @Transactional(readOnly = true)
    public HouseholdCompositionDto compose(String householdId, String viewerEmail) {
        Group g = household(householdId);
        if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found");
        Demographic d = demographicRepo.findFirstByHouseholdIdOrderByIdDesc(householdId).orElse(null);
        return compose(g, d, viewerEmail);
    }

    /**
     * Same, with the demographic already resolved by the caller — MeService
     * passes the row it also tests for Home's "demographics done", so the
     * number and the check can never come from two different rows.
     */
    @Transactional(readOnly = true)
    public HouseholdCompositionDto compose(Group g, Demographic d, String viewerEmail) {
        String hid = g.getGroupId();
        String viewer = lower(viewerEmail);
        GroupRole viewerRole = GroupRole.fromGroup(g, viewer);
        boolean viewerAdmin = viewerRole.isAtLeastAdmin();

        List<String> accounts = accountEmails(g);
        Map<String, HouseholdBand> accountBands = accountBands(hid);
        Map<String, UserInfo> profiles = profiles(accounts);
        List<HouseholdManualMember> manual = manualRepo.findByHouseholdIdOrderByCreatedAtAsc(hid);
        List<HouseholdPet> pets = petRepo.findByHouseholdIdOrderByCreatedAtAsc(hid);
        Map<String, HouseholdClaimInvite> claims = new HashMap<>();
        for (HouseholdClaimInvite i : claimRepo.findLiveForHousehold(hid, Instant.now())) {
            claims.merge(i.getManualMemberId(), i,
                    (a, b) -> a.getIssuedAt().isAfter(b.getIssuedAt()) ? a : b);
        }

        Tally named = new Tally();
        List<Person> people = new ArrayList<>();

        // Accounts — you first, then roster order.
        List<String> ordered = new ArrayList<>(accounts);
        if (viewer != null && ordered.remove(viewer)) ordered.add(0, viewer);
        String owner = lower(g.getOwnerEmail());
        for (String email : ordered) {
            HouseholdBand band = accountBands.getOrDefault(email, HouseholdBand.ADULT);
            named.add(band);
            UserInfo u = profiles.get(email);
            boolean isYou = email.equals(viewer);
            boolean isOwner = email.equals(owner);
            people.add(new Person(
                    "user:" + email,
                    PersonKind.ACCOUNT,
                    band,
                    displayName(u),
                    email,
                    u == null ? null : u.getId(),
                    null,
                    isYou,
                    GroupRole.fromGroup(g, email) == GroupRole.NONE ? "MEMBER" : GroupRole.fromGroup(g, email).name(),
                    null,
                    null,
                    null,
                    u == null ? null : DtoImages.avatar(u.getProfileImageUrl()),
                    new Capabilities(false, viewerAdmin && !isOwner && !isYou, false, false)));
        }

        // Manual members — oldest first.
        for (HouseholdManualMember m : manual) {
            HouseholdBand band = m.effectiveBand();
            named.add(band);
            HouseholdClaimInvite inv = claims.get(m.getId());
            people.add(new Person(
                    "manual:" + m.getId(),
                    PersonKind.MANUAL,
                    band,
                    m.getName(),
                    null,
                    null,
                    m.getId(),
                    false,
                    null,
                    inv == null ? Claim.NONE : new Claim(true, inv.getIssuedAt(), inv.getExpiresAt()),
                    m.getRelationship(),
                    m.getAge(),
                    DtoImages.avatar(m.getPhotoUrl()),
                    // Manual-member writes are member-gated (HouseholdManualMemberResource);
                    // a claim link is an admin act.
                    new Capabilities(true, true, viewerAdmin, false)));
        }

        Counts counts = d == null ? new Counts(0, 0, 0, 0, 0, 0, 0) : countsOf(d);

        // Placeholders — per band, count − named.
        int unnamedPeople = 0;
        for (HouseholdBand band : HouseholdBand.values()) {
            int open = Math.max(0, counts.of(band) - named.of(band));
            for (int n = 1; n <= open; n++) {
                unnamedPeople++;
                people.add(new Person(
                        "placeholder:" + band.name() + ":" + n,
                        PersonKind.PLACEHOLDER,
                        band,
                        null, null, null, null, false, null, null, null, null, null,
                        // Naming = POST manual-members (member-gated); dropping a
                        // placeholder lowers the count (admin-gated counts write).
                        new Capabilities(false, viewerAdmin, false, true)));
            }
        }

        List<Pet> petRows = new ArrayList<>();
        for (HouseholdPet p : pets) {
            PetSpecies sp = PetSpecies.of(p.getSpecies());
            named.add(sp);
            petRows.add(new Pet("pet:" + p.getId(), PetKind.NAMED, sp, p.getName(), p.getId(),
                    DtoImages.avatar(p.getPhotoUrl()),
                    // HouseholdPetService writes are admin-gated.
                    new Capabilities(viewerAdmin, viewerAdmin, false, false)));
        }
        int unnamedPets = 0;
        for (PetSpecies sp : PetSpecies.values()) {
            int open = Math.max(0, counts.of(sp) - named.of(sp));
            for (int n = 1; n <= open; n++) {
                unnamedPets++;
                petRows.add(new Pet("placeholder:" + sp.name() + ":" + n, PetKind.PLACEHOLDER, sp,
                        null, null, null, new Capabilities(false, viewerAdmin, false, viewerAdmin)));
            }
        }

        int namedPeople = accounts.size() + manual.size();
        Summary summary = new Summary(
                people.size(),
                petRows.size(),
                people.size() + petRows.size(),
                namedPeople + pets.size(),
                unnamedPeople + unnamedPets,
                accounts.size(),
                manual.size(),
                pets.size());

        return new HouseholdCompositionDto(
                HouseholdCompositionDto.VERSION,
                hid,
                new HouseholdCompositionDto.Viewer(
                        viewerRole == GroupRole.NONE ? "NONE" : viewerRole.name(), viewerAdmin),
                d != null,
                counts,
                named.asCounts(),
                people,
                petRows,
                summary);
    }

    /** Named totals per band/species — the floor a counts write must respect. */
    @Transactional(readOnly = true)
    public Counts minimum(String householdId) {
        Group g = household(householdId);
        if (g == null) return new Counts(0, 0, 0, 0, 0, 0, 0);
        return tally(g).asCounts();
    }

    // ── write-time count rules ──────────────────────────────────────────

    /**
     * Raise every band/species of the household's demographic to at least its
     * named total. With {@code createIfMissing} and no row, creates one seeded
     * at the named totals, owned by {@code actorEmail}. Returns true when the
     * row changed. No-op for a non-household.
     */
    @Transactional
    public boolean raiseToNamed(String householdId, boolean createIfMissing, String actorEmail) {
        Group g = household(householdId);
        if (g == null) return false;
        Counts min = tally(g).asCounts();
        Demographic d = demographicRepo.findFirstByHouseholdIdOrderByIdDesc(householdId).orElse(null);
        if (d == null) {
            if (!createIfMissing) return false;
            d = new Demographic();
            d.setHouseholdId(householdId);
            d.setOwnerEmail(lower(actorEmail) != null ? lower(actorEmail) : lower(g.getOwnerEmail()));
        }
        boolean changed = d.getId() == null;
        changed |= raise(d, min);
        if (!changed) return false;
        save(d);
        return true;
    }

    /** A named person in {@code band} was deleted: that band's count drops by one, never below named. */
    @Transactional
    public void lowerBand(String householdId, HouseholdBand band) {
        if (band == null) return;
        Demographic d = demographicRepo.findFirstByHouseholdIdOrderByIdDesc(householdId).orElse(null);
        Group g = household(householdId);
        if (d == null || g == null) return;
        int floor = tally(g).of(band);
        int next = Math.max(floor, countOf(d, band) - 1);
        if (next == countOf(d, band)) return;
        setCount(d, band, next);
        save(d);
    }

    /** A named pet of {@code species} was deleted: that column drops by one, never below named. */
    @Transactional
    public void lowerSpecies(String householdId, PetSpecies species) {
        if (species == null) return;
        Demographic d = demographicRepo.findFirstByHouseholdIdOrderByIdDesc(householdId).orElse(null);
        Group g = household(householdId);
        if (d == null || g == null) return;
        int floor = tally(g).of(species);
        int next = Math.max(floor, countOf(d, species) - 1);
        if (next == countOf(d, species)) return;
        setCount(d, species, next);
        save(d);
    }

    /**
     * 409 when {@code requested} counts fewer than are named in any band — the
     * server owns the drawer's floors. Negative values are 400.
     */
    @Transactional(readOnly = true)
    public void requireAtLeastNamed(String householdId, Counts requested) {
        if (householdId == null || requested == null) return;
        requireNonNegative(requested);
        Group g = household(householdId);
        if (g == null) return;
        Tally named = tally(g);
        for (HouseholdBand b : HouseholdBand.values()) {
            if (requested.of(b) < named.of(b)) throw new CountsBelowNamedException(b.name(), named.of(b), requested.of(b));
        }
        for (PetSpecies s : PetSpecies.values()) {
            if (requested.of(s) < named.of(s)) throw new CountsBelowNamedException(s.name(), named.of(s), requested.of(s));
        }
    }

    /**
     * Admin counts write ({@code PUT /api/households/{id}/composition/counts}).
     * Null fields are unchanged. Creates the row when missing. Returns the
     * fresh composition.
     */
    @Transactional
    public HouseholdCompositionDto setCounts(String householdId, CountsRequest body, String caller) {
        Group g = household(householdId);
        if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found");
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "counts required");
        Demographic d = demographicRepo.findFirstByHouseholdIdOrderByIdDesc(householdId).orElse(null);
        Counts current = d == null ? new Counts(0, 0, 0, 0, 0, 0, 0) : countsOf(d);
        Counts next = new Counts(
                pick(body.adults(), current.adults()),
                pick(body.teens(), current.teens()),
                pick(body.kids(), current.kids()),
                pick(body.infants(), current.infants()),
                pick(body.dogs(), current.dogs()),
                pick(body.cats(), current.cats()),
                pick(body.otherPets(), current.otherPets()));
        requireAtLeastNamed(householdId, next);
        if (d == null) {
            d = new Demographic();
            d.setHouseholdId(householdId);
            d.setOwnerEmail(lower(caller));
        }
        if (d.getId() == null || !next.equals(current)) {
            apply(d, next);
            save(d);
        }
        return compose(g, d, caller);
    }

    public record CountsRequest(Integer adults, Integer teens, Integer kids, Integer infants,
                                Integer dogs, Integer cats, Integer otherPets) {}

    /** 409 with the band, its floor and what was asked — the drawer can say exactly why. */
    public static class CountsBelowNamedException extends ResponseStatusException {
        private final String band;
        private final int minimum;
        private final int requested;

        public CountsBelowNamedException(String band, int minimum, int requested) {
            super(HttpStatus.CONFLICT, messageFor(band, minimum, requested));
            this.band = band;
            this.minimum = minimum;
            this.requested = requested;
        }

        public String band() { return band; }
        public int minimum() { return minimum; }
        public int requested() { return requested; }

        private static String messageFor(String band, int minimum, int requested) {
            String noun = switch (band) {
                case "ADULT" -> minimum == 1 ? "adult" : "adults";
                case "TEEN" -> minimum == 1 ? "teen" : "teens";
                case "KID" -> minimum == 1 ? "kid" : "kids";
                case "INFANT" -> minimum == 1 ? "infant" : "infants";
                case "DOG" -> minimum == 1 ? "dog" : "dogs";
                case "CAT" -> minimum == 1 ? "cat" : "cats";
                default -> minimum == 1 ? "other pet" : "other pets";
            };
            return "Your household has " + minimum + " named " + noun
                    + ", so the plan can't count fewer than " + minimum
                    + ". Remove someone by name first.";
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    /** Per-band / per-species named totals. */
    static final class Tally {
        private final EnumMap<HouseholdBand, Integer> people = new EnumMap<>(HouseholdBand.class);
        private final EnumMap<PetSpecies, Integer> pets = new EnumMap<>(PetSpecies.class);

        void add(HouseholdBand b) { people.merge(b, 1, Integer::sum); }
        void add(PetSpecies s) { pets.merge(s, 1, Integer::sum); }
        int of(HouseholdBand b) { return people.getOrDefault(b, 0); }
        int of(PetSpecies s) { return pets.getOrDefault(s, 0); }

        Counts asCounts() {
            return new Counts(of(HouseholdBand.ADULT), of(HouseholdBand.TEEN), of(HouseholdBand.KID),
                    of(HouseholdBand.INFANT), of(PetSpecies.DOG), of(PetSpecies.CAT), of(PetSpecies.OTHER));
        }
    }

    Tally tally(Group g) {
        Tally t = new Tally();
        String hid = g.getGroupId();
        Map<String, HouseholdBand> bands = accountBands(hid);
        for (String email : accountEmails(g)) t.add(bands.getOrDefault(email, HouseholdBand.ADULT));
        for (HouseholdManualMember m : manualRepo.findByHouseholdIdOrderByCreatedAtAsc(hid)) t.add(m.effectiveBand());
        for (HouseholdPet p : petRepo.findByHouseholdIdOrderByCreatedAtAsc(hid)) t.add(PetSpecies.of(p.getSpecies()));
        return t;
    }

    private Group household(String householdId) {
        if (householdId == null || householdId.isBlank()) return null;
        return groupRepo.findByGroupId(householdId)
                .filter(g -> HOUSEHOLD.equalsIgnoreCase(g.getGroupType()))
                .orElse(null);
    }

    /** Distinct, lower-cased, trimmed member emails in roster order. */
    static List<String> accountEmails(Group g) {
        Set<String> out = new LinkedHashSet<>();
        if (g.getMemberEmails() != null) {
            for (String e : g.getMemberEmails()) {
                String l = lower(e);
                if (l != null) out.add(l);
            }
        }
        return new ArrayList<>(out);
    }

    private Map<String, HouseholdBand> accountBands(String hid) {
        Map<String, HouseholdBand> out = new HashMap<>();
        for (HouseholdMemberBand b : bandRepo.findByHouseholdId(hid)) {
            if (b.getUserEmail() != null && b.getBand() != null) out.put(lower(b.getUserEmail()), b.getBand());
        }
        return out;
    }

    private Map<String, UserInfo> profiles(List<String> emails) {
        Map<String, UserInfo> out = new HashMap<>();
        if (emails.isEmpty()) return out;
        List<UserInfo> found = userInfoRepo.findByUserEmailIn(emails);
        if (found != null) {
            for (UserInfo u : found) {
                String l = lower(u.getUserEmail());
                if (l != null) out.putIfAbsent(l, u);
            }
        }
        // Profiles stored with mixed case miss the IN; fall back one by one.
        for (String e : emails) {
            if (!out.containsKey(e)) userInfoRepo.findByUserEmailIgnoreCase(e).ifPresent(u -> out.put(e, u));
        }
        return out;
    }

    private static String displayName(UserInfo u) {
        if (u == null) return null;
        String first = u.getUserFirstName() == null ? "" : u.getUserFirstName().trim();
        String last = u.getUserLastName() == null ? "" : u.getUserLastName().trim();
        String n = (first + " " + last).trim();
        return n.isEmpty() ? null : n;
    }

    static Counts countsOf(Demographic d) {
        return new Counts(d.getAdults(), d.getTeens(), d.getKids(), d.getInfants(),
                d.getDogs(), d.getCats(), d.getPets());
    }

    private static boolean raise(Demographic d, Counts min) {
        boolean changed = false;
        for (HouseholdBand b : HouseholdBand.values()) {
            if (countOf(d, b) < min.of(b)) { setCount(d, b, min.of(b)); changed = true; }
        }
        for (PetSpecies s : PetSpecies.values()) {
            if (countOf(d, s) < min.of(s)) { setCount(d, s, min.of(s)); changed = true; }
        }
        return changed;
    }

    private static void apply(Demographic d, Counts c) {
        d.setAdults(c.adults());
        d.setTeens(c.teens());
        d.setKids(c.kids());
        d.setInfants(c.infants());
        d.setDogs(c.dogs());
        d.setCats(c.cats());
        d.setPets(c.otherPets());
    }

    static int countOf(Demographic d, HouseholdBand b) {
        return switch (b) {
            case ADULT -> d.getAdults();
            case TEEN -> d.getTeens();
            case KID -> d.getKids();
            case INFANT -> d.getInfants();
        };
    }

    static int countOf(Demographic d, PetSpecies s) {
        return switch (s) {
            case DOG -> d.getDogs();
            case CAT -> d.getCats();
            case OTHER -> d.getPets();
        };
    }

    static void setCount(Demographic d, HouseholdBand b, int v) {
        switch (b) {
            case ADULT -> d.setAdults(v);
            case TEEN -> d.setTeens(v);
            case KID -> d.setKids(v);
            case INFANT -> d.setInfants(v);
        }
    }

    static void setCount(Demographic d, PetSpecies s, int v) {
        switch (s) {
            case DOG -> d.setDogs(v);
            case CAT -> d.setCats(v);
            case OTHER -> d.setPets(v);
        }
    }

    private static int pick(Integer requested, int current) {
        return requested == null ? current : requested;
    }

    private static void requireNonNegative(Counts c) {
        if (c.adults() < 0 || c.teens() < 0 || c.kids() < 0 || c.infants() < 0
                || c.dogs() < 0 || c.cats() < 0 || c.otherPets() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Counts can't be negative");
        }
    }

    /** Save and push the new head count to the household's other devices after commit. */
    private void save(Demographic d) {
        Demographic saved = demographicRepo.save(d);
        final String hid = saved.getHouseholdId();
        if (hid == null || hid.isBlank()) return;
        final Map<String, Object> frame = Map.of("type", "demographic", "demographic", DemographicDto.from(saved));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { ws.sendHouseholdDemographic(hid, frame); }
            });
        } else {
            ws.sendHouseholdDemographic(hid, frame);
        }
    }

    static String lower(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }
}
