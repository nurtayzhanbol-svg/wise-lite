package com.wiselite.fx;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Element;

/** Fetches the ECB daily reference rates XML. */
@Component
public class EcbRateProvider {

    private final RestClient http;
    private final FxProperties properties;

    public EcbRateProvider(RestClient.Builder builder, FxProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        // Always set timeouts on outbound calls; the default is "wait forever".
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.requestFactory(factory).build();
        this.properties = properties;
    }

    public RateSnapshot fetch() {
        var body = http.get().uri(properties.ecbUrl()).retrieve().body(byte[].class);
        if (body == null) {
            throw new IllegalStateException("Empty ECB response");
        }
        return parse(body);
    }

    static RateSnapshot parse(byte[] xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            // External input: forbid DTDs entirely (prevents XXE and billion-laughs attacks).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

            var rates = new HashMap<String, BigDecimal>();
            rates.put("EUR", BigDecimal.ONE);
            LocalDate asOf = null;
            var cubes = doc.getElementsByTagNameNS("*", "Cube");
            for (int i = 0; i < cubes.getLength(); i++) {
                var cube = (Element) cubes.item(i);
                if (cube.hasAttribute("time")) {
                    asOf = LocalDate.parse(cube.getAttribute("time"));
                }
                if (cube.hasAttribute("currency")) {
                    var rate = new BigDecimal(cube.getAttribute("rate"));
                    if (rate.signum() <= 0) {
                        throw new IllegalArgumentException("Non-positive rate for " + cube.getAttribute("currency"));
                    }
                    rates.put(cube.getAttribute("currency"), rate);
                }
            }
            if (asOf == null || rates.size() < 2) {
                throw new IllegalArgumentException("No rates in ECB response");
            }
            return new RateSnapshot(asOf, rates, Instant.EPOCH);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable ECB response", e);
        }
    }
}
