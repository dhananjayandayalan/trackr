package dev.trakr.api.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc builds /v3/api-docs from the controllers and records; this bean adds what code can't
 * say: the title, and that the API is authenticated by the session cookie. The frontend generates
 * its TypeScript types from this document (03-api §5).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI trakrOpenApi() {
        return new OpenAPI()
                .info(new Info().title("TRAKR API").version("v1"))
                .components(new Components().addSecuritySchemes("session", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("TRAKR_SESSION")))
                .addSecurityItem(new SecurityRequirement().addList("session"));
    }
}
