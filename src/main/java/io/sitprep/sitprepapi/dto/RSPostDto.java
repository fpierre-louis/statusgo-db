package io.sitprep.sitprepapi.dto;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RSPostDto {
    private UUID rsPostId;
    private String rsGroupId;
    private String createdByEmail;
    private Instant createdAt;
    private Instant updatedAt;
    private String content;
}