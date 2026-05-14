package com.orochiverse.platform.common.openapi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Wires two alternative auth schemes into the generated OpenAPI doc so
 * Swagger UI's "Authorize" modal offers a choice:
 *
 * <ul>
 *   <li>{@code oauth2Password} — RFC 6749 §4.3 password grant against
 *       {@code POST /api/auth/oauth-token}. The user types their email
 *       + password in the modal; Swagger UI fetches a token and attaches
 *       it to every subsequent "Try it out" call.</li>
 *   <li>{@code bearerAuth} — paste an access token directly (e.g. one
 *       obtained out-of-band from the SPA's {@code /api/auth/login}).</li>
 * </ul>
 *
 * <p>The two are registered as <em>separate</em> top-level
 * {@link SecurityRequirement} entries, which OpenAPI interprets as OR
 * (either one satisfies the operation). A single requirement listing
 * both schemes would mean AND, which isn't what we want.
 *
 * <p>Spring's actual auth allowlist (jwks, actuator, /api/auth/login,
 * /api/auth/oauth-token, …) lives in {@code SecurityConfig} — the
 * OpenAPI schemes describe what protected endpoints expect, not what
 * the public ones do.
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_BEARER = "bearerAuth";
    private static final String SCHEME_OAUTH = "oauth2Password";

    @Bean
    public OpenAPI orochiverseOpenAPI() {
        // Password flow: Swagger UI POSTs username/password/grant_type=password to tokenUrl
        // as application/x-www-form-urlencoded, and reads `access_token` from the response.
        // Our /api/auth/oauth-token adapter speaks exactly that wire shape.
        OAuthFlow passwordFlow = new OAuthFlow()
                .tokenUrl("/api/auth/oauth-token")
                .scopes(new Scopes());

        SecurityScheme oauth2 = new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Log in inline — Swagger UI sends your email + password to "
                        + "/api/auth/oauth-token and uses the returned access token "
                        + "automatically. Leave client_id / client_secret blank.")
                .flows(new OAuthFlows().password(passwordFlow));

        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste an access token (e.g. one obtained from "
                        + "/api/auth/login outside Swagger).");

        return new OpenAPI()
                .info(new Info()
                        .title("Orochiverse Platform API")
                        .version("0.1.0")
                        .description("IAM + multi-tenant platform shell. M1 surface: auth, "
                                + "operator admin (tenants, operators, assignments), audit.")
                        .license(new License().name("Proprietary")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_OAUTH))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_BEARER))
                .components(new Components()
                        .addSecuritySchemes(SCHEME_OAUTH, oauth2)
                        .addSecuritySchemes(SCHEME_BEARER, bearer));
    }
}
