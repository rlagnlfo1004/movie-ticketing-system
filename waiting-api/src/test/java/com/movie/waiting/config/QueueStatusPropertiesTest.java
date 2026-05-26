package com.movie.waiting.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class QueueStatusPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TestConfiguration.class));

    @Test
    void defaultsArePositive() {
        contextRunner.run(context -> {
            QueueStatusProperties properties = context.getBean(QueueStatusProperties.class);

            assertThat(properties.getWaitingPollAfterMillis()).isEqualTo(1000L);
            assertThat(properties.getActivePollAfterMillis()).isEqualTo(100L);
            assertThat(properties.getNotFoundPollAfterMillis()).isEqualTo(3000L);
        });
    }

    @Test
    void rejectsNonPositiveValues() {
        contextRunner
                .withPropertyValues("waiting.queue.status.waiting-poll-after-millis=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(QueueStatusProperties.class)
    static class TestConfiguration {
    }
}
