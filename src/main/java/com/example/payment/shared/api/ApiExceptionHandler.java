package com.example.payment.shared.api;

import com.example.payment.shared.application.ResourceNotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError(Instant.now(), HttpStatus.NOT_FOUND.value(), exception.getMessage()));
  }

  public record ApiError(Instant timestamp, int status, String message) {}
}
