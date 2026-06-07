package io.sitprep.sitprepapi.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for controller methods that should consult the idempotency cache
 * before executing — audit P1-10.
 *
 * <p>When the request carries an {@code Idempotency-Key} header and the
 * (caller, endpoint, key) tuple has a successful cached response within
 * the TTL, {@link IdempotencyInterceptor} short-circuits and returns the
 * cached body verbatim. Misses execute normally and have their successful
 * (2xx) response cached on the way out. Non-2xx responses are not cached
 * — the client is meant to retry those and we don't want to lock in a
 * transient failure.</p>
 *
 * <p>Requests without the header pass through with no cache interaction.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {
}
