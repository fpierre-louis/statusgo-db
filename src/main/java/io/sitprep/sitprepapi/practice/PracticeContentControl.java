package io.sitprep.sitprepapi.practice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The server-side kill switch for one Practice content key (V102).
 *
 * <p>A row with {@code disabledAt} set takes every version of the key out of
 * the startable catalog AND stops runs already in progress, at once, without a
 * deploy or an App Store release. Re-enabling clears {@code disabledAt} and
 * keeps the row, so the last reason and actor stay visible to operators.</p>
 */
@Entity
@Getter
@Setter
@Table(name = "practice_content_control")
public class PracticeContentControl {

    @Id
    @Column(name = "content_key", nullable = false, length = 96)
    private String contentKey;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_reason", length = 500)
    private String disabledReason;

    @Column(name = "updated_by", length = 320)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isDisabled() {
        return disabledAt != null;
    }
}
