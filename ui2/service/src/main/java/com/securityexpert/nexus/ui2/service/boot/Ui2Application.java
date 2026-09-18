package com.securityexpert.nexus.ui2.service.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * UI 2.0 composition root — the entry point that makes the service a running
 * process rather than a library.
 *
 * <p>Until this class existed the module had six controllers and no way to
 * serve any of them: {@code bootJar} was disabled with the note "no main class
 * wired to it". Phase 1 step 1 is exactly this — the roof — and nothing
 * more: the process starts, connects to its already-migrated database, and
 * serves a shell. The deployment's short-lived {@link MigrationMain} process
 * applies migrations before this long-running process starts.</p>
 *
 * <p>Spring's {@code DataSourceAutoConfiguration} is excluded deliberately.
 * The `C1` §6 secret discipline requires the database credential to be read
 * from a file per component and purpose, and to fail closed when that file is
 * missing, unreadable or empty. Auto-configuration would take a URL and a
 * password from properties instead, which is the shape that puts a credential
 * into a config file or an environment default. {@link DatabaseConfiguration}
 * builds the DataSource under that rule instead.</p>
 *
 * <p>{@code JooqAutoConfiguration} is excluded for the same reason in a
 * different direction: jOOQ reaches this module's runtime classpath through
 * {@code :persistence}, and Spring would then wire its own jOOQ/transaction
 * integration. `C1` puts transaction boundaries in {@code :persistence}, so
 * the framework must not take them over here.</p>
 */
// The composition root sits in `.boot`, so component scanning is anchored at
// the service package above it -- otherwise the controllers in
// `…​.ui2.service` and `…​.ui2.service.api` are never found and every route
// answers 404 while the process looks healthy.
@SpringBootApplication(
        scanBasePackages = "com.securityexpert.nexus.ui2.service",
        exclude = {DataSourceAutoConfiguration.class, JooqAutoConfiguration.class})
// The `…service.api` controllers were all written before their collaborators
// were beans; step 1 excluded the whole package deliberately, one movement at
// a time removing the filter as it wires each controller's own collaborators.
//
// C3A local authentication narrowed the exclusion to admit LoginController,
// LoginResolveController, PasswordChangeController and SessionStatusController.
// NXS-LOCAL-0152 (forced password change / session sign-out) adds a fifth:
// SessionLogoutController (self sign-out, distinct from the admin-only
// SessionAdminController that stays excluded below). All five now have real,
// working collaborators via LocalAuthenticationConfiguration.
//
// RoleBindingAdminController is no longer excluded: 13G local identity
// administration (PO_DECISION_RECORD_2026_09_13G) needs
// POST /role-bindings / POST /role-bindings/revoke reachable for real, so
// this movement finished wiring GateChain/RBAC (RbacConfiguration) rather
// than leave it deferred. NXS-LOCAL-0158 wires DeviceRegistrationController's
// own remaining collaborators (DeviceAddSingleService, DeviceQueryService,
// DeviceCompositionConfiguration) and removes it from this exclusion.
// SessionAdminController stays excluded: its own collaborator (a
// session-admin service) is untouched by this movement and remains out of
// scope. It is excluded, not deleted: a route that 404s because its
// controller was never registered is honest, while one that 500s on every
// call is not.
@ComponentScan(
        basePackages = "com.securityexpert.nexus.ui2.service",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.securityexpert\\.nexus\\.ui2\\.service\\.api\\.SessionAdminController"))
public class Ui2Application {
    public static void main(String[] args) {
        SpringApplication.run(Ui2Application.class, args);
    }
}
