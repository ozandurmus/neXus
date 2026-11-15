package com.securityexpert.nexus.ui2.service.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.Clock;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.HttpFirewall;

/**
 * Spring Security 7 foundation, Step 1 of {@code docs/design/SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md}: a
 * <em>passthrough</em> filter chain. {@code FilterChainProxy} is now in the servlet filter chain, but it changes nothing
 * a client or a controller can observe: every request still reaches {@link GateChainInterceptor} exactly as before, and
 * authentication, sessions, CSRF and authorization stay where they are (the {@code ui2_session} cookie, {@link GateChain}
 * E1 to E6). Later steps move those into this chain one at a time.
 *
 * <p>Zero behaviour change is the whole contract, so every default Spring Security would add is switched off here, each
 * one on purpose:</p>
 * <ul>
 *   <li>CSRF: off. {@code GateChain} E1 enforces the per-session secret and the Origin header.</li>
 *   <li>HTTP Basic, form login (and its generated login page), logout (and its {@code /logout} endpoint): off.</li>
 *   <li>Sessions: {@code STATELESS}, so Spring Security never creates a servlet {@code JSESSIONID}; the request cache is
 *       off so it never saves or replays a request.</li>
 *   <li>Anonymous authentication and the servlet-API request wrapper: off, so a request and its principal look as before.</li>
 *   <li>CORS: off.</li>
 *   <li>Response headers (PO decision 2026-11-15, {@code PO_DECISION_RECORD_2026_11_15_SPRING_SECURITY_DECISIONS.md}):
 *       {@link NexusSecurityHeadersFilter} writes {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY} and
 *       {@code Cache-Control: no-store} on every response. The {@code Cache-Control} is only the default: a response that
 *       already chose its own cache policy (the immutable {@code /assets/*}, the revalidated {@code index.html}, the download
 *       endpoints) keeps it, so static asset caching is as before. Spring's own header writers (and so Pragma/Expires, HSTS,
 *       X-XSS-Protection) stay off.</li>
 *   <li>The request firewall: a pass-through. {@code StrictHttpFirewall} would answer 400 to request targets the
 *       service accepts today (for example {@code /healthz;x=1}); Tomcat's own checks stay the only ones.</li>
 *   <li>Authorization: every request is permitted; the only filter behind the chain is still the Spring MVC interceptor.</li>
 * </ul>
 *
 * <p>Step 2 adds exactly one filter, {@link Ui2SessionAuthenticationFilter}: it only reads the {@code ui2_session} cookie into the
 * {@code SecurityContext} for the request and changes no answer.</p>
 *
 * <p>{@code MachinePortConfiguration.machinePortFilter} is registered at {@code HIGHEST_PRECEDENCE}, ahead of the
 * Spring Security filter (default order -100), so port 8086 isolation still runs first.</p>
 */
@Configuration
@EnableWebSecurity
public class NexusSecurityFilterChainConfiguration {

    @Bean
    SecurityFilterChain nexusPassthroughSecurityFilterChain(HttpSecurity http,
            ObjectProvider<SessionRepository> sessions, ObjectProvider<Clock> clock) throws Exception {
        // Step 2: the ui2_session cookie becomes a SecurityContext authentication (additive; GateChain stays the authority).
        // Created here and not as a bean so Spring Boot does not also register it as a plain servlet filter.
        SessionRepository sessionRepository = sessions.getIfAvailable();
        http.addFilterAfter(new NexusSecurityHeadersFilter(), SecurityContextHolderFilter.class);
        if (sessionRepository != null) {
            Clock now = clock.getIfAvailable(Clock::system);
            http.addFilterAfter(new Ui2SessionAuthenticationFilter(sessionRepository, now::now),
                    NexusSecurityHeadersFilter.class);
        }
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                // PO 2026-11-15 response headers are written by NexusSecurityHeadersFilter (added above), not by Spring's
                // HeaderWriterFilter, which races on asynchronous responses.
                .headers(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .servletApi(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Picked up by Spring Security's {@code WebSecurity} in place of {@code StrictHttpFirewall}: wraps the request without
     * judging it, so the set of request targets the service answers does not change.
     */
    @Bean
    HttpFirewall nexusPassthroughHttpFirewall() {
        return new HttpFirewall() {
            @Override
            public FirewalledRequest getFirewalledRequest(HttpServletRequest request) {
                return new FirewalledRequest(request) {
                    @Override
                    public void reset() {
                        // nothing was changed on the wrapped request
                    }
                };
            }

            @Override
            public HttpServletResponse getFirewalledResponse(HttpServletResponse response) {
                return response;
            }
        };
    }
}
