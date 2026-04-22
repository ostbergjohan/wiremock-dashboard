package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class DashboardExtension implements AdminApiExtension {
    
    @Override
    public String getName() {
        return "wiremock_dashboard";
    }
    
    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.GET, "/dashboard", (admin, serveEvent, pathParams) -> {
            try {
                InputStream is = getClass().getResourceAsStream("/dashboard/index.html");
                if (is == null) {
                    return new ResponseDefinition(404, "Dashboard not found");
                }
                
                String html = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
                
                return new ResponseDefinition(200, html);
            } catch (Exception e) {
                return new ResponseDefinition(500, "Error loading dashboard: " + e.getMessage());
            }
        });

        router.add(RequestMethod.GET, "/favicon.svg", (admin, serveEvent, pathParams) -> {
            try {
                InputStream is = getClass().getResourceAsStream("/dashboard/favicon.svg");
                if (is == null) {
                    return new ResponseDefinition(404, "Favicon not found");
                }
                String svg = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
                return new ResponseDefinitionBuilder()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/svg+xml")
                    .withBody(svg)
                    .build();
            } catch (Exception e) {
                return new ResponseDefinition(500, "Error loading favicon: " + e.getMessage());
            }
        });
    }
}