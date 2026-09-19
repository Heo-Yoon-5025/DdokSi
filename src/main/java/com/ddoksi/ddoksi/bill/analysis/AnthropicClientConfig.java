package com.ddoksi.ddoksi.bill.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Claude API 클라이언트 빈.
 *
 * <p>키는 {@code ANTHROPIC_API_KEY} 환경변수로 받는다. 국회 API 키와 마찬가지로
 * gitignore 된 {@code .env} 에 두고, 설정 파일에는 값을 적지 않는다.
 *
 * <p><b>키가 없어도 기동은 성공해야 한다.</b> 분석 배치는 기본 비활성이고 조회 API 와
 * 수집 배치는 Claude 없이 동작한다. 키가 없다고 앱 전체가 뜨지 않으면 개발이 막힌다.
 * 그래서 빈은 만들어 두고, 실제 호출 시점에 SDK 가 인증 오류를 낸다.
 */
@Configuration(proxyBeanMethods = false)
public class AnthropicClientConfig {

    @Bean
    public AnthropicClient anthropicClient(@Value("${ddoksi.analysis.api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            // 키 없이도 SDK 는 다른 자격증명 경로(환경변수, 프로파일)를 스스로 찾는다.
            return AnthropicOkHttpClient.fromEnv();
        }
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }
}
