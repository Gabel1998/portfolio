package dk.andreasgabel.portfolio.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure, network-free parsing/encoding logic in
 * {@link InfrastructureController}: docker-compose parsing, nginx route parsing,
 * PlantUML generation/encoding, and GitHub Actions workflow YAML parsing
 * (including secret sanitization). The GitHub-API pipeline fetching is not
 * covered here as it requires network access.
 */
class InfrastructureControllerTest {

    private InfrastructureController controller;

    @TempDir
    Path tempDir;

    private Path composeFile;
    private Path nginxFile;

    @BeforeEach
    void setUp() throws Exception {
        controller = new InfrastructureController();

        composeFile = tempDir.resolve("docker-compose.yml");
        Files.writeString(composeFile, """
                services:
                  nginx:
                    image: nginx:alpine
                    ports:
                      - "80:80"
                    networks:
                      - frontend
                  portfolio-backend:
                    image: ghcr.io/gabel1998/portfolio/backend:latest
                    expose:
                      - "8080"
                    environment:
                      - CORS_ORIGINS=http://localhost
                      - GITHUB_TOKEN=supersecretvalue
                    networks:
                      - frontend
                networks:
                  frontend:
                """);

        nginxFile = tempDir.resolve("nginx.conf");
        Files.writeString(nginxFile, """
                upstream backend {
                    server portfolio-backend:8080;
                }
                server {
                    listen 443 ssl;
                    location / {
                        proxy_pass http://frontend;
                    }
                    location /api/ {
                        proxy_pass http://backend;
                    }
                }
                """);

        ReflectionTestUtils.setField(controller, "composePath", composeFile.toString());
        ReflectionTestUtils.setField(controller, "nginxPath", nginxFile.toString());
    }

    @Test
    void getInfrastructure_returnsServicesAndRoutes() {
        Map<String, Object> result = controller.getInfrastructure();

        assertTrue(result.containsKey("services"));
        assertTrue(result.containsKey("routes"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) result.get("services");
        assertEquals(2, services.size());

        Map<String, Object> backend = services.stream()
                .filter(s -> "portfolio-backend".equals(s.get("name")))
                .findFirst().orElseThrow();
        assertEquals("ghcr.io/gabel1998/portfolio/backend:latest", backend.get("image"));
    }

    @Test
    void parseComposeServices_stripsSecretValuesKeepingOnlyEnvKeys() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>)
                ReflectionTestUtils.invokeMethod(controller, "parseComposeServices");

        Map<String, Object> backend = services.stream()
                .filter(s -> "portfolio-backend".equals(s.get("name")))
                .findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        List<String> envKeys = (List<String>) backend.get("envKeys");
        // Keys are surfaced; the secret value must never leak.
        assertTrue(envKeys.contains("GITHUB_TOKEN"));
        assertTrue(envKeys.contains("CORS_ORIGINS"));
        assertFalse(envKeys.toString().contains("supersecretvalue"));
    }

    @Test
    void parseNginxRoutes_mapsLocationToProxyTarget() {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> routes = (List<Map<String, String>>)
                ReflectionTestUtils.invokeMethod(controller, "parseNginxRoutes");

        assertEquals(2, routes.size());
        assertTrue(routes.stream().anyMatch(r ->
                "/".equals(r.get("path")) && "http://frontend".equals(r.get("target"))));
        assertTrue(routes.stream().anyMatch(r ->
                "/api/".equals(r.get("path")) && "http://backend".equals(r.get("target"))));
    }

    @Test
    void getDiagram_producesPlantUmlAndEncodedUrls() {
        Map<String, String> diagram = controller.getDiagram();

        String puml = diagram.get("puml");
        assertNotNull(puml);
        assertTrue(puml.contains("@startuml"));
        assertTrue(puml.contains("@enduml"));

        assertTrue(diagram.get("svg").startsWith("https://www.plantuml.com/plantuml/svg/"));
        assertTrue(diagram.get("png").startsWith("https://www.plantuml.com/plantuml/png/"));
        // The deflate+base64 payload must be non-empty for a valid compose file.
        assertTrue(diagram.get("svg").length() > "https://www.plantuml.com/plantuml/svg/".length());
    }

    @Test
    void parseWorkflowYaml_parsesNameTriggersJobsAndSanitizesSecrets() {
        String yaml = """
                name: CI
                on:
                  push:
                    branches: [main]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - name: Build
                        run: mvn verify
                      - run: echo ${{ secrets.TOKEN }}
                """;

        @SuppressWarnings("unchecked")
        Map<String, Object> wf = (Map<String, Object>)
                ReflectionTestUtils.invokeMethod(controller, "parseWorkflowYaml", "ci.yml", yaml);

        assertNotNull(wf);
        assertEquals("CI", wf.get("name"));
        assertEquals("ci.yml", wf.get("file"));

        @SuppressWarnings("unchecked")
        Map<String, Object> triggers = (Map<String, Object>) wf.get("triggers");
        assertTrue(triggers.containsKey("push"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) wf.get("jobs");
        assertEquals(1, jobs.size());
        assertEquals("build", jobs.get(0).get("id"));

        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) jobs.get(0).get("steps");
        assertTrue(steps.contains("checkout"));   // derived from "uses: actions/checkout@v4"
        assertTrue(steps.contains("Build"));       // named step
        // Unnamed run step: secret interpolation must be sanitized to ***
        assertTrue(steps.stream().anyMatch(s -> s.contains("***")));
        assertFalse(steps.toString().contains("secrets.TOKEN"));
    }

    @Test
    void parseTriggers_handlesStringListAndMapForms() {
        @SuppressWarnings("unchecked")
        Map<String, Object> fromString = (Map<String, Object>)
                ReflectionTestUtils.invokeMethod(controller, "parseTriggers", "push");
        assertTrue(fromString.containsKey("push"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fromList = (Map<String, Object>)
                ReflectionTestUtils.invokeMethod(controller, "parseTriggers", List.of("push", "pull_request"));
        assertTrue(fromList.containsKey("push"));
        assertTrue(fromList.containsKey("pull_request"));

        Map<String, Object> onMap = new LinkedHashMap<>();
        onMap.put("schedule", List.of(Map.of("cron", "0 0 * * *")));
        onMap.put("workflow_dispatch", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> fromMap = (Map<String, Object>)
                ReflectionTestUtils.invokeMethod(controller, "parseTriggers", onMap);
        assertTrue(fromMap.containsKey("schedule"));
        assertTrue(fromMap.containsKey("workflow_dispatch"));
    }

    @Test
    void parseTriggers_nullReturnsEmpty() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                ReflectionTestUtils.invokeMethod(controller, "parseTriggers", new Object[]{null});
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
