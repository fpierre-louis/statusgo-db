package io.sitprep.sitprepapi.web;

import io.sitprep.sitprepapi.domain.IdempotencyKey;
import io.sitprep.sitprepapi.repo.IdempotencyKeyRepo;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An in-memory {@link IdempotencyKeyRepo} with the same claim semantics as the
 * table: {@code reserve} is insert-if-absent on the primary key (atomic via
 * {@link ConcurrentHashMap#putIfAbsent}), {@code complete} fills only an
 * unanswered claim, {@code release} drops only an unanswered claim.
 */
public final class FakeIdempotencyRepo {

    private FakeIdempotencyRepo() {}

    public static IdempotencyKeyRepo create(Map<IdempotencyKey.PK, IdempotencyKey> store) {
        IdempotencyKeyRepo repo = mock(IdempotencyKeyRepo.class);
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(store.get(i.<IdempotencyKey.PK>getArgument(0))));
        when(repo.reserve(anyString(), anyString(), anyString(), any(Instant.class))).thenAnswer(i -> {
            IdempotencyKey row = new IdempotencyKey();
            row.setCallerEmail(i.getArgument(0));
            row.setEndpoint(i.getArgument(1));
            row.setKey(i.getArgument(2));
            row.setResponseStatusCode(0);
            row.setResponseBody("");
            row.setCreatedAt(i.getArgument(3));
            return store.putIfAbsent(pk(i.getArgument(0), i.getArgument(1), i.getArgument(2)), row) == null ? 1 : 0;
        });
        when(repo.complete(anyString(), anyString(), anyString(), anyInt(), anyString())).thenAnswer(i -> {
            IdempotencyKey row = store.get(pk(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
            if (row == null || row.getResponseStatusCode() != 0) return 0;
            row.setResponseStatusCode(i.getArgument(3));
            row.setResponseBody(i.getArgument(4));
            return 1;
        });
        when(repo.release(anyString(), anyString(), anyString())).thenAnswer(i -> {
            IdempotencyKey.PK pk = pk(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            IdempotencyKey row = store.get(pk);
            return row != null && row.getResponseStatusCode() == 0 && store.remove(pk, row) ? 1 : 0;
        });
        when(repo.deleteExpired(anyString(), anyString(), anyString(), any(Instant.class))).thenAnswer(i -> {
            IdempotencyKey.PK pk = pk(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            IdempotencyKey row = store.get(pk);
            return row != null && row.getCreatedAt().equals(i.getArgument(3)) && store.remove(pk, row) ? 1 : 0;
        });
        return repo;
    }

    public static IdempotencyKeyRepo create() {
        return create(new ConcurrentHashMap<>());
    }

    private static IdempotencyKey.PK pk(String caller, String endpoint, String key) {
        return new IdempotencyKey.PK(caller, endpoint, key);
    }
}
