package com.orochiverse.platform.common.openapi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Wires the bearer-auth security scheme into the generated OpenAPI doc so
 * Swagger UI shows the "Authorize" button. Without this, calls from
 * Swagger always go out without a token and every protected endpoint
 * returns 401 even when the user has a valid JWT pasted somewhere.
 *
 * <p>Endpoints individually annotated with
 * {@code @SecurityRequirement(name = "bearerAuth")} would also work, but
 * a global requirement is the right default since most of our API is
 * authenticated — Spring's allowlist (jwks, actuator, login) doesn't go
 * through Swagger anyway.
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI orochiverseOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Orochiverse Platform API")
                        .version("0.1.0")
                        .description("IAM + multi-tenant platform shell. M1 surface: auth, "
                                + "operator admin (tenants, operators, assignments), audit.")
                        .license(new License().name("Proprietary")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME))
                .components(new Components().addSecuritySchemes(SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the access token returned by POST /api/auth/login.")));
    }
}
