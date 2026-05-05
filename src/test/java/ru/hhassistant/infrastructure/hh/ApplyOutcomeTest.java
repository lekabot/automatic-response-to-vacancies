package ru.hhassistant.infrastructure.hh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplyOutcomeTest {

    @Test
    void applied_hasCorrectFields() {
        var outcome = ApplyOutcome.applied();
        assertThat(outcome.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.errorCode()).isNull();
        assertThat(outcome.retryable()).isFalse();
    }

    @Test
    void alreadyApplied_hasCorrectFields() {
        var outcome = ApplyOutcome.alreadyApplied();
        assertThat(outcome.status()).isEqualTo(ApplyStatus.ALREADY_APPLIED);
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("alreadyApplied");
    }

    @Test
    void tempError_withDetail_combinedIntoErrorCode() {
        // MN-4 fix: detail is now preserved in errorCode
        var outcome = ApplyOutcome.tempError("transport_error", "Connection refused");
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
        assertThat(outcome.errorCode()).isEqualTo("transport_error: Connection refused");
    }

    @Test
    void tempError_withNullDetail_onlyBaseCode() {
        var outcome = ApplyOutcome.tempError("captcha_required", null);
        assertThat(outcome.errorCode()).isEqualTo("captcha_required");
    }

    @Test
    void tempError_withBlankDetail_onlyBaseCode() {
        var outcome = ApplyOutcome.tempError("captcha_required", "   ");
        assertThat(outcome.errorCode()).isEqualTo("captcha_required");
    }

    @Test
    void permError_notRetryable() {
        var outcome = ApplyOutcome.permError("validationError");
        assertThat(outcome.status()).isEqualTo(ApplyStatus.PERM_ERROR);
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("validationError");
    }

    @Test
    void authError_notRetryableAuthStatus() {
        var outcome = ApplyOutcome.authError("no_xsrf");
        assertThat(outcome.status()).isEqualTo(ApplyStatus.AUTH_ERROR);
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("no_xsrf");
    }

    @Test
    void timeout_retryable() {
        var outcome = ApplyOutcome.timeout();
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TIMEOUT);
        assertThat(outcome.retryable()).isTrue();
        assertThat(outcome.errorCode()).isEqualTo("timeout");
    }
}
