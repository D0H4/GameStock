package com.gamestock.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.stream.Stream;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String webOrigin;

    public CorsConfig(@Value("${gamestock.cors-origin}") String webOrigin) {
        this.webOrigin = webOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 배포 도메인과 로컬 개발 화면을 함께 허용한다. 로컬 프론트는
                // localhost:8080(또는 다른 개발 포트)에서 백엔드 8081을 호출한다.
                .allowedOriginPatterns(Stream.concat(
                        Stream.of(webOrigin.split(",")),
                        Stream.of("https://syuenasset.xyz", "http://localhost:*", "http://127.0.0.1:*"))
                        .map(String::trim).filter(origin -> !origin.isEmpty())
                        .toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
