package org.example.footballmanager.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.footballmanager.dto.ApiErrorResponseDTO;
import org.example.footballmanager.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleApiException(ApiException ex, HttpServletRequest request) {
        return buildResponse(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleResponseStatusException(ResponseStatusException ex, HttpServletRequest request) {
        String message = ex.getReason() != null && !ex.getReason().isBlank()
                ? ex.getReason()
                : "Request could not be completed.";
        return buildResponse(HttpStatus.valueOf(ex.getStatusCode().value()), "REQUEST_FAILED", message, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", safeMessage(ex.getMessage(), "Request is invalid."), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, "CONFLICT", safeMessage(ex.getMessage(), "Request cannot be completed right now."), request);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponseDTO> handleRuntime(RuntimeException ex, HttpServletRequest request) {
        log.error("Unhandled runtime exception for {}", request != null ? request.getRequestURI() : "unknown path", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", safeMessage(ex.getMessage(), "Request could not be completed."), request);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiErrorResponseDTO> handleThrowable(Throwable ex, HttpServletRequest request) {
        log.error("Unhandled throwable for {}", request != null ? request.getRequestURI() : "unknown path", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error.", request);
    }

    private ResponseEntity<ApiErrorResponseDTO> buildResponse(HttpStatus status,
                                                              String code,
                                                              String message,
                                                              HttpServletRequest request) {
        ApiErrorResponseDTO body = new ApiErrorResponseDTO(
                status.value(),
                code,
                message,
                request != null ? request.getRequestURI() : null,
                LocalDateTime.now()
        );
        return ResponseEntity.status(status).body(body);
    }

    private String safeMessage(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
