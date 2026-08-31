package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdvancedReadinessCompletion {

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "completed_by", length = 320)
    private String completedBy;
}
