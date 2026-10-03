package com.wiselite.recon;

import com.wiselite.rails.api.RailsApi.StatementLine;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RailStatementClient {

    private final RestClient http;

    public RailStatementClient(RestClient.Builder builder, ReconProperties properties) {
        this.http = builder.baseUrl(properties.railsUrl().toString()).build();
    }

    public List<StatementLine> statement() {
        var lines = http.get().uri("/statement").retrieve().body(StatementLine[].class);
        return lines == null ? List.of() : Arrays.asList(lines);
    }
}
