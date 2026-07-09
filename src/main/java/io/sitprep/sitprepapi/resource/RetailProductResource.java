package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.RetailProductDto;
import io.sitprep.sitprepapi.service.RetailProductCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public read of the emergency-food product catalog. The catalog itself now
 * lives in {@link RetailProductCatalog} (shared with
 * {@code FoodPlanCalculatorService}) so package sizes have a single source of
 * truth — this resource is a thin exposure of it.
 */
@RestController
@RequestMapping("/api/retail/products")
public class RetailProductResource {

    private final RetailProductCatalog catalog;

    public RetailProductResource(RetailProductCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<List<RetailProductDto>> list() {
        return ResponseEntity.ok(catalog.all());
    }
}
