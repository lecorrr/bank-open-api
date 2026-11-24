package dev.tkachuk.openbanking;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** The application's Kyiv clock, stopped at 22 November 2026, for slices that leave TimeConfig out. */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClock {

    public static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    // 10:00 UTC is noon in Kyiv, so balances carry 22 November as their date.
    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneId.of("Europe/Kyiv"));
    }
}
