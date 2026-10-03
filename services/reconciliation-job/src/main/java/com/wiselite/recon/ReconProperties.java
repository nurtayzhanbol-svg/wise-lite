package com.wiselite.recon;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wiselite.recon")
public record ReconProperties(Db reconDb, Db transfersDb, Db payoutsDb, URI railsUrl, Duration grace) {

    public record Db(String url, String username, String password) {}
}
