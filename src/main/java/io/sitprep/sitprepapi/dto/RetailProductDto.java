package io.sitprep.sitprepapi.dto;

public record RetailProductDto(
        String item,
        String unit,
        String description,
        String buyLink,
        String imageUrl,
        String amazonAsin,
        String amazonSearchUrl,
        String walmartItemId,
        String walmartSearchUrl,
        double packageSize,
        String packageUnit,
        String packageLabel,
        String lastVerifiedAt
) {}
