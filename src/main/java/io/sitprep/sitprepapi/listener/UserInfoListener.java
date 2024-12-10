package io.sitprep.sitprepapi.listener;

import io.sitprep.sitprepapi.domain.UserInfo;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

public class UserInfoListener {

    @PrePersist
    public void prePersist(UserInfo userInfo) {
        userInfo.setUserStatusLastUpdated(Instant.now());
    }

    @PreUpdate
    public void preUpdate(UserInfo userInfo) {
        userInfo.setUserStatusLastUpdated(Instant.now());
    }
}
