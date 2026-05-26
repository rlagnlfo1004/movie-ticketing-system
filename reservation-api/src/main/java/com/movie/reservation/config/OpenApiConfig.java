package com.movie.reservation.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI reservationApiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Movie Reservation API")
                        .description("티켓 발급 및 발급 이력 조회 API")
                        .version("v1"));
    }
}
