package ru.hhassistant.infrastructure.hh;

public record ApplyOutcome(
  ApplyStatus status,
  Integer httpStatus,
  String errorCode,
  boolean retryable
) {

  public static ApplyOutcome applied() {
    return new ApplyOutcome(ApplyStatus.APPLIED, 200, null, false);
  }

  public static ApplyOutcome alreadyApplied() {
    return new ApplyOutcome(ApplyStatus.ALREADY_APPLIED, 200, "alreadyApplied", false);
  }

  public static ApplyOutcome tempError(String errorCode, String detail) {
    String combined = detail != null && !detail.isBlank()
      ? errorCode + ": " + detail
      : errorCode;
    return new ApplyOutcome(ApplyStatus.TEMP_ERROR, null, combined, true);
  }

  public static ApplyOutcome permError(String errorCode) {
    return new ApplyOutcome(ApplyStatus.PERM_ERROR, null, errorCode, false);
  }

  public static ApplyOutcome authError(String errorCode) {
    return new ApplyOutcome(ApplyStatus.AUTH_ERROR, null, errorCode, false);
  }

  public static ApplyOutcome timeout() {
    return new ApplyOutcome(ApplyStatus.TIMEOUT, null, "timeout", true);
  }
}
