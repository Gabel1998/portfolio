package dk.andreasgabel.portfolio.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

@RestController
public class InfrastructureController {

    @Value("${infrastructure.compose-path:/app/docker-compose.yml}")
    private String composePath;

    @Value("${infrastructure.nginx-path:/app/nginx.conf}")
    private String nginxPath;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Cache: repo URL -> { timestamp, data }
    private final ConcurrentHashMap<String, CachedPipelines> pipelineCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    private record CachedPipelines(long timestamp, List<Map<String, Object>> workflows) {}

    @Value("${infrastructure.workflows-path:}")
    private String localWorkflowsPath;

    @Value("${infrastructure.github-token:}")
    private String githubToken;

    // Projects and their repos — a project can span multiple repos
    private record ProjectConfig(String name, List<String> repos) {}

    private static final List<ProjectConfig> PROJECTS = List.of(
            new ProjectConfig("Portfolio",       List.of("Gabel1998/portfolio")),
            new ProjectConfig("Raid Fines",      List.of("Gabel1998/raid-fines")),
            new ProjectConfig("Web Crawler",     List.of("Gabel1998/webCrawler", "Gabel1998/webcrawler-frontend")),
            new ProjectConfig("Beskyttelsesrum", List.of("Gabel1998/beskyttelsesrum")),
            new ProjectConfig("Talent API",      List.of("Gabel1998/talent-api")),
            new ProjectConfig("MonkKnows",       List.of("nasOps/MonkKnows"))
    );

    // Branches to check for workflow files (in order of priority)
    private static final List<String> BRANCHES_TO_CHECK = List.of("main", "master", "showroom");

    @GetMapping("/api/infrastructure")
    public Map<String, Object> getInfrastructure() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("services", parseComposeServices());
        result.put("routes", parseNginxRoutes());
        return result;
    }

    @GetMapping("/api/infrastructure/diagram")
    public Map<String, String> getDiagram() {
        String puml = generatePlantUML();
        String encoded = encodePlantUML(puml);
        return Map.of(
            "puml", puml,
            "svg", "https://www.plantuml.com/plantuml/svg/" + encoded,
            "png", "https://www.plantuml.com/plantuml/png/" + encoded
        );
    }

    @GetMapping("/api/infrastructure/pipelines")
    public List<Map<String, Object>> getPipelines() {
        List<Map<String, Object>> projects = new ArrayList<>();

        for (ProjectConfig pc : PROJECTS) {
            Map<String, Object> project = new LinkedHashMap<>();
            project.put("project", pc.name);
            project.put("repos", pc.repos);

            // Collect workflows from all repos belonging to this project
            List<Map<String, Object>> allWorkflows = new ArrayList<>();
            for (String repo : pc.repos) {
                List<Map<String, Object>> repoWorkflows = fetchWorkflows(repo);
                String repoShort = repo.substring(repo.indexOf('/') + 1);
                // Tag each workflow with its source repo (useful when multiple repos)
                for (Map<String, Object> wf : repoWorkflows) {
                    if (pc.repos.size() > 1) {
                        wf.put("repo", repoShort);
                    }
                    allWorkflows.add(wf);
                }
            }
            project.put("workflows", allWorkflows);
            projects.add(project);
        }

        return projects;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchWorkflows(String repo) {
        // Check cache
        CachedPipelines cached = pipelineCache.get(repo);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.workflows;
        }

        // Collect workflows from all branches (deduplicate by file name, keep all unique ones)
        Map<String, Map<String, Object>> workflowsByKey = new LinkedHashMap<>();

        // Try local filesystem first (for private repos like Portfolio)
        if (localWorkflowsPath != null && !localWorkflowsPath.isBlank()) {
            String repoShort = repo.contains("/") ? repo.substring(repo.indexOf('/') + 1) : repo;
            // Check if this repo's workflows are available locally (e.g., Portfolio's own .github/workflows)
            tryLoadLocalWorkflows(workflowsByKey, repoShort);
        }

        // Fetch from GitHub API across multiple branches
        for (String branch : BRANCHES_TO_CHECK) {
            try {
                String listUrl = "https://api.github.com/repos/" + repo + "/contents/.github/workflows?ref=" + branch;
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(listUrl))
                        .header("Accept", "application/vnd.github.v3+json")
                        .timeout(Duration.ofSeconds(10));
                if (githubToken != null && !githubToken.isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + githubToken);
                }
                HttpRequest listReq = reqBuilder.GET().build();

                HttpResponse<String> listRes = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
                if (listRes.statusCode() != 200) continue;

                List<Map<String, Object>> files = new Yaml().load(listRes.body());

                for (Map<String, Object> file : files) {
                    String name = (String) file.get("name");
                    if (name == null || (!name.endsWith(".yml") && !name.endsWith(".yaml"))) continue;

                    // Skip if we already have this file from another branch
                    // (same workflow file often exists on multiple branches)
                    String key = name;
                    if (workflowsByKey.containsKey(key)) continue;

                    String downloadUrl = (String) file.get("download_url");
                    if (downloadUrl == null) continue;

                    HttpRequest fileReq = HttpRequest.newBuilder()
                            .uri(URI.create(downloadUrl))
                            .timeout(Duration.ofSeconds(10))
                            .GET().build();

                    HttpResponse<String> fileRes = httpClient.send(fileReq, HttpResponse.BodyHandlers.ofString());
                    if (fileRes.statusCode() != 200) continue;

                    Map<String, Object> parsed = parseWorkflowYaml(name, fileRes.body());
                    if (parsed != null) {
                        parsed.put("branch", branch);
                        workflowsByKey.put(key, parsed);
                    }
                }
            } catch (Exception e) {
                // Continue to next branch
            }
        }

        List<Map<String, Object>> workflows = new ArrayList<>(workflowsByKey.values());
        pipelineCache.put(repo, new CachedPipelines(System.currentTimeMillis(), workflows));
        return workflows;
    }

    private void tryLoadLocalWorkflows(Map<String, Map<String, Object>> target, String repoShort) {
        // Only look in exact subdirectory matching repo name
        Path dir = Path.of(localWorkflowsPath, repoShort);
        try {
            if (!Files.isDirectory(dir)) return;

            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                      .forEach(p -> {
                          try {
                              String content = Files.readString(p);
                              String fileName = p.getFileName().toString();
                              Map<String, Object> parsed = parseWorkflowYaml(fileName, content);
                              if (parsed != null) {
                                  parsed.put("branch", "local");
                                  target.put(fileName, parsed);
                              }
                          } catch (Exception ignored) {}
                      });
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseWorkflowYaml(String fileName, String yamlContent) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> wf = yaml.load(yamlContent);
            if (wf == null) return null;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("file", fileName);
            result.put("name", wf.getOrDefault("name", fileName));

            // Parse triggers — SnakeYaml parses "on:" as Boolean.TRUE (YAML 1.1)
            Object onSection = wf.get("on");
            if (onSection == null) onSection = wf.get(Boolean.TRUE);
            result.put("triggers", parseTriggers(onSection));

            // Parse jobs
            Map<String, Object> jobs = (Map<String, Object>) wf.get("jobs");
            List<Map<String, Object>> jobList = new ArrayList<>();

            if (jobs != null) {
                for (Map.Entry<String, Object> jobEntry : jobs.entrySet()) {
                    Map<String, Object> jobCfg = (Map<String, Object>) jobEntry.getValue();
                    Map<String, Object> job = new LinkedHashMap<>();
                    job.put("id", jobEntry.getKey());
                    job.put("name", jobCfg.getOrDefault("name", jobEntry.getKey()));

                    // Dependencies
                    Object needs = jobCfg.get("needs");
                    if (needs instanceof String) {
                        job.put("needs", List.of(needs));
                    } else if (needs instanceof List) {
                        job.put("needs", needs);
                    } else {
                        job.put("needs", List.of());
                    }

                    // Environment
                    Object env = jobCfg.get("environment");
                    if (env instanceof String) {
                        job.put("environment", env);
                    } else if (env instanceof Map) {
                        job.put("environment", ((Map<String, Object>) env).get("name"));
                    }

                    // Steps — extract just the names, sanitize secrets
                    List<Map<String, Object>> steps = (List<Map<String, Object>>) jobCfg.get("steps");
                    List<String> stepNames = new ArrayList<>();
                    if (steps != null) {
                        for (Map<String, Object> step : steps) {
                            String stepName = (String) step.get("name");
                            if (stepName != null) {
                                stepNames.add(stepName);
                            } else if (step.containsKey("uses")) {
                                // e.g. actions/checkout@v4 → Checkout
                                String uses = step.get("uses").toString();
                                String action = uses.contains("/") ? uses.substring(uses.lastIndexOf('/') + 1) : uses;
                                action = action.replaceAll("@.*", "");
                                stepNames.add(action);
                            } else if (step.containsKey("run")) {
                                String run = step.get("run").toString().split("\n")[0].trim();
                                // Sanitize: remove anything that looks like a secret
                                run = run.replaceAll("\\$\\{\\{[^}]*}}", "***");
                                if (run.length() > 60) run = run.substring(0, 57) + "...";
                                stepNames.add(run);
                            }
                        }
                    }
                    job.put("steps", stepNames);

                    jobList.add(job);
                }
            }

            result.put("jobs", jobList);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTriggers(Object onSection) {
        Map<String, Object> triggers = new LinkedHashMap<>();
        if (onSection == null) return triggers;

        if (onSection instanceof String) {
            triggers.put((String) onSection, Map.of());
        } else if (onSection instanceof List) {
            for (Object item : (List<?>) onSection) {
                triggers.put(item.toString(), Map.of());
            }
        } else if (onSection instanceof Map) {
            Map<String, Object> onMap = (Map<String, Object>) onSection;
            for (Map.Entry<String, Object> entry : onMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    triggers.put(entry.getKey(), entry.getValue());
                } else {
                    triggers.put(entry.getKey(), Map.of());
                }
            }
        }
        return triggers;
    }

    @SuppressWarnings("unchecked")
    private String generatePlantUML() {
        Map<String, Object> compose;
        Map<String, Object> services;
        List<Map<String, String>> routes = parseNginxRoutes();

        try (InputStream in = new FileInputStream(composePath)) {
            Yaml yaml = new Yaml();
            compose = yaml.load(in);
            services = (Map<String, Object>) compose.get("services");
        } catch (Exception e) {
            return "@startuml\nnote \"Could not read docker-compose.yml\" as N1\n@enduml";
        }

        if (services == null) services = Map.of();

        // Build route lookup: service -> list of paths
        Map<String, List<String>> routesByUpstream = new HashMap<>();
        for (Map<String, String> r : routes) {
            if (r.containsKey("error")) continue;
            String target = r.get("target"); // e.g. http://raidfines-frontend/
            String path = r.get("path");
            // Extract service name from target URL
            String svcName = target.replaceAll("https?://", "").replaceAll("[:/].*", "");
            routesByUpstream.computeIfAbsent(svcName, k -> new ArrayList<>()).add(path);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("!theme plain\n");
        sb.append("skinparam backgroundColor transparent\n");
        sb.append("skinparam defaultFontName \"Segoe UI\"\n");
        sb.append("skinparam defaultFontSize 11\n");
        sb.append("skinparam componentStyle rectangle\n");
        sb.append("skinparam nodesep 80\n");
        sb.append("skinparam ranksep 50\n");
        sb.append("skinparam padding 8\n");
        sb.append("skinparam arrowFontSize 10\n");
        sb.append("\n");
        sb.append("title Portfolio System — Deployment Diagram\n\n");

        // Client
        sb.append("node \"Client\" as client <<device>> #FEF3C7 {\n");
        sb.append("  [Web Browser] as browser\n");
        sb.append("}\n\n");

        // GHCR
        sb.append("cloud \"GHCR\" as ghcr <<container registry>> {\n");
        sb.append("  [ghcr.io/gabel1998] as registry\n");
        sb.append("}\n\n");

        // Production Server
        sb.append("node \"Production Server\" as server <<DigitalOcean Droplet>> {\n");
        sb.append("  node \"Docker Compose\" as docker {\n\n");

        // Nginx
        sb.append("    package \"Portfolio System\" as portfolio <<network: frontend>> #EFF6FF {\n\n");
        sb.append("      node \"nginx\" as nginx <<reverse proxy>> #F0FDFA {\n");
        sb.append("        [Nginx Web Server\\nPort: 80] as nginx_proc\n");
        sb.append("      }\n\n");

        // Group projects
        Map<String, String[]> projectSvcs = new LinkedHashMap<>();
        projectSvcs.put("Portfolio Site", new String[]{"portfolio-frontend", "portfolio-backend"});
        projectSvcs.put("Raid Fines", new String[]{"raidfines-frontend", "raidfines-backend"});
        projectSvcs.put("Web Crawler", new String[]{"webcrawler-frontend", "webcrawler-backend", "selenium-chrome"});
        projectSvcs.put("Beskyttelsesrum", new String[]{"beskyttelsesrum"});
        projectSvcs.put("Talent API", new String[]{"talent-api"});

        Map<String, String> groupColors = new LinkedHashMap<>();
        groupColors.put("Portfolio Site", "#DBEAFE");
        groupColors.put("Raid Fines", "#DCFCE7");
        groupColors.put("Web Crawler", "#FFF7ED");
        groupColors.put("Beskyttelsesrum", "#F3E8FF");
        groupColors.put("Talent API", "#FEF3C7");

        // Database info per backend
        Map<String, String[]> dbInfo = new LinkedHashMap<>();
        // Check environment for profiles
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            Map<String, Object> cfg = (Map<String, Object>) entry.getValue();
            List<?> env = (List<?>) cfg.getOrDefault("environment", List.of());
            String name = entry.getKey();
            boolean isShowroom = env.stream().anyMatch(e -> e.toString().contains("SPRING_PROFILES_ACTIVE=showroom"));
            if (name.equals("raidfines-backend") && isShowroom) {
                dbInfo.put(name, new String[]{"H2 Database", "In-Memory (showroom)"});
            } else if (name.equals("webcrawler-backend") && isShowroom) {
                dbInfo.put(name, new String[]{"H2 Database", "In-Memory (showroom)"});
            } else if (name.equals("beskyttelsesrum") && isShowroom) {
                dbInfo.put(name, new String[]{"H2 Database", "In-Memory (showroom)"});
            }
        }

        for (Map.Entry<String, String[]> group : projectSvcs.entrySet()) {
            String gName = group.getKey();
            String[] svcs = group.getValue();
            String color = groupColors.getOrDefault(gName, "#F9FAFB");
            String alias = gName.toLowerCase().replaceAll("[^a-z]", "_");

            // Only include groups with existing services
            boolean hasAny = false;
            for (String s : svcs) if (services.containsKey(s)) hasAny = true;
            if (!hasAny) continue;

            sb.append("      package \"").append(gName).append("\" as ").append(alias)
              .append(" ").append(color).append(" {\n");

            for (String sName : svcs) {
                if (!services.containsKey(sName)) continue;
                Map<String, Object> cfg = (Map<String, Object>) services.get(sName);
                String sAlias = sName.replaceAll("-", "_");

                String type = sName.contains("frontend") ? "frontend" :
                             sName.contains("backend") ? "backend" :
                             sName.equals("selenium-chrome") ? "browser engine" :
                             sName.equals("beskyttelsesrum") || sName.equals("talent-api") ? "full-stack" : "service";

                List<?> expose = (List<?>) cfg.getOrDefault("expose", List.of());
                List<?> ports = (List<?>) cfg.getOrDefault("ports", List.of());
                String port = !ports.isEmpty() ? ports.get(0).toString() :
                              !expose.isEmpty() ? expose.get(0).toString() : "";

                String image = cfg.containsKey("image") ? cfg.get("image").toString() : null;
                String src = image != null ? image.substring(image.lastIndexOf('/') + 1) : "local build";

                sb.append("        node \"").append(sName).append("\" as ").append(sAlias)
                  .append(" <<").append(type).append(">> {\n");
                sb.append("          [").append(src);
                if (!port.isEmpty()) sb.append("\\nPort: ").append(port);
                sb.append("] as ").append(sAlias).append("_proc\n");
                sb.append("        }\n");

                // Database for this service
                if (dbInfo.containsKey(sName)) {
                    String[] db = dbInfo.get(sName);
                    sb.append("        database \"").append(db[0]).append("\" as ")
                      .append(sAlias).append("_db <<").append(db[1]).append(">>\n");
                }
            }

            sb.append("      }\n\n");
        }

        sb.append("    }\n"); // Portfolio System
        sb.append("  }\n"); // Docker
        sb.append("}\n\n"); // Server

        // Connections
        sb.append("' === Connections ===\n");
        sb.append("browser -down-> nginx_proc : HTTPS\\n:443\n");
        sb.append("registry -left-> docker : docker pull\n\n");

        // Nginx → services (using routes)
        for (Map.Entry<String, String[]> group : projectSvcs.entrySet()) {
            for (String sName : group.getValue()) {
                if (!services.containsKey(sName)) continue;
                if (sName.equals("selenium-chrome")) continue;
                String sAlias = sName.replaceAll("-", "_") + "_proc";
                List<String> svcRoutes = routesByUpstream.getOrDefault(sName, List.of());
                if (svcRoutes.isEmpty()) {
                    if (sName.equals("portfolio-backend")) svcRoutes = routesByUpstream.getOrDefault("backend", List.of());
                    if (sName.equals("portfolio-frontend")) svcRoutes = routesByUpstream.getOrDefault("frontend", List.of());
                }
                String label = svcRoutes.isEmpty() ? "proxy" : String.join("\\n", svcRoutes);
                sb.append("nginx_proc -down-> ").append(sAlias).append(" : ").append(label).append("\n");
            }
        }

        // Selenium connection
        if (services.containsKey("webcrawler-backend") && services.containsKey("selenium-chrome")) {
            sb.append("webcrawler_backend_proc -right-> selenium_chrome_proc : Selenium\\n:4444\n");
        }

        // Database connections
        for (Map.Entry<String, String[]> db : dbInfo.entrySet()) {
            String sAlias = db.getKey().replaceAll("-", "_");
            sb.append(sAlias).append("_proc -down-> ").append(sAlias).append("_db : JDBC\n");
        }

        sb.append("\n@enduml\n");
        return sb.toString();
    }

    // PlantUML URL encoding (deflate + custom base64)
    private String encodePlantUML(String text) {
        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(Deflater.BEST_COMPRESSION, true));
            dos.write(data);
            dos.close();
            byte[] compressed = baos.toByteArray();
            return encode64(compressed);
        } catch (Exception e) {
            return "";
        }
    }

    private String encode64(byte[] data) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            if (i + 2 == data.length) {
                r.append(append3bytes(data[i] & 0xFF, data[i + 1] & 0xFF, 0));
            } else if (i + 1 == data.length) {
                r.append(append3bytes(data[i] & 0xFF, 0, 0));
            } else {
                r.append(append3bytes(data[i] & 0xFF, data[i + 1] & 0xFF, data[i + 2] & 0xFF));
            }
        }
        return r.toString();
    }

    private String append3bytes(int b1, int b2, int b3) {
        int c1 = b1 >> 2;
        int c2 = ((b1 & 0x3) << 4) | (b2 >> 4);
        int c3 = ((b2 & 0xF) << 2) | (b3 >> 6);
        int c4 = b3 & 0x3F;
        return "" + encode6bit(c1) + encode6bit(c2) + encode6bit(c3) + encode6bit(c4);
    }

    private char encode6bit(int b) {
        if (b < 10) return (char) ('0' + b);
        b -= 10;
        if (b < 26) return (char) ('A' + b);
        b -= 26;
        if (b < 26) return (char) ('a' + b);
        b -= 26;
        if (b == 0) return '-';
        return '_';
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseComposeServices() {
        List<Map<String, Object>> serviceList = new ArrayList<>();

        try (InputStream in = new FileInputStream(composePath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> compose = yaml.load(in);
            Map<String, Object> services = (Map<String, Object>) compose.get("services");

            if (services == null) return serviceList;

            for (Map.Entry<String, Object> entry : services.entrySet()) {
                Map<String, Object> config = (Map<String, Object>) entry.getValue();
                Map<String, Object> svc = new LinkedHashMap<>();
                svc.put("name", entry.getKey());
                svc.put("image", config.getOrDefault("image", null));
                svc.put("build", config.containsKey("build") ? config.get("build") : null);
                svc.put("ports", config.getOrDefault("ports", List.of()));
                svc.put("expose", config.getOrDefault("expose", List.of()));
                svc.put("dependsOn", config.getOrDefault("depends_on", List.of()));
                svc.put("networks", config.getOrDefault("networks", List.of()));
                svc.put("restart", config.getOrDefault("restart", null));

                List<String> envKeys = new ArrayList<>();
                Object env = config.get("environment");
                if (env instanceof List<?> envList) {
                    for (Object e : envList) {
                        String s = e.toString();
                        int eq = s.indexOf('=');
                        envKeys.add(eq > 0 ? s.substring(0, eq) : s);
                    }
                }
                svc.put("envKeys", envKeys);

                serviceList.add(svc);
            }
        } catch (Exception e) {
            serviceList.add(Map.of("error", "Could not read docker-compose.yml: " + e.getMessage()));
        }

        return serviceList;
    }

    private List<Map<String, String>> parseNginxRoutes() {
        List<Map<String, String>> routes = new ArrayList<>();

        try {
            String content = Files.readString(Path.of(nginxPath));
            String[] lines = content.split("\n");

            String currentLocation = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("location ")) {
                    currentLocation = trimmed.replace("location ", "").replace("{", "").trim();
                } else if (trimmed.startsWith("proxy_pass ") && currentLocation != null) {
                    String target = trimmed.replace("proxy_pass ", "").replace(";", "").trim();
                    routes.add(Map.of("path", currentLocation, "target", target));
                    currentLocation = null;
                }
            }
        } catch (Exception e) {
            routes.add(Map.of("error", "Could not read nginx.conf: " + e.getMessage()));
        }

        return routes;
    }
}
