package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.constant.PlatformRole;

import java.util.Set;

public record SavePlatformAdminRequest(
        PlatformRole role,
        Set<PlatformPermission> extraGrants
) {}
