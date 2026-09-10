package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.ResourceListingDto;
import io.sitprep.sitprepapi.service.ResourceListingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceListingResourceTest {

    private ResourceListingService service;
    private ResourceListingResource resource;

    @BeforeEach
    void setUp() {
        service = mock(ResourceListingService.class);
        resource = new ResourceListingResource(service);
    }

    @Test
    void previewReturnsApprovedPublicShape() {
        ResourceListingDto dto = new ResourceListingDto(
                42L,
                "Cooling center",
                "Open during the heat advisory.",
                "cooling-center",
                39.7392,
                -104.9903,
                "123 Main St",
                "https://example.org/cooling",
                "COMMUNITY",
                null,
                Instant.parse("2026-09-10T12:00:00Z"));
        when(service.findPublicPreview(42L)).thenReturn(Optional.of(dto));

        ResponseEntity<ApiResponse<ResourceListingDto>> res = resource.preview(42L);

        assertEquals(200, res.getStatusCode().value());
        assertNotNull(res.getBody());
        assertEquals(dto, res.getBody().data());
        verify(service).findPublicPreview(42L);
    }

    @Test
    void preview404sWhenListingIsNotPublic() {
        when(service.findPublicPreview(99L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<ResourceListingDto>> res = resource.preview(99L);

        assertEquals(404, res.getStatusCode().value());
        verify(service).findPublicPreview(99L);
    }
}
