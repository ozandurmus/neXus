package com.securityexpert.nexus.ui2.service.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Spring Boot 4 defaults HTTP JSON to Jackson 3. This service keeps Jackson 2 (its DTOs, {@code @JsonProperty}
 * names and {@code JsonNode} payloads are Jackson 2), so the response shape must stay what Spring Boot 3 wrote:
 * one Jackson 2 converter backed by Boot's ObjectMapper, ISO-8601 instants, unknown request fields ignored.
 */
@SuppressWarnings("removal") // MappingJackson2HttpMessageConverter: deprecated in Spring Framework 7, still supported.
class HttpJsonMapperConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer()) // the real application.yml
            .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
                    WebMvcAutoConfiguration.class));

    record Sample(@JsonProperty("submitted_at") Instant submittedAt) { }

    @Test
    void jackson3IsNotOnTheClasspath() {
        assertThat(ClassUtils.isPresent("tools.jackson.databind.ObjectMapper", null)).isFalse();
    }

    @Test
    void springMvcWritesJsonWithBootsJackson2ObjectMapper() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.http.converters.preferred-json-mapper"))
                    .isEqualTo("jackson2");
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            List<HttpMessageConverter<?>> converters =
                    context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();
            List<org.springframework.http.converter.json.MappingJackson2HttpMessageConverter> json = converters.stream()
                    .filter(org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class::isInstance)
                    .map(org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class::cast)
                    .toList();
            assertThat(json).hasSize(1);
            assertThat(json.get(0).getObjectMapper()).isSameAs(mapper);
            // A String return value (GET /healthz -> "ok") is still written as UTF-8 text, never as a JSON string.
            int string = indexOf(converters, StringHttpMessageConverter.class);
            assertThat(string).isNotNegative().isLessThan(converters.indexOf(json.get(0)));
            assertThat(((StringHttpMessageConverter) converters.get(string)).getDefaultCharset())
                    .isEqualTo(StandardCharsets.UTF_8);

            assertThat(mapper.writeValueAsString(new Sample(Instant.parse("2026-10-15T08:30:00Z"))))
                    .isEqualTo("{\"submitted_at\":\"2026-10-15T08:30:00Z\"}");
            assertThat(mapper.readValue("{\"submitted_at\":\"2026-10-15T08:30:00Z\",\"extra\":1}", Sample.class))
                    .isEqualTo(new Sample(Instant.parse("2026-10-15T08:30:00Z")));
        });
    }

    private static int indexOf(List<HttpMessageConverter<?>> converters, Class<?> type) {
        for (int i = 0; i < converters.size(); i++) {
            if (type.isInstance(converters.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
