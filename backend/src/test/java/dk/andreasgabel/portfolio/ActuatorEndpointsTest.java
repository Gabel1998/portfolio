package dk.andreasgabel.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the Prometheus scrape endpoint and health probe added for
 * observability are actually exposed and serving the expected payloads.
 *
 * {@code @AutoConfigureObservability} is required because Spring Boot disables
 * metrics-export auto-configuration (incl. the Prometheus registry/endpoint)
 * inside {@code @SpringBootTest} by default. Production exposure is configured
 * in application.properties.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoints.web.exposure.include=health,info,prometheus"
)
@AutoConfigureObservability
class ActuatorEndpointsTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void prometheusEndpointExposesMicrometerMetrics() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/prometheus", String.class);
        assertEquals(200, res.getStatusCode().value());
        assertNotNull(res.getBody());
        // Micrometer publishes JVM metrics in the Prometheus text exposition format.
        assertTrue(res.getBody().contains("jvm_memory_used_bytes"),
                "Prometheus endpoint should expose Micrometer JVM metrics");
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        assertEquals(200, res.getStatusCode().value());
        assertNotNull(res.getBody());
        assertTrue(res.getBody().contains("\"status\":\"UP\""));
    }
}
