package com.gamestock.backend.openapi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.List;

@Configuration
public class OpenApiWebConfig implements WebMvcConfigurer {
    private final OpenApiRateLimiter limiter;
    private TrustedClientAddress addresses=new TrustedClientAddress("127.0.0.1,::1");
    public OpenApiWebConfig(OpenApiRateLimiter limiter) { this.limiter = limiter; }
    @org.springframework.beans.factory.annotation.Value("${gamestock.http.trusted-proxy-addresses:127.0.0.1,::1}")
    void trustedProxyAddresses(String value) { addresses=new TrustedClientAddress(value); }

    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
            ObjectMapper strict = strictMapper(jackson.getObjectMapper());
            for (Class<?> type : List.of(OpenApiModels.Order.class, OpenApiModels.Close.class, OpenApiModels.CreateKey.class))
                jackson.registerObjectMappersForType(type, mappings -> mappings.put(MediaType.APPLICATION_JSON, strict));
        }
    }

    static ObjectMapper strictMapper(ObjectMapper source) {
        return source.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "no-store");
                if (!"OPTIONS".equals(request.getMethod()) && request.getDispatcherType() != jakarta.servlet.DispatcherType.ASYNC)
                    limiter.ip(addresses.resolve(request));
                return true;
            }
        }).addPathPatterns("/api/openapi/**");
    }
}
