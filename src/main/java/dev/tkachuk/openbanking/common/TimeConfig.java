package dev.tkachuk.openbanking.common;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TimeConfig {

    // Kyiv time decides a consent's last valid day and the day its reads count for. Whole microseconds, as
    // PostgreSQL stores them, so a timestamp reads back as it was written.
    @Bean
    Clock clock() {
        return Clock.tick(Clock.system(ZoneId.of("Europe/Kyiv")), Duration.ofNanos(1_000));
    }
}
