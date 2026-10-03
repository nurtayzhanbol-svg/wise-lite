package com.wiselite.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class EcbRateProviderTest {

    static byte[] sample() throws Exception {
        try (var in = EcbRateProviderTest.class.getResourceAsStream("/ecb-sample.xml")) {
            return in.readAllBytes();
        }
    }

    @Test
    void parsesTheEcbFormat() throws Exception {
        var snapshot = EcbRateProvider.parse(sample());

        assertThat(snapshot.asOf()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(snapshot.perEur()).containsKeys("EUR", "USD", "JPY", "GBP", "HUF");
        assertThat(snapshot.rate(Currency.getInstance("EUR"), Currency.getInstance("HUF"))).isEqualByComparingTo("395.10");
    }

    @Test
    void rejectsDoctypesToPreventXxe() {
        var xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <Envelope><Cube><Cube time="2026-10-02"><Cube currency="USD" rate="&x;"/></Cube></Cube></Envelope>
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EcbRateProvider.parse(xxe)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsGarbageAndEmptyResponses() {
        assertThatThrownBy(() -> EcbRateProvider.parse("<html>maintenance</html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EcbRateProvider.parse("{".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownCurrencyIsRejected() throws Exception {
        var snapshot = EcbRateProvider.parse(sample());
        assertThatThrownBy(() -> snapshot.rate(Currency.getInstance("EUR"), Currency.getInstance("KZT")))
                .isInstanceOf(UnsupportedCurrencyException.class);
    }
}
