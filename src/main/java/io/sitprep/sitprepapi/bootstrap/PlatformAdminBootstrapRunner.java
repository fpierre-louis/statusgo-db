package io.sitprep.sitprepapi.bootstrap;

import io.sitprep.sitprepapi.constant.PlatformRole;
import io.sitprep.sitprepapi.domain.PlatformAdmin;
import io.sitprep.sitprepapi.repo.PlatformAdminRepo;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Component
public class PlatformAdminBootstrapRunner implements ApplicationRunner {

    private final PlatformAdminRepo repo;
    private final List<String> bootstrapEmails;

    public PlatformAdminBootstrapRunner(
            PlatformAdminRepo repo,
            @Value("${app.admin.bootstrap-super-admins:}") String bootstrapSuperAdmins) {
        this.repo = repo;
        this.bootstrapEmails = Arrays.stream((bootstrapSuperAdmins == null ? "" : bootstrapSuperAdmins).split(","))
                .map(PlatformAccessService::normalizeEmail)
                .filter(email -> email != null && !email.isBlank())
                .distinct()
                .toList();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        for (String email : bootstrapEmails) {
            PlatformAdmin admin = repo.findByEmailIgnoreCase(email).orElseGet(PlatformAdmin::new);
            admin.setEmail(email);
            admin.setRole(PlatformRole.SUPER_ADMIN);
            admin.setActive(true);
            if (admin.getGrantedBy() == null || admin.getGrantedBy().isBlank()) {
                admin.setGrantedBy("bootstrap");
            }
            if (admin.getGrantedAt() == null) admin.setGrantedAt(now);
            admin.setUpdatedAt(now);
            repo.save(admin);
        }
    }
}
