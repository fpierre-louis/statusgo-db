package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AdminAuditLog;
import io.sitprep.sitprepapi.dto.AdminAuditLogDto;
import io.sitprep.sitprepapi.repo.AdminAuditLogRepo;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AdminAuditLogService {

    private final AdminAuditLogRepo repo;

    public AdminAuditLogService(AdminAuditLogRepo repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<AdminAuditLogDto> list(String actor,
                                       String action,
                                       String date,
                                       boolean canViewAll,
                                       String selfEmail) {
        String actorFilter = canViewAll ? normalizeFilter(actor, 320) : normalizeActor(selfEmail);
        String actionFilter = normalizeAction(action);
        DateWindow dateWindow = parseDate(date);

        var page = repo.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorFilter != null) {
                predicates.add(cb.equal(cb.lower(root.get("actorEmail")), actorFilter.toLowerCase(Locale.ROOT)));
            }
            if (actionFilter != null) {
                predicates.add(cb.equal(root.get("action"), actionFilter));
            }
            if (dateWindow != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("at"), dateWindow.from()));
                predicates.add(cb.lessThan(root.get("at"), dateWindow.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "at")));
        return page.stream().map(AdminAuditLogDto::from).toList();
    }

    @Transactional
    public void record(String actorEmail,
                       String action,
                       String targetType,
                       String targetId,
                       String summary) {
        AdminAuditLog row = new AdminAuditLog();
        row.setActorEmail(normalizeActor(actorEmail));
        row.setAction(trim(action, 48));
        row.setTargetType(trim(targetType, 32));
        row.setTargetId(trim(targetId, 64));
        row.setSummary(trim(summary, 500));
        repo.save(row);
    }

    private static String normalizeActor(String actorEmail) {
        String value = trim(actorEmail, 320);
        if (value == null) return "unknown";
        if ("admin-token".equalsIgnoreCase(value)) return "admin-token";
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeFilter(String raw, int max) {
        String value = trim(raw, max);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeAction(String raw) {
        String value = trim(raw, 48);
        return value == null ? null : value.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static DateWindow parseDate(String raw) {
        String value = trim(raw, 24);
        if (value == null) return null;
        try {
            LocalDate date = LocalDate.parse(value);
            Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            return new DateWindow(from, date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be YYYY-MM-DD");
        }
    }

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record DateWindow(Instant from, Instant to) {}
}
