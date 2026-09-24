package com.insidejoke.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Modules (the packages right under com.insidejoke) must not depend on each other in a circle: each can then be read,
 * tested and changed on its own terms. Where a lower module needs a higher one, the lower one declares a port or
 * publishes an event (docs/CONVENTIONS.md).
 */
class ModuleDependenciesTest {

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "insidejoke");
    private static final Pattern IMPORT = Pattern.compile("(?m)^import com\\.insidejoke\\.(\\w+)\\.");

    static Map<String, TreeSet<String>> graph() throws IOException {
        Map<String, TreeSet<String>> graph = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path relative = SOURCES.relativize(file);
                if (relative.getNameCount() < 2) {
                    continue;
                }
                String module = relative.getName(0).toString();
                Matcher m = IMPORT.matcher(Files.readString(file));
                while (m.find()) {
                    if (!m.group(1).equals(module)) {
                        graph.computeIfAbsent(module, k -> new TreeSet<>()).add(m.group(1));
                    }
                }
            }
        }
        return graph;
    }

    @Test
    void modulesDoNotDependOnEachOtherInACircle() throws IOException {
        Map<String, TreeSet<String>> graph = graph();
        assertThat(graph).as("the scan found the modules").containsKeys("game", "billing", "auth");
        Map<String, Integer> state = new HashMap<>();
        List<String> path = new ArrayList<>();
        for (String module : graph.keySet()) {
            List<String> cycle = findCycle(module, graph, state, path);
            assertThat(cycle).as("module cycle").isNull();
        }
    }

    private static List<String> findCycle(
            String module, Map<String, TreeSet<String>> graph, Map<String, Integer> state, List<String> path) {
        Integer s = state.get(module);
        if (s != null && s == 2) {
            return null;
        }
        if (s != null && s == 1) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(module), path.size()));
            cycle.add(module);
            return cycle;
        }
        state.put(module, 1);
        path.add(module);
        for (String next : graph.getOrDefault(module, new TreeSet<>())) {
            List<String> cycle = findCycle(next, graph, state, path);
            if (cycle != null) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        state.put(module, 2);
        return null;
    }
}
