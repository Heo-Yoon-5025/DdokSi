package com.ddoksi.ddoksi.collection.api;

/**
 * 국회 API 호출 실패.
 *
 * <p>재시도 가치를 {@code retryable} 로 구분한다.
 * 네트워크 오류나 타임아웃은 다시 시도하면 성공할 수 있지만,
 * 인증키 무효(ERROR-290)나 응답 구조 불일치는 몇 번을 반복해도 결과가 같다.
 * 이 구분이 없으면 배치가 같은 실패를 반복하며 시간과 호출 한도만 소모한다.
 *
 * <p>생성자 대신 정적 팩토리를 쓴다. {@code new AssemblyApiException(msg, null, false)} 처럼
 * 불리언만 보고는 재시도 여부를 알기 어렵고, null 인자가 생성자 선택을 모호하게 만든다.
 */
public class AssemblyApiException extends RuntimeException {

    private final boolean retryable;
    private final String resultCode;

    private AssemblyApiException(String message, Throwable cause, String resultCode, boolean retryable) {
        super(message, cause);
        this.resultCode = resultCode;
        this.retryable = retryable;
    }

    /** 다시 시도하면 성공할 수 있는 실패 (타임아웃, 연결 실패, 5xx, 빈 응답). */
    public static AssemblyApiException retryable(String message) {
        return new AssemblyApiException(message, null, null, true);
    }

    /** @see #retryable(String) */
    public static AssemblyApiException retryable(String message, Throwable cause) {
        return new AssemblyApiException(message, cause, null, true);
    }

    /** 재시도해도 결과가 같은 실패 (설정 오류, 응답 구조 불일치). */
    public static AssemblyApiException fatal(String message) {
        return new AssemblyApiException(message, null, null, false);
    }

    /** @see #fatal(String) */
    public static AssemblyApiException fatal(String message, Throwable cause) {
        return new AssemblyApiException(message, cause, null, false);
    }

    /** 국회 API 가 RESULT 코드로 알려준 오류. 코드를 남겨 원인 추적에 쓴다. */
    public static AssemblyApiException apiError(String message, String resultCode) {
        return new AssemblyApiException(message, null, resultCode, false);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getResultCode() {
        return resultCode;
    }
}
