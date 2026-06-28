package com.eop.platform;

import com.eop.authz.ForbiddenException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to RFC-7807 {@code application/problem+json} (Spring serializes
 * {@link ProblemDetail} with that content type). Every problem carries the request's correlation id so a
 * client error can be tied to server logs. Matches the frozen contract's {@code Problem} schema.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setType(URI.create("urn:eop:error:" + ex.reason().name().toLowerCase()));
        problem.setProperty("reason", ex.reason().name());
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return problem;
    }
}
