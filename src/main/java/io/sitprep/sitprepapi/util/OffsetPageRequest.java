package io.sitprep.sitprepapi.util;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} addressed by row offset rather than page number, for
 * endpoints that take {@code offset} + {@code limit} (audit TK-08: the group
 * board). Spring's {@code PageRequest} only addresses page-aligned offsets;
 * this one passes any offset straight through to the query. Ordering comes
 * from the query's own {@code ORDER BY}, so the sort is always unsorted.
 */
public final class OffsetPageRequest implements Pageable {

    private final long offset;
    private final int limit;

    public OffsetPageRequest(long offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0");
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        this.offset = offset;
        this.limit = limit;
    }

    @Override public int getPageNumber() { return (int) (offset / limit); }
    @Override public int getPageSize() { return limit; }
    @Override public long getOffset() { return offset; }
    @Override public Sort getSort() { return Sort.unsorted(); }
    @Override public Pageable next() { return new OffsetPageRequest(offset + limit, limit); }
    @Override public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageRequest(Math.max(0, offset - limit), limit) : first();
    }
    @Override public Pageable first() { return new OffsetPageRequest(0, limit); }
    @Override public Pageable withPage(int pageNumber) {
        return new OffsetPageRequest((long) pageNumber * limit, limit);
    }
    @Override public boolean hasPrevious() { return offset > 0; }

    @Override public boolean equals(Object o) {
        return o instanceof OffsetPageRequest other && other.offset == offset && other.limit == limit;
    }
    @Override public int hashCode() { return Long.hashCode(offset) * 31 + limit; }
    @Override public String toString() { return "OffsetPageRequest[offset=" + offset + ", limit=" + limit + "]"; }
}
