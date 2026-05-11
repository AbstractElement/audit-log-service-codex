package com.auditlog.application;

public class ValidationException extends RuntimeException {

  private final ValidationError error;

  public ValidationException(ValidationError error) {
    super(error.message());
    this.error = error;
  }

  public ValidationError error() {
    return error;
  }
}
