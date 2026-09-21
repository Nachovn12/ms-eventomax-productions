package cl.duoc.eventomax.productions.controller;

import cl.duoc.eventomax.productions.service.InvalidTransitionException;
import cl.duoc.eventomax.productions.service.ResourceNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;


@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {

        String detail = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        Map<String, Object> response = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", 400,
                "error", "Bad Request",
                "message", detail
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);

    }


    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            ResourceNotFoundException ex) {

        Map<String, Object> response = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", 404,
                "error", "Not Found",
                "message", ex.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);

    }


    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTransition(
            InvalidTransitionException ex) {

        Map<String, Object> response = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", 409,
                "error", "Conflict",
                "message", ex.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);

    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex) {

        Map<String, Object> response = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", 400,
                "error", "Bad Request",
                "message", ex.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);

    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {

        Map<String, Object> response = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", 400,
                "error", "Bad Request",
                "message", "Malformed JSON request or invalid fields"
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);

    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {

        Map<String, Object> response = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", 400,
                "error", "Bad Request",
                "message", "Invalid parameter type: " + ex.getName()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);

    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException ex) {

        Map<String, Object> response = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", 415,
                "error", "Unsupported Media Type",
                "message", "Media type not supported. Please use application/json"
        );

        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(response);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(ex.getHeaders())
                .body(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "status", 405,
                        "error", "Method Not Allowed",
                        "message", "HTTP method not supported for this endpoint"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("Unexpected error processing request", ex);

        Map<String, Object> response = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", 500,
                "error", "Internal Server Error",
                "message", "An unexpected error occurred"
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }

}
