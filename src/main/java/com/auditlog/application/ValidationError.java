package com.auditlog.application;

public record ValidationError(String code, String message, String field) {

  public ValidationError(String code, String message) {
    this(code, message, null);
  }

  public static ValidationError missingFilter() {
    return new ValidationError(
        "MISSING_FILTER", "At least one of 'actor' or 'resource' is required.");
  }

  public static ValidationError missingParameter(String field) {
    return new ValidationError(
        "MISSING_PARAMETER", "Parameter '" + field + "' is required.", field);
  }

  public static ValidationError invalidTimeWindow() {
    return new ValidationError("INVALID_TIME_WINDOW", "'from' must be strictly before 'to'.");
  }

  public static ValidationError windowTooLarge() {
    return new ValidationError("WINDOW_TOO_LARGE", "Time window must not exceed 7 days.");
  }

  public static ValidationError limitOutOfRange() {
    return new ValidationError("LIMIT_OUT_OF_RANGE", "'limit' must be between 1 and 500.", "limit");
  }

  public static ValidationError invalidActorSet() {
    return new ValidationError(
        "INVALID_ACTOR_SET",
        "'actor' must contain 1 to 10 comma-separated non-empty values; comma is reserved as the separator.",
        "actor");
  }

  public static ValidationError conflictingParameters() {
    return new ValidationError(
        "CONFLICTING_PARAMETERS", "'cursor' cannot be supplied with filter parameters.");
  }
}
