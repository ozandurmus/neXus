package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * A Spring stereotype class (service, component, controller, repository,
 * configuration) with more than one constructor must mark exactly one with
 * {@code @Autowired}.
 *
 * <p>Spring picks the constructor itself only when there is exactly one. With
 * two or more it needs {@code @Autowired} on one, or it falls back to a
 * no-argument constructor, and if there is none the application context
 * fails with "No default constructor found" -- at startup, not in any unit
 * test. That is how a ship's preview failed twice on 2026-11-15: a new
 * {@code @Service} had two public constructors and nothing marked.</p>
 *
 * <p>The rule is exactly Spring's own: a class with several visible
 * constructors is fine when one is {@code @Autowired}, or when none is and a
 * no-argument constructor exists (Spring then uses that one, e.g.
 * {@code FailoverBookingAdmissionControl}, whose second constructor takes a
 * test clock). It is a violation when none is marked and there is no
 * no-argument constructor, or when more than one is marked.</p>
 *
 * <p>Only constructors Spring can see count: private constructors are
 * ignored by its candidate scan, so a stereotype class with one visible
 * constructor and private helpers is fine.</p>
 */
class SpringStereotypeConstructorTest {

    private static final Set<String> STEREOTYPES = Set.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration");

    private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.securityexpert.nexus.ui2");

    @Test
    void importedSomething() {
        assertFalse(CLASSES.isEmpty());
    }

    @Test
    void stereotypeClassWithSeveralConstructorsMarksExactlyOneAutowired() {
        List<String> violations = CLASSES.stream()
                .filter(SpringStereotypeConstructorTest::isStereotype)
                .filter(c -> !c.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
                .filter(c -> !c.isInterface())
                .map(SpringStereotypeConstructorTest::violation)
                .filter(v -> v != null)
                .sorted()
                .collect(Collectors.toList());
        assertTrue(violations.isEmpty(),
                "Spring stereotype classes with several non-private constructors need exactly one @Autowired "
                        + "or a no-argument constructor (otherwise the context fails to start): " + violations);
    }

    private static boolean isStereotype(JavaClass c) {
        return STEREOTYPES.stream().anyMatch(s -> c.isAnnotatedWith(s) || c.isMetaAnnotatedWith(s));
    }

    private static String violation(JavaClass c) {
        List<JavaConstructor> visible = c.getConstructors().stream()
                .filter(k -> !k.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PRIVATE))
                .collect(Collectors.toList());
        if (visible.size() < 2) {
            return null;
        }
        long marked = visible.stream().filter(k -> k.isAnnotatedWith(AUTOWIRED)).count();
        boolean noArgFallback = c.getConstructors().stream().anyMatch(k -> k.getRawParameterTypes().isEmpty());
        if (marked == 1 || (marked == 0 && noArgFallback)) {
            return null;
        }
        return c.getName() + " (" + visible.size() + " constructors, " + marked + " @Autowired)";
    }
}
