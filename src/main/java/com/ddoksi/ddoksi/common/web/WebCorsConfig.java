package com.ddoksi.ddoksi.common.web;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 설정.
 *
 * Expo 웹(기본 8081)에서 이 API(8080)를 호출하면 브라우저가 교차 출처로 보고 막는다.
 * 앱 개발 중에는 이 설정이 없으면 요청이 아예 도달하지 않는다.
 *
 * 허용 출처를 설정으로 뺀 이유: 개발용 localhost 를 운영 환경에 그대로 열어두면 안 된다.
 * 운영에서는 실제 도메인만 지정한다. 와일드카드(*)는 쓰지 않는다.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public WebCorsConfig(@Value("${ddoksi.cors.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                // 조회 전용이므로 읽기 메서드만 연다. 필요해질 때 추가한다.
                .allowedMethods("GET")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }
}
