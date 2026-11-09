package com.movie.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.movie.storage.ticket.IdempotencyClaimResult;
import com.movie.storage.ticket.RedisIdempotencyRecordRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.dao.DuplicateKeyException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class StorageContractIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    @Test
    void sameSeatCannotBeInsertedTwiceForOneScreening() throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.update("INSERT INTO screenings (id, movie_title, starts_at, total_seat_count, remaining_seat_count, created_at) "
                + "VALUES ('1', 'Movie', NOW(), 1, 1, NOW())");
        jdbc.update("INSERT INTO screening_seats (screening_id, seat_no, status) VALUES ('1', 'A-1', 'AVAILABLE')");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screening_seats (screening_id, seat_no, status) VALUES ('1', 'A-1', 'AVAILABLE')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void redisClaimRejectsDuplicateAndConflictingFingerprint() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        try {
            StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
            template.afterPropertiesSet();
            RedisIdempotencyRecordRepository repository = new RedisIdempotencyRecordRepository(template);

            assertThat(repository.claim("1", "same-key", "fingerprint-a", Duration.ofMinutes(1)))
                    .isEqualTo(IdempotencyClaimResult.CLAIMED);
            assertThat(repository.claim("1", "same-key", "fingerprint-a", Duration.ofMinutes(1)))
                    .isEqualTo(IdempotencyClaimResult.DUPLICATE_IN_PROGRESS);
            assertThat(repository.claim("1", "same-key", "fingerprint-b", Duration.ofMinutes(1)))
                    .isEqualTo(IdempotencyClaimResult.FINGERPRINT_CONFLICT);
        } finally {
            connectionFactory.destroy();
        }
    }
}
