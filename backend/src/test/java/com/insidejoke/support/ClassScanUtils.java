package com.insidejoke.support;

import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.ClassMetadata;

/** Classpath scans of the application's own classes, for tests that check conventions across all of them. */
public final class ClassScanUtils {

    private ClassScanUtils() {}

    /** A scanner that takes every class whose metadata passes {@code filter}, not only Spring components. */
    public static ClassPathScanningCandidateComponentProvider scanner(Predicate<ClassMetadata> filter) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(@NonNull AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter((reader, factory) -> filter.test(reader.getClassMetadata()));
        return scanner;
    }
}
