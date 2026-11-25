package dev.tkachuk.openbanking.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceIdsTest {

    @Test
    void shouldParseIdInTheFormTheBankHandsOut() {
        UUID id = UUID.fromString("0b6c1f2e-5d4a-4c3b-9a8f-7e6d5c4b3a21");

        assertThat(ResourceIds.parse(id.toString())).contains(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0B6C1F2E-5D4A-4C3B-9A8F-7E6D5C4B3A21", "0B6c1f2e-5d4a-4c3b-9a8f-7e6d5c4b3a21", "1-1-1-1-1",
            "0b6c1f2e5d4a4c3b9a8f7e6d5c4b3a21", "0b6c1f2e-5d4a-4c3b-9a8f-7e6d5c4b3a21/status", "not-an-id", ""})
    void shouldFindNoIdInOtherText(String text) {
        assertThat(ResourceIds.parse(text)).isEmpty();
    }
}
