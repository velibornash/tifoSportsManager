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
