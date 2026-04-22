package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.common.Json;

import java.util.HashMap;
import java.util.Map;

public class ResetStubCounterAdminTask implements AdminApiExtension {
    
    @Override
    public String getName() {
        return "wiremock_reset-mock-counter";
    }
    
    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.POST, "/reset-stub-counter", (admin, serveEvent, pathParams) -> {
            StubCounterExtension.resetCounters();
            ResponseTimeTracker.resetTimings();
            
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "All counters and response times reset");
            
            return new ResponseDefinition(200, Json.write(response));
        });
    }
}