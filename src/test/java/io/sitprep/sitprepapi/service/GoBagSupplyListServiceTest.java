package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.GoBagDtos.CreateGoBagRequest;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagShoppingListDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagSummaryDto;
import io.sitprep.sitprepapi.dto.GoBagDtos.ShoppingItemDto;
import io.sitprep.sitprepapi.repo.GoBagItemRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supply-list engine guards: remainder math (partial packs), the
 * toBuy/toReplace bucketing with expired-first ordering, catalog link joins
 * (final tagged URLs), the "gather at home" null-productKey path, and
 * commerce suppression (links nulled + reason flagged). Also pins
 * {@code completionPct} on GoBagDto/GoBagSummaryDto.
 */
@SpringBootTest
@ActiveProfiles("test")
class GoBagSupplyListServiceTest {

    @Autowired GoBagService goBagService;
    @Autowired GoBagSupplyListService supplyListService;
    @Autowired GoBagProductCatalog catalog;
    @Autowired GoBagItemRepo itemRepo;
    @Autowired GroupRepo groupRepo;

    private GoBagDto seedBag(String householdId) {
        // Seed from the real recommendation engine so the list mirrors prod
        // (needs a Demographic row → create via the repo-less path: the
        // recommendation service falls back? No — create WITHOUT seeding and
        // add items by hand for exact control).
        return goBagService.createBag("owner@x.com", householdId,
                new CreateGoBagRequest(UUID.randomUUID().toString(), "Home go bag",
                        "home", "Hallway closet", null, null, null, null,
                        false, false, false, Map.of()));
    }

    private void addItem(String bagId, String label, String productKey, int qtyRec,
                         int qtyPacked, LocalDate expiresOn) {
        var item = new io.sitprep.sitprepapi.domain.GoBagItem();
        item.setId(UUID.randomUUID().toString());
        item.setBagId(bagId);
        item.setItemKey("k-" + label.toLowerCase().replace(' ', '-'));
        item.setProductKey(productKey);
        item.setLabel(label);
        item.setCategory("water");
        item.setPriority(1);
        item.setQtyRecommended(qtyRec);
        item.setQtyPacked(qtyPacked);
        item.setExpiresOn(expiresOn);
        itemRepo.save(item);
    }

    @Test
    void remainderMath_bucketing_andCatalogJoin() {
        String hh = "hh-supply-" + UUID.randomUUID();
        GoBagDto bag = seedBag(hh);
        LocalDate today = LocalDate.now();

        addItem(bag.id(), "Water pouches", "gobag-water-pouches", 5, 3, null); // partial → need 2
        addItem(bag.id(), "Ration bars", "gobag-ration-bars", 2, 2,
                today.plusDays(10));                                            // packed, expiring soon
        addItem(bag.id(), "Old meds", null, 1, 1, today.minusDays(3));          // packed, EXPIRED
        addItem(bag.id(), "Copies of documents", null, 1, 0, null);             // missing, no product
        addItem(bag.id(), "Flashlight", "gobag-headlamp", 1, 1, null);          // fully packed, fresh → absent

        GoBagShoppingListDto list = supplyListService.supplyList(bag.id());

        assertThat(list.commerceSuppressed()).isFalse();
        assertThat(list.suppressedReason()).isNull();

        // toBuy: partial remainder + the unpacked document row; packed rows absent.
        assertThat(list.toBuy()).extracting(ShoppingItemDto::label)
                .containsExactlyInAnyOrder("Water pouches", "Copies of documents");
        ShoppingItemDto water = list.toBuy().stream()
                .filter(r -> r.label().equals("Water pouches")).findFirst().orElseThrow();
        assertThat(water.qtyRemaining()).isEqualTo(2); // 5 − 3
        assertThat(water.amazonUrl())
                .contains("amazon.com/s?k=").contains("tag=sitprep0f-20");
        assertThat(water.walmartUrl()).startsWith("https://www.walmart.com/search?q=")
                .doesNotContain("tag=");

        ShoppingItemDto docs = list.toBuy().stream()
                .filter(r -> r.label().equals("Copies of documents")).findFirst().orElseThrow();
        assertThat(docs.productKey()).isNull();     // gather at home —
        assertThat(docs.amazonUrl()).isNull();      // no links
        assertThat(docs.walmartUrl()).isNull();

        // toReplace: expired first, then expiring-soon; expired flag set.
        assertThat(list.toReplace()).extracting(ShoppingItemDto::label)
                .containsExactly("Old meds", "Ration bars");
        assertThat(list.toReplace().get(0).expired()).isTrue();
        assertThat(list.toReplace().get(1).expired()).isFalse();
        // Verified-ASIN product gets a dp/ link.
        assertThat(list.toReplace().get(1).amazonUrl())
                .isEqualTo("https://www.amazon.com/dp/B001CSAHW0?tag=sitprep0f-20");

        // counts: 2 toBuy, 2 toReplace, 2 linkable (water + ration bars).
        assertThat(list.counts().toBuy()).isEqualTo(2);
        assertThat(list.counts().toReplace()).isEqualTo(2);
        assertThat(list.counts().linkable()).isEqualTo(2);

        // completionPct: 3 of 5 packed (ration bars + old meds + flashlight) = 60.
        GoBagSummaryDto summary = goBagService.summariesForHousehold(hh).get(0);
        assertThat(summary.itemsPacked()).isEqualTo(3);
        assertThat(summary.itemsTotal()).isEqualTo(5);
        assertThat(summary.completionPct()).isEqualTo(60);
    }

    @Test
    void suppression_householdCheckinActive_nullsLinksAndFlags() {
        String hh = "hh-suppress-" + UUID.randomUUID();
        Group household = new Group();
        household.setGroupId(hh);
        household.setOwnerEmail("owner@x.com");
        household.setAlert("Active");
        groupRepo.save(household);

        GoBagDto bag = seedBag(hh);
        addItem(bag.id(), "Water pouches", "gobag-water-pouches", 5, 0, null);

        GoBagShoppingListDto list = supplyListService.supplyList(bag.id());

        assertThat(list.commerceSuppressed()).isTrue();
        assertThat(list.suppressedReason()).isEqualTo("household_checkin");
        // The action guidance still ships — only commerce is stripped.
        assertThat(list.toBuy()).hasSize(1);
        assertThat(list.toBuy().get(0).amazonUrl()).isNull();
        assertThat(list.toBuy().get(0).walmartUrl()).isNull();
        // linkable counts catalog coverage independent of suppression.
        assertThat(list.counts().linkable()).isEqualTo(1);
    }

    @Test
    void suppression_noGroupRow_defaultsToCommerceOn() {
        assertThat(supplyListService.suppressionReason("hh-none-" + UUID.randomUUID()))
                .isNull();
    }

    @Test
    void catalog_tagAndAsinRules() {
        assertThat(catalog.all()).hasSize(15);
        // Verified ASIN → dp link, tagged.
        assertThat(catalog.byKey("gobag-emergency-bivvy").orElseThrow().amazonUrl())
                .isEqualTo("https://www.amazon.com/dp/B00TW2CXZM?tag=sitprep0f-20");
        // No ASIN → tagged search fallback, never a fabricated dp/.
        String search = catalog.byKey("gobag-water-filter").orElseThrow().amazonUrl();
        assertThat(search).contains("/s?k=").contains("&tag=sitprep0f-20")
                .doesNotContain("/dp/");
        assertThat(catalog.byKey("gobag-water-filter").orElseThrow().lastReviewedAt())
                .isEqualTo("2026-07-08");
        assertThat(catalog.byKey(null)).isEmpty();
    }
}
