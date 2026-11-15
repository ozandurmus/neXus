package com.securityexpert.nexus.ui2.service.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
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
 *   <li>CORS: off. Response headers: off. The service has never sent X-Frame-Options, X-Content-Type-Options (outside two
 *       controllers that set it themselves), Pragma, Expires or a blanket Cache-Control, and the plan's "keep security
 *       response headers" would ADD them to every response, including the immutable {@code /assets/*}; that is a
 *       behaviour change and is left for a decision of its own (see the report of this step).</li>
 *   <li>The request firewall: a pass-through. {@code StrictHttpFirewall} would answer 400 to request targets the
 *       service accepts today (for example {@code /healthz;x=1}); Tomcat's own checks stay the only ones.</li>
 *   <li>Authorization: every request is permitted; the only filter behind the chain is still the Spring MVC interceptor.</li>
 * </ul>
 *
 * <p>{@code MachinePortConfiguration.machinePortFilter} is registered at {@code HIGHEST_PRECEDENCE}, ahead of the
 * Spring Security filter (default order -100), so port 8086 isolation still runs first.</p>
 */
@Configuration
@EnableWebSecurity
public class NexusSecurityFilterChainConfiguration {

    @Bean
    SecurityFilterChain nexusPassthroughSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
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
