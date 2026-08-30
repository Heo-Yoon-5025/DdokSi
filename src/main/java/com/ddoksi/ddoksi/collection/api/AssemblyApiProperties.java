package com.ddoksi.ddoksi.collection.api;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 국회 Open API 접속 설정.
 *
 * @param key         인증키. 코드나 설정 파일에 넣지 않고 환경변수/.env 로 주입한다.
 * @param baseUrl     API 기본 주소
 * @param userAgent   요청 User-Agent. ⚠️ 비워두면 앞단 WAF 가 HTTP 400 으로 막는다.
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    응답 타임아웃. 없으면 상대가 응답하지 않을 때 배치 스레드가 영원히 붙잡힌다.
 * @param pageSize    한 번에 요청할 건수
 * @param requestDelay 요청 사이 간격. 호출 한도를 모르는 상태이므로 보수적으로 둔다.
 */
@ConfigurationProperties(prefix = "assembly.api")
public record AssemblyApiProperties(
        String key,
        String baseUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        int pageSize,
        Duration requestDelay
) {
    public boolean hasKey() {
        return key != null && !key.isBlank();
    }
}
