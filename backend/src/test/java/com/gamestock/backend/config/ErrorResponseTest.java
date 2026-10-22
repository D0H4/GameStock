package com.gamestock.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the production error configuration with an isolated HTTP server and no application DB. */
@SpringBootTest(classes=ErrorResponseTest.TestApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorResponseTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)
    @Import(FailingController.class)
    static class TestApplication { }

    @RestController
    static class FailingController {
        @GetMapping("/audit-error-response")
        String fail() { throw new IllegalStateException("internal-table-name-and-driver-detail"); }
    }

    @Autowired TestRestTemplate http;

    @Test void unhandledServerErrorDoesNotExposeItsInternalException() {
        var response=http.getForEntity("/audit-error-response",String.class);
        assertEquals(500,response.getStatusCode().value());
        assertFalse(response.getBody().contains("internal-table-name-and-driver-detail"));
        assertFalse(response.getBody().contains("IllegalStateException"));
        assertFalse(response.getBody().contains("\"trace\""));
    }
}
