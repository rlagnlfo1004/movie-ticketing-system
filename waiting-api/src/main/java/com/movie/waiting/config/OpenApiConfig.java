package com.movie.waiting.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI waitingApiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Movie Waiting API")
                        .description("대기열 등록, 상태 조회, 입장 가능 토큰 조회 API")
                        .version("v1"));
    }
}
