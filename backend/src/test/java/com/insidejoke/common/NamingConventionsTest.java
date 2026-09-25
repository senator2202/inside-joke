package com.insidejoke.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.ClassScanUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * The naming scheme of docs/CONVENTIONS.md: a class name tells whether it holds data or does work. Logic carries a role
 * suffix (Service, Repository, Controller, Client, Config, Handler, Listener, Utils, Exception); API data ends with Dto
 * and lives in a dto package; nothing else borrows those suffixes.
 */
class NamingConventionsTest {

    /** Every class of the application (not the tests), nested ones included. */
    private static List<Class<?>> classes() throws ClassNotFoundException {
        List<Class<?>> out = new ArrayList<>();
        for (var d : ClassScanUtils.scanner(metadata -> true).findCandidateComponents("com.insidejoke")) {
            Class<?> c = Class.forName(d.getBeanClassName());
            if (!c.isAnonymousClass() && !c.isSynthetic() && !c.getSimpleName().isEmpty() && !testCode(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Test code follows test conventions (Fake..., ...Test, ...IT) and is not checked here. */
    private static boolean testCode(Class<?> c) {
        var source = c.getProtectionDomain().getCodeSource();
        return source != null && source.getLocation().getPath().contains("test-classes");
    }

    private static String roleOfBean(Class<?> c) {
        if (AnnotatedElementUtils.hasAnnotation(c, Service.class)) {
            boolean impl = c.getSimpleName().endsWith("ServiceImpl")
                    && Arrays.stream(c.getInterfaces())
                            .anyMatch(i ->
                                    i.getSimpleName().equals(c.getSimpleName().replace("Impl", "")));
            return c.getSimpleName().endsWith("Service") || impl
                    ? null
                    : "@Service must end with Service (or ServiceImpl)";
        }
        if (AnnotatedElementUtils.hasAnnotation(c, Repository.class)) {
            return c.getSimpleName().endsWith("Repository") ? null : "@Repository must end with Repository";
        }
        if (AnnotatedElementUtils.hasAnnotation(c, ControllerAdvice.class)) {
            return c.getSimpleName().endsWith("Handler") ? null : "controller advice must end with Handler";
        }
        if (AnnotatedElementUtils.hasAnnotation(c, Controller.class)) {
            return c.getSimpleName().endsWith("Controller") ? null : "controllers must end with Controller";
        }
        if (AnnotatedElementUtils.hasAnnotation(c, Configuration.class)) {
            return c.getSimpleName().endsWith("Config") ? null : "@Configuration must end with Config";
        }
        return Stream.of("Client", "Handler", "Listener", "Repository").anyMatch(c.getSimpleName()::endsWith)
                ? null
                : "@Component must end with Client, Handler, Listener or Repository";
    }

    @Test
    void springBeansCarryTheirRole() throws Exception {
        List<String> wrong = classes().stream()
                .filter(c -> AnnotatedElementUtils.hasAnnotation(c, Component.class)
                        && !c.isAnnotationPresent(SpringBootApplication.class))
                .filter(c -> roleOfBean(c) != null)
                .map(c -> c.getName() + ": " + roleOfBean(c))
                .toList();
        assertThat(wrong).isEmpty();
    }

    @Test
    void onlyServicesAreNamedService() throws Exception {
        List<String> wrong = classes().stream()
                .filter(c -> !c.isInterface())
                .filter(c -> c.getSimpleName().endsWith("Service")
                        || c.getSimpleName().endsWith("ServiceImpl"))
                .filter(c -> !c.isAnnotationPresent(Service.class))
                .map(Class::getName)
                .toList();
        assertThat(wrong).as("named like a service but not @Service").isEmpty();
    }

    @Test
    void settingsEndWithProperties() throws Exception {
        List<String> wrong = classes().stream()
                .filter(c -> c.isAnnotationPresent(ConfigurationProperties.class))
                .filter(c -> !c.getSimpleName().endsWith("Properties"))
                .map(Class::getName)
                .toList();
        assertThat(wrong).isEmpty();
    }

    @Test
    void apiDataEndsWithDtoAndLivesInDtoPackages() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Class<?> c : classes()) {
            boolean inDto = c.getPackageName().endsWith(".dto");
            String n = c.getSimpleName();
            if (inDto && !(c.isRecord() && n.endsWith("Dto"))) {
                wrong.add(c.getName() + ": everything in a dto package is a record named ...Dto");
            }
            if (!inDto && Stream.of("Dto", "Request", "Response", "View").anyMatch(n::endsWith)) {
                wrong.add(c.getName() + ": Dto, Request, Response and View are for API data in dto packages");
            }
        }
        assertThat(wrong).isEmpty();
    }

    private static boolean staticHelper(Class<?> c) {
        if (c.isInterface() || c.isEnum() || c.isRecord() || !Modifier.isFinal(c.getModifiers())) {
            return false;
        }
        Constructor<?>[] constructors = c.getDeclaredConstructors();
        boolean privateOnly = constructors.length == 1
                && Modifier.isPrivate(constructors[0].getModifiers())
                && constructors[0].getParameterCount() == 0;
        boolean allStatic = Arrays.stream(c.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .allMatch(m -> Modifier.isStatic(m.getModifiers()));
        boolean noState = Arrays.stream(c.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .allMatch(f -> Modifier.isStatic(f.getModifiers()));
        return privateOnly
                && allStatic
                && noState
                && Arrays.stream(c.getDeclaredMethods()).anyMatch(m -> !m.isSynthetic());
    }

    @Test
    void staticHelpersAreNamedUtils() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Class<?> c : classes()) {
            if (staticHelper(c) != c.getSimpleName().endsWith("Utils")) {
                wrong.add(c.getName()
                        + (staticHelper(c)
                                ? ": only static methods, so ...Utils"
                                : ": ...Utils must hold only static methods"));
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void exceptionsAreNamedException() throws Exception {
        List<String> wrong = classes().stream()
                .filter(c ->
                        Throwable.class.isAssignableFrom(c) != c.getSimpleName().endsWith("Exception"))
                .map(Class::getName)
                .toList();
        assertThat(wrong).isEmpty();
    }
}
