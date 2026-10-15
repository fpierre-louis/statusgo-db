package io.sitprep.sitprepapi.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An unknown route is a 404 and a wrong verb is a 405, never a 500.
 *
 * <p>Spring Boot 3.2+ answers an unmatched path with
 * {@code NoResourceFoundException}, which fell through to the catch-all and
 * came back as {@code INTERNAL_ERROR} (build-10 backlog, found by the group
 * lane on {@code /api/no-such-route}).</p>
 */
class UnknownRouteNotServerErrorTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unknownRouteIs404() {
        var req = new MockHttpServletRequest("GET", "/api/no-such-route");
        var ex = new NoResourceFoundException(HttpMethod.GET, "api/no-such-route");

        ResponseEntity<Map<String, Object>> res = handler.handleAllUncaughtException(ex, req);

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertEquals("Not Found", body.get("message"));
        assertTrue(body.containsKey("data") && body.containsKey("error") && body.containsKey("meta"));
    }

    @Test
    void wrongVerbIs405() {
        var req = new MockHttpServletRequest("DELETE", "/api/community/posts");
        var ex = new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED,
                handler.handleAllUncaughtException(ex, req).getStatusCode());
    }

    @Test
    void aRealFailureIsStill500WithAGenericMessage() {
        var req = new MockHttpServletRequest("GET", "/api/anything");

        ResponseEntity<Map<String, Object>> res =
                handler.handleAllUncaughtException(new IllegalStateException("select * from secret"), req);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertEquals("An unexpected error occurred", res.getBody().get("message"));
    }
}
