package io.sitprep.sitprepapi.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A malformed REQUEST is a 400, never a 500.
 *
 * <p>Spring's binding failures used to fall through to the catch-all
 * {@code @ExceptionHandler(Exception.class)} and come back as
 * {@code INTERNAL_ERROR} with a 500. Found on {@code GET /api/community/posts},
 * whose required {@code lat}/{@code lng} produced a 500 when omitted and a 200
 * when supplied — but it was never that endpoint's bug. Every endpoint with a
 * required parameter had it, which is why the fix and this test live here.</p>
 *
 * <p>Why it matters beyond tidiness: a 500 tells the client the server broke
 * when the client sent a bad request, and it fires the 5xx alerting path for
 * ordinary user input.</p>
 */
class BadRequestNotServerErrorTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        return req;
    }

    @Test
    void missingRequiredParameterIs400NotServerError() {
        var ex = new MissingServletRequestParameterException("lat", "Double");

        ResponseEntity<Map<String, Object>> res =
                handler.handleMissingParam(ex, request("/api/community/posts"));

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode(),
                "a missing query parameter must not be reported as a server error");
        assertNotNull(res.getBody());
    }

    @Test
    void missingParameterResponseNamesTheParameter() {
        var ex = new MissingServletRequestParameterException("lng", "Double");

        Map<String, Object> body =
                handler.handleMissingParam(ex, request("/api/community/posts")).getBody();

        assertNotNull(body);
        // The caller has to be able to say WHICH field is missing; "something
        // went wrong" is what this replaced.
        assertTrue(String.valueOf(body.get("message")).contains("lng"),
                "the message should name the missing parameter, got: " + body.get("message"));
    }

    @Test
    void missingParameterKeepsTheCanonicalEnvelope() {
        var ex = new MissingServletRequestParameterException("lat", "Double");

        Map<String, Object> body =
                handler.handleMissingParam(ex, request("/api/community/posts")).getBody();

        assertNotNull(body);
        // data + error + meta is what the FE interceptor detects an envelope by.
        assertTrue(body.containsKey("data"));
        assertTrue(body.containsKey("error"));
        assertTrue(body.containsKey("meta"));
    }

    @Test
    void wrongParameterTypeIs400NotServerError() throws Exception {
        MethodParameter param = new MethodParameter(
                BadRequestNotServerErrorTest.class.getDeclaredMethod("sample", Double.class), 0);
        var ex = new MethodArgumentTypeMismatchException(
                "not-a-number", Double.class, "lat", param, new NumberFormatException());

        ResponseEntity<Map<String, Object>> res =
                handler.handleTypeMismatch(ex, request("/api/community/posts"));

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertTrue(String.valueOf(res.getBody().get("message")).contains("lat"));
    }

    @SuppressWarnings("unused")
    private void sample(Double lat) { /* MethodParameter target only */ }
}
