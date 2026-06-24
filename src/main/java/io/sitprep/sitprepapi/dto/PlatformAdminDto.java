package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.constant.PlatformRole;
import io.sitprep.sitprepapi.domain.PlatformAdmin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public record PlatformAdminDto(
        Long id,
        String email,
        String role,
        List<String> extraGrants,
        List<String> effectivePermissions,
        String grantedBy,
        Instant grantedAt,
        Instant updatedAt,
        boolean active
) {
    public static PlatformAdminDto from(PlatformAdmin admin) {
        PlatformRole role = admin.getRole() == null ? PlatformRole.NONE : admin.getRole();
        EnumSet<PlatformPermission> grants = EnumSet.noneOf(PlatformPermission.class);
        if (admin.getExtraGrants() != null) grants.addAll(admin.getExtraGrants());

        EnumSet<PlatformPermission> effective = EnumSet.noneOf(PlatformPermission.class);
        effective.addAll(role.defaults());
        effective.addAll(grants);

        return new PlatformAdminDto(
                admin.getId(),
                admin.getEmail(),
                role.name(),
                names(grants),
                names(effective),
                admin.getGrantedBy(),
                admin.getGrantedAt(),
                admin.getUpdatedAt(),
                admin.isActive()
        );
    }

    private static List<String> names(EnumSet<PlatformPermission> permissions) {
        List<String> names = new ArrayList<>();
        for (PlatformPermission permission : PlatformPermission.values()) {
            if (permissions.contains(permission)) names.add(permission.name());
        }
        return names;
    }
}
