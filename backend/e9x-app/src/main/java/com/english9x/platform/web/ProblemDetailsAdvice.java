package com.english9x.platform.web;

import com.english9x.org.api.OrgContextDeniedException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as RFC 7807 {@code application/problem+json}: {@code type}, {@code title},
 * {@code status}, {@code detail} and {@code errors[]} (always present; one entry per invalid field/row).
 *
 * <p>Spring's own MVC exceptions (404, 405, 406, 415, malformed body, type mismatch ...) are mapped by the base
 * class; this class normalises their {@code type} and adds {@code errors}. Anything unexpected becomes a generic
 * 500: the exception goes to the log, never to the client. Access refusals become 403 without ids or messages.
 * Spring Security's AccessDeniedException (P1.4) is not on the classpath yet; P1.4 maps it in this advice.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsAdvice.class);
    private static final String URN = "urn:e9x:problem:";
    private static final Pattern FIRST_INDEX = Pattern.compile("\\[(\\d+)]");

    public ProblemDetailsAdvice(MessageSource messageSource) {
        setMessageSource(messageSource);
    }

    // ---- validation -------------------------------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        BindingResult result = ex.getBindingResult();
        List<ProblemError> errors = new ArrayList<>();
        for (FieldError e : result.getFieldErrors()) {
            errors.add(error(e.getField(), e.getCode(), e.getDefaultMessage()));
        }
        result.getGlobalErrors().forEach(e -> errors.add(error(null, e.getCode(), e.getDefaultMessage())));
        return validation(errors, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ProblemError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(r -> {
            String name = r.getMethodParameter().getParameterName();
            r.getResolvableErrors().forEach(e -> errors.add(error(name, firstCode(e.getCodes()), e.getDefaultMessage())));
        });
        return validation(errors, headers);
    }

    /** Constraint violations raised outside request binding (service/persist time) are still the caller's input. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(ConstraintViolationException ex) {
        List<ProblemError> errors = new ArrayList<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            String code = v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            errors.add(error(v.getPropertyPath().toString(), code, v.getMessage()));
        }
        return validation(errors, new HttpHeaders());
    }

    private ResponseEntity<Object> validation(List<ProblemError> errors, HttpHeaders headers) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "One or more fields are invalid.");
        p.setProperty("errors", errors);
        return respond(p, headers);
    }

    private static ProblemError error(String field, String code, String message) {
        Integer row = null;
        if (field != null) {
            Matcher m = FIRST_INDEX.matcher(field);
            if (m.find()) {
                row = Integer.valueOf(m.group(1));
            }
        }
        return new ProblemError(field, row, code, message);
    }

    private static String firstCode(String[] codes) {
        return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
    }

    // ---- access and unexpected errors ----------------------------------------------------------------------------

    /**
     * Catch-all. The org-context refusal can arrive bare or wrapped (a transaction manager wraps a failure while
     * opening the transaction, e.g. CannotCreateTransactionException), so the whole cause chain is searched.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OrgContextDeniedException) {
                return respond(forbidden(), new HttpHeaders());
            }
        }
        LOG.error("Unhandled exception", ex);
        ProblemDetail p = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
                "An unexpected error occurred.");
        p.setProperty("errors", List.of());
        return respond(p, new HttpHeaders());
    }

    private ProblemDetail forbidden() {
        ProblemDetail p = problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "You do not have access to this resource.");
        p.setProperty("errors", List.of());
        return p;
    }

    // ---- Spring MVC's own exceptions: normalise type, guarantee errors[] -------------------------------------------

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                "The request body could not be read.");
        p.setProperty("errors", List.of());
        return respond(p, headers);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body == null && ex instanceof ErrorResponse er) {
            body = er.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (body instanceof ProblemDetail p) {
            if (p.getType() == null || "about:blank".equals(p.getType().toString())) {
                p.setType(URI.create(URN + slug(statusCode)));
            }
            if (p.getProperties() == null || !p.getProperties().containsKey("errors")) {
                p.setProperty("errors", List.of());
            }
            if (statusCode.is5xxServerError()) {
                p.setDetail("An unexpected error occurred."); // no internals, whatever the framework said
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(URN + slug));
        p.setTitle(title);
        return p;
    }

    private static ResponseEntity<Object> respond(ProblemDetail p, HttpHeaders headers) {
        HttpHeaders h = new HttpHeaders();
        h.putAll(headers);
        h.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(p, h, p.getStatus());
    }

    private static String slug(HttpStatusCode code) {
        if (code instanceof HttpStatus s) {
            return s.name().toLowerCase().replace('_', '-');
        }
        return "http-" + code.value();
    }
}
