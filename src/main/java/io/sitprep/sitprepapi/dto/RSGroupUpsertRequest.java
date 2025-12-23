// src/main/java/io/sitprep/sitprepapi/dto/RSGroupUpsertRequest.java
package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.RSEventVisibility;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RSGroupUpsertRequest {

    private String name;
    private String sportType;
    private String description;

    // use wrappers so “not sent” != false
    private Boolean isPrivate;

    // policy (optional)
    private Boolean isDiscoverable;
    private Boolean allowPublicEvents;
    private RSEventVisibility defaultEventVisibility;
}