package com.orochiverse.platform.common.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Wires {@link EmailProperties} and a Thymeleaf engine dedicated to email
 * templates. Distinct from Spring Boot's auto-wired Thymeleaf engine
 * (which targets HTML view rendering at {@code templates/}) so we can use
 * TEXT mode without disabling the global HTML mode.
 *
 * <p>The {@link NoOpEmailService} fallback is registered with
 * {@code @ConditionalOnMissingBean(EmailService.class)} so:
 * <ul>
 *   <li>Dev/integration/prod: {@link SmtpEmailService} wins (its
 *       {@code @ConditionalOnBean(JavaMailSender.class)} matches).</li>
 *   <li>{@code test} profile: no {@code JavaMailSender} bean, so
 *       {@code SmtpEmailService} backs off and this NoOp registers.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailConfig {

    @Bean
    public TemplateEngine emailTemplateEngine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCacheable(true);
        resolver.setCharacterEncoding("UTF-8");

        // SpringTemplateEngine uses SpringEL (already on classpath via
        // spring-boot-starter-thymeleaf). Plain TemplateEngine would pull
        // in OGNL, which isn't a transitive dep — using it would force
        // adding ognl just for our email templates.
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    @Bean
    @ConditionalOnMissingBean(EmailService.class)
    public EmailService noOpEmailService() {
        return new NoOpEmailService();
    }
}
