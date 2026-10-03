package org.example.commonmanager.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.commonmanager.dto.ApiErrorResponseDTO;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@RestControllerAdvice
public class GlobalApiExceptionHandler {

    /**
     * A request that is missing a required parameter, or carries one it cannot read, is a <b>client</b>
     * error.
     *
     * <p>It was arriving as a <b>500</b>, because neither type had a handler and both fell through to the
     * catch-all below. That is the wrong signal twice over: the server did not break, and a frontend that
     * checks {@code response.ok} cannot tell a malformed request from a real outage — which is the mistake
     * this codebase has already made once, where loaders that did not check {@code ok} turned a 404 into a
     * generic "API Error" card.
     *
     * <p>Found while testing {@code DELETE /transfers/remove/{playerId}}: omitting {@code teamId} logged
     * {@code Unhandled exception ... Required request parameter 'teamId' is not present} and answered 500.
     */
    @ExceptionHandler({
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponseDTO> handleMalformedRequest(Exception ex, HttpServletRequest request) {
        log.debug(
                "Malformed request during {} {} (query={}): {}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                ex.getMessage()
        );

        ApiErrorResponseDTO body = new ApiErrorResponseDTO(
                HttpStatus.BAD_REQUEST.value(),
                "BAD_REQUEST",
                ex.getMessage() != null ? ex.getMessage() : "The request could not be read.",
                request.getRequestURI(),
                LocalDateTime.now()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleMissingStaticResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.debug(
                "Static resource not found during {} {} (query={}): {}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                ex.getMessage()
        );

        ApiErrorResponseDTO body = new ApiErrorResponseDTO(
                HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND",
                ex.getMessage() != null ? ex.getMessage() : "Resource not found.",
                request.getRequestURI(),
                LocalDateTime.now()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex) {
        log.debug("Client disconnected: {}", ex.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public void handleIOException(IOException ex) {
        if (ex.getMessage() != null && ex.getMessage().contains("Broken pipe")) {
            log.debug("Broken pipe (client disconnected): {}", ex.getMessage());
        } else {
            log.warn("IOException: {}", ex.getMessage());
        }
    }

    /**
     * A deliberate refusal from the game, reported with the status it was thrown with.
     *
     * <p>There was no handler for {@code ApiException} at all, so every one of them fell through to
     * the catch-all below and reached the browser as a 500. That is not a cosmetic problem: the game
     * signals ordinary, expected outcomes with this exception — the transfer window is shut, the
     * club cannot afford the fee, the squad is full, the price is below the asking price, the report
     * does not exist — and all of them were indistinguishable from a genuine server fault. The
     * frontend reacts by logging the user out or replacing a whole page with "API Error".
     */
    @ExceptionHandler(org.example.footballmanager.newLogic.exception.ApiException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleApiException(
            org.example.footballmanager.newLogic.exception.ApiException ex,
            HttpServletRequest request) {

        // Logged at warn, not error: these are decisions the game made, not faults.
        log.warn("{} {} refused: [{}] {}", request.getMethod(), request.getRequestURI(),
                ex.getCode(), ex.getMessage());

        ApiErrorResponseDTO body = new ApiErrorResponseDTO(
                ex.getStatus().value(),
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI(),
                LocalDateTime.now());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * A caller sent something the server refuses to act on — an unknown unit, an advance of
     * {@code amount = 2147483647} hours, a negative count.
     *
     * <p>Without this it fell into the catch-all below and arrived as a <b>500</b>, so a manager who typed
     * something unreasonable was told the server had broken. That is the same failure
     * {@code ApiExceptionStatusTest} exists for on the other side: a refusal the game decided on is a 4xx,
     * and reporting it as a server fault sends people looking in the wrong place.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleIllegalArgument(IllegalArgumentException ex,
                                                                     HttpServletRequest request) {
        log.warn("Rejected request to {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponseDTO(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                ex.getMessage() != null ? ex.getMessage() : "The request was not valid.",
                request.getRequestURI(),
                java.time.LocalDateTime.now()));
    }

    /**
     * Anything the game decided not to allow — a role that may not advance the world, a player who does not
     * own the thing they asked about.
     *
     * <p><b>These are re-thrown, not handled.</b> A {@code @PreAuthorize} denial throws
     * {@code AuthorizationDeniedException} (Spring Security 6) from the method-security proxy, and without
     * this it fell into the catch-all below and arrived as a <b>500</b>: a manager who was correctly refused
     * was told the server had broken, and the real refusal was logged as an unhandled exception. Security
     * exceptions belong to the filter chain, which turns them into a proper 401 or 403.
     *
     * <p>This is why {@code @PreAuthorize} was chosen over throwing from inside the controller body: both
     * arrive here, and both need this.
     */
    /**
     * A refusal that already carries its own status.
     *
     * <p>Controllers throw {@code ResponseStatusException} to say "404, not found" or "403, not yours", and
     * the catch-all below was flattening all of them into a <b>500</b>. So a deliberate refusal read as a
     * broken server: a cup tie belonging to another country came back 500 rather than 404, and the reason
     * was logged as an unhandled exception.
     *
     * <p>The same failure as {@code IllegalArgumentException} below, and as the security exceptions — a
     * catch-all that owns every exception ends up owning the exceptions that already knew what they were.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("{} {} failed with {}", request.getMethod(), request.getRequestURI(), status);
        } else {
            log.warn("Refused {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getReason());
        }
        return ResponseEntity.status(status).body(new ApiErrorResponseDTO(
                status.value(),
                status.is5xxServerError() ? "INTERNAL_SERVER_ERROR" : "REQUEST_REFUSED",
                ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(),
                request.getRequestURI(),
                LocalDateTime.now()));
    }

    @ExceptionHandler({
            org.springframework.security.authorization.AuthorizationDeniedException.class,
            org.springframework.security.access.AccessDeniedException.class,
            org.springframework.security.core.AuthenticationException.class})
    public void rethrowSecurityException(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponseDTO> handleUnhandledException(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("Response already committed, cannot write error for {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            return null;
        }

        Throwable rootCause = ex;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }

        log.error(
                "Unhandled exception during {} {} (query={}): {}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                rootCause.getMessage(),
                ex
        );

        ApiErrorResponseDTO body = new ApiErrorResponseDTO(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                rootCause instanceof DataAccessException ? "DATA_ACCESS_ERROR" : "INTERNAL_SERVER_ERROR",
                rootCause.getMessage() != null ? rootCause.getMessage() : "Unexpected server error.",
                request.getRequestURI(),
                LocalDateTime.now()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
