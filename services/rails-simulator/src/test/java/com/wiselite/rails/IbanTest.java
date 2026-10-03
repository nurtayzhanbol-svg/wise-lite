package com.wiselite.rails;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IbanTest {

    @ParameterizedTest
    @ValueSource(strings = {"DE89370400440532013000", "GB82 WEST 1234 5698 7654 32", "HU42117730161111101800000000", "EE382200221020145685"})
    void validIbans(String iban) {
        assertThat(Iban.isValid(iban)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DE89370400440532013001", "GB82WEST12345698765433", "XX00", "", "DE8937040044053201300!"})
    void invalidIbans(String iban) {
        assertThat(Iban.isValid(iban)).isFalse();
    }
}
