package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GoBag;
import io.sitprep.sitprepapi.domain.GoBagItem;
import io.sitprep.sitprepapi.dto.GoBagDtos.AddItemRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.CreateGoBagRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagItemDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagSummaryDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.ItemPatchRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.RecommendationDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.RecommendedItemDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.SeedItemsRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.UpdateGoBagRequest;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.GoBagSnapshotDto;
import io.sitprep.sitprepapi.repo.GoBagItemRepo;
import io.sitprep.sitprepapi.repo.GoBagRepo;
import io.sitprep.sitprepapi.util.GeoUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD + rollups for {@link GoBag} / {@link GoBagItem}. The resource layer
 * owns auth (household member reads, owner/admin writes via
 * {@link HouseholdAccessService}); this service assumes the caller is
 * already verified.
 *
 * <p>Seeding is an ADDITIVE merge keyed on {@code itemKey} — packed
 * progress is user labor and is never clobbered by a wizard re-run.
 * Expiry dates are only stamped from explicit wizard/user input
 * ({@code expirySeeds}); the template's {@code defaultShelfLifeDays} is
 * advisory (date-picker prefill + the "Mark refreshed" bump) so users
 * aren't reminded about items they never dated.</p>
 */
@Service
public class GoBagService {

    private static final Set<String> KINDS = Set.of("home", "vehicle", "work", "other");
    private static final Set<String> STRATEGIES = Set.of("premade", "diy", "hybrid");
    /** "Mark refreshed" fallback when a custom item has no template default. */
    private static final int FALLBACK_SHELF_LIFE_DAYS = 365;

    private final GoBagRepo bagRepo;
    private final GoBagItemRepo itemRepo;
    private final GoBagRecommendationService recommendations;

    public GoBagService(GoBagRepo bagRepo,
                        GoBagItemRepo itemRepo,
                        GoBagRecommendationService recommendations) {
        this.bagRepo = bagRepo;
        this.itemRepo = itemRepo;
        this.recommendations = recommendations;
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<GoBagDto> listForHousehold(String householdId) {
        List<GoBag> bags = bagRepo.findByHouseholdIdOrderByCreatedAtAsc(householdId);
        if (bags.isEmpty()) return List.of();
        Map<String, List<GoBagItem>> itemsByBag = itemRepo
                .findByBagIdIn(bags.stream().map(GoBag::getId).toList())
                .stream().collect(Collectors.groupingBy(GoBagItem::getBagId));
        return bags.stream()
                .map(b -> toDto(b, itemsByBag.getOrDefault(b.getId(), List.of())))
                .toList();
    }

    /** Lossy summaries for {@code HouseholdPlanDto} / plan-tab surfaces. */
    @Transactional(readOnly = true)
    public List<GoBagSummaryDto> summariesForHousehold(String householdId) {
        return listForHousehold(householdId).stream()
                .map(d -> new GoBagSummaryDto(d.id(), d.name(), d.kind(), d.storageLabel(),
                        d.itemsPacked(), d.itemsTotal(), d.expiringSoonCount()))
                .toList();
    }

    /**
     * "Grab before you go" snapshots for the deployed-plan dashboard —
     * household-audience ONLY (the resource/service caller enforces the
     * audience branch; never include these in a recipient projection).
     */
    @Transactional(readOnly = true)
    public List<GoBagSnapshotDto> snapshotsForHousehold(String householdId) {
        if (householdId == null || householdId.isBlank()) return List.of();
        LocalDate today = LocalDate.now();
        List<GoBag> bags = bagRepo.findByHouseholdIdOrderByCreatedAtAsc(householdId);
        if (bags.isEmpty()) return List.of();
        Map<String, List<GoBagItem>> itemsByBag = itemRepo
                .findByBagIdIn(bags.stream().map(GoBag::getId).toList())
                .stream().collect(Collectors.groupingBy(GoBagItem::getBagId));
        return bags.stream().map(b -> {
            List<GoBagItem> items = itemsByBag.getOrDefault(b.getId(), List.of());
            int packed = (int) items.stream().filter(GoBagService::isPacked).count();
            int expired = (int) items.stream()
                    .filter(i -> i.getExpiresOn() != null && i.getExpiresOn().isBefore(today))
                    .count();
            List<String> topUnpackedP0 = items.stream()
                    .filter(i -> i.getPriority() == 0 && !isPacked(i))
                    .sorted(Comparator.comparing(GoBagItem::getCreatedAt))
                    .limit(3)
                    .map(GoBagItem::getLabel)
                    .toList();
            return new GoBagSnapshotDto(b.getName(), b.getStorageLabel(), b.getKind(),
                    packed, items.size(), expired, topUnpackedP0);
        }).toList();
    }

    @Transactional(readOnly = true)
    public GoBag requireBag(String bagId) {
        return bagRepo.findById(bagId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Go bag not found: " + bagId));
    }

    // ---------------------------------------------------------------------
    // Bag writes
    // ---------------------------------------------------------------------

    @Transactional
    public GoBagDto createBag(String ownerEmail, String householdId, CreateGoBagRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bag name is required");
        }
        GeoUtil.requireValidLatLng(req.lat(), req.lng());

        GoBag bag = new GoBag();
        bag.setId(req.id() != null && !req.id().isBlank()
                ? req.id().trim() : UUID.randomUUID().toString());
        bag.setHouseholdId(householdId);
        bag.setOwnerEmail(ownerEmail);
        bag.setName(req.name().trim());
        bag.setKind(normalizedKind(req.kind()));
        bag.setStorageLabel(trimToNull(req.storageLabel()));
        bag.setLat(req.lat());
        bag.setLng(req.lng());
        bag.setStrategy(normalizedStrategy(req.strategy()));
        bag.setPremadeKitLabel(trimToNull(req.premadeKitLabel()));
        // Use the SAVE RETURN VALUE, not the passed-in instance: these entities
        // have a client-assigned String @Id, so Spring Data treats them as
        // non-new and save() does a merge(), firing @PrePersist (createdAt/
        // updatedAt) on a managed COPY. The original stays detached with null
        // timestamps — reading them downstream (toDto sorts items by createdAt)
        // NPE'd and 500'd the POST /go-bags request.
        GoBag saved = bagRepo.save(bag);

        List<GoBagItem> items = List.of();
        if (req.seedItems()) {
            items = seedMissingItems(saved,
                    recommendations.recommendForHousehold(householdId,
                            req.seniorNeeds(), req.medicalNeeds()),
                    req.expirySeeds(), List.of());
        }
        return toDto(saved, items);
    }

    @Transactional
    public GoBagDto updateBag(String bagId, UpdateGoBagRequest req) {
        GoBag bag = requireBag(bagId);
        if (req.name() != null && !req.name().isBlank()) bag.setName(req.name().trim());
        if (req.kind() != null) bag.setKind(normalizedKind(req.kind()));
        if (req.storageLabel() != null) bag.setStorageLabel(trimToNull(req.storageLabel()));
        if (req.lat() != null || req.lng() != null) {
            GeoUtil.requireValidLatLng(req.lat(), req.lng());
            bag.setLat(req.lat());
            bag.setLng(req.lng());
        }
        if (req.strategy() != null) bag.setStrategy(normalizedStrategy(req.strategy()));
        if (req.premadeKitLabel() != null) bag.setPremadeKitLabel(trimToNull(req.premadeKitLabel()));
        bagRepo.save(bag);
        return toDto(bag, itemRepo.findByBagIdOrderByPriorityAscCreatedAtAsc(bagId));
    }

    @Transactional
    public void deleteBag(String bagId) {
        GoBag bag = requireBag(bagId);
        itemRepo.deleteByBagId(bag.getId());
        bagRepo.delete(bag);
    }

    // ---------------------------------------------------------------------
    // Item writes
    // ---------------------------------------------------------------------

    /** Wizard re-run — additive merge; existing itemKeys are left alone. */
    @Transactional
    public GoBagDto seedItems(String bagId, SeedItemsRequest req) {
        GoBag bag = requireBag(bagId);
        List<GoBagItem> existing = itemRepo.findByBagIdOrderByPriorityAscCreatedAtAsc(bagId);
        RecommendationDto rec = recommendations.recommendForHousehold(
                bag.getHouseholdId(),
                req != null && req.seniorNeeds(),
                req != null && req.medicalNeeds());
        seedMissingItems(bag, rec, req == null ? null : req.expirySeeds(), existing);
        return toDto(bag, itemRepo.findByBagIdOrderByPriorityAscCreatedAtAsc(bagId));
    }

    @Transactional
    public GoBagItemDto addItem(String bagId, AddItemRequest req) {
        GoBag bag = requireBag(bagId);
        if (req == null || req.label() == null || req.label().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item label is required");
        }
        GoBagItem item = new GoBagItem();
        String id = req.id() != null && !req.id().isBlank()
                ? req.id().trim() : UUID.randomUUID().toString();
        item.setId(id);
        item.setBagId(bag.getId());
        item.setItemKey("custom:" + id);
        item.setLabel(req.label().trim());
        item.setCategory(req.category() != null && !req.category().isBlank()
                ? req.category().trim() : "tools");
        item.setPriority(1);
        item.setQtyRecommended(req.qtyRecommended() != null
                ? Math.max(1, req.qtyRecommended()) : 1);
        item.setQtyPacked(0);
        item.setUnit(trimToNull(req.unit()));
        item.setExpiresOn(parseDate(req.expiresOn()));
        item.setNotes(trimToNull(req.notes()));
        // Return the managed instance (client-assigned @Id → save() merges,
        // firing @PrePersist on the copy, not this detached original).
        return toItemDto(itemRepo.save(item));
    }

    @Transactional
    public GoBagItemDto patchItem(String bagId, String itemId, ItemPatchRequest req) {
        GoBagItem item = requireItem(bagId, itemId);
        if (req.qtyPacked() != null) {
            item.setQtyPacked(Math.max(0, req.qtyPacked()));
        }
        if (Boolean.TRUE.equals(req.clearExpiry())) {
            item.setExpiresOn(null);
            item.setReminderSentAt(null);
        } else if (req.expiresOn() != null) {
            item.setExpiresOn(parseDate(req.expiresOn()));
            // A fresh date re-arms the fire-once reminder latch.
            item.setReminderSentAt(null);
        }
        if (req.notes() != null) item.setNotes(trimToNull(req.notes()));
        itemRepo.save(item);
        return toItemDto(item);
    }

    /**
     * "Mark refreshed" — the one-tap rotation: bumps {@code expiresOn} to
     * today + the item's template shelf life (fallback
     * {@value #FALLBACK_SHELF_LIFE_DAYS}d) and re-arms the reminder latch.
     */
    @Transactional
    public GoBagItemDto markRefreshed(String bagId, String itemId) {
        GoBagItem item = requireItem(bagId, itemId);
        int shelfDays = recommendations.templateByKey(item.getItemKey())
                .map(t -> t.defaultShelfLifeDays() != null
                        ? t.defaultShelfLifeDays() : FALLBACK_SHELF_LIFE_DAYS)
                .orElse(FALLBACK_SHELF_LIFE_DAYS);
        item.setExpiresOn(LocalDate.now().plusDays(shelfDays));
        item.setReminderSentAt(null);
        itemRepo.save(item);
        return toItemDto(item);
    }

    @Transactional
    public void deleteItem(String bagId, String itemId) {
        itemRepo.delete(requireItem(bagId, itemId));
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private List<GoBagItem> seedMissingItems(GoBag bag, RecommendationDto rec,
                                             Map<String, String> expirySeeds,
                                             List<GoBagItem> existing) {
        Set<String> existingKeys = existing.stream()
                .map(GoBagItem::getItemKey).collect(Collectors.toSet());
        List<GoBagItem> created = new ArrayList<>();
        for (RecommendedItemDto r : rec.items()) {
            if (existingKeys.contains(r.itemKey())) continue;
            GoBagItem item = new GoBagItem();
            item.setId(UUID.randomUUID().toString());
            item.setBagId(bag.getId());
            item.setItemKey(r.itemKey());
            item.setProductKey(r.productKey());
            item.setLabel(r.label());
            item.setCategory(r.category());
            item.setPriority(r.priority());
            item.setQtyRecommended(r.qtyRecommended());
            item.setQtyPacked(0);
            item.setUnit(r.unit());
            if (expirySeeds != null && expirySeeds.get(r.itemKey()) != null) {
                item.setExpiresOn(parseDate(expirySeeds.get(r.itemKey())));
            }
            created.add(item);
        }
        // saveAll RETURNS the managed copies (with @PrePersist timestamps);
        // the `created` originals stay detached with null createdAt. Return
        // the saved instances so toDto's createdAt sort has real values.
        List<GoBagItem> savedCreated = created.isEmpty()
                ? List.of() : itemRepo.saveAll(created);
        List<GoBagItem> all = new ArrayList<>(existing);
        all.addAll(savedCreated);
        return all;
    }

    private GoBagItem requireItem(String bagId, String itemId) {
        GoBagItem item = itemRepo.findById(itemId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Go bag item not found: " + itemId));
        if (!Objects.equals(item.getBagId(), bagId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Item does not belong to bag " + bagId);
        }
        return item;
    }

    private GoBagDto toDto(GoBag b, List<GoBagItem> rawItems) {
        LocalDate today = LocalDate.now();
        LocalDate soonHorizon = today.plusDays(GoBagRecommendationService.EXPIRY_WARNING_DAYS);
        List<GoBagItem> items = rawItems.stream()
                .sorted(Comparator.comparingInt(GoBagItem::getPriority)
                        .thenComparing(GoBagItem::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        int packed = (int) items.stream().filter(GoBagService::isPacked).count();
        int p0Total = (int) items.stream().filter(i -> i.getPriority() == 0).count();
        int p0Packed = (int) items.stream()
                .filter(i -> i.getPriority() == 0 && isPacked(i)).count();
        LocalDate nextExpiry = items.stream()
                .map(GoBagItem::getExpiresOn).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        int expiringSoon = (int) items.stream()
                .filter(i -> i.getExpiresOn() != null && !i.getExpiresOn().isAfter(soonHorizon))
                .count();

        return new GoBagDto(
                b.getId(), b.getHouseholdId(), b.getName(), b.getKind(),
                b.getStorageLabel(), b.getLat(), b.getLng(), b.getStrategy(),
                b.getPremadeKitLabel(),
                items.stream().map(this::toItemDto).toList(),
                packed, items.size(), p0Packed, p0Total,
                nextExpiry, expiringSoon, b.getUpdatedAt());
    }

    private GoBagItemDto toItemDto(GoBagItem i) {
        return new GoBagItemDto(i.getId(), i.getItemKey(), i.getProductKey(), i.getLabel(),
                i.getCategory(), i.getPriority(), i.getQtyRecommended(), i.getQtyPacked(),
                i.getUnit(), i.getExpiresOn(), i.getNotes(), isPacked(i));
    }

    private static boolean isPacked(GoBagItem i) {
        return i.getQtyPacked() >= Math.max(1, i.getQtyRecommended());
    }

    private static String normalizedKind(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase();
        if (!KINDS.contains(k)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind must be one of " + KINDS);
        }
        return k;
    }

    private static String normalizedStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) return null;
        String s = strategy.trim().toLowerCase();
        if (!STRATEGIES.contains(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "strategy must be one of " + STRATEGIES);
        }
        return s;
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid date (expected YYYY-MM-DD): " + iso);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
