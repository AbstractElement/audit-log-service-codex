package com.auditlog.api;

import com.auditlog.application.ValidationError;
import com.auditlog.application.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  public record ErrorResponse(String error, String message, String field) {}

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex) {
    ValidationError err = ex.error();
    return ResponseEntity.badRequest()
        .body(new ErrorResponse(err.code(), err.message(), err.field()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                "INVALID_TIMESTAMP",
                "Parameter '" + ex.getName() + "' must be ISO-8601 UTC.",
                ex.getName()));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingParam(
      MissingServletRequestParameterException ex) {
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                "MISSING_PARAMETER",
                "Parameter '" + ex.getParameterName() + "' is required.",
                ex.getParameterName()));
  }
}
