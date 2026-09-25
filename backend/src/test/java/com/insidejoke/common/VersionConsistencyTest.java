package com.insidejoke.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * One version for the whole service (docs/VERSIONING.md): the Maven project, the frontend package and the latest
 * released section of CHANGELOG.md must agree, or the build fails.
 */
class VersionConsistencyTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file));
    }

    private static String first(Pattern pattern, String text, String what) {
        Matcher m = pattern.matcher(text);
        assertThat(m.find()).as(what).isTrue();
        return m.group(1);
    }

    @Test
    void mavenFrontendAndChangelogShareOneVersion() throws IOException {
        String maven = first(
                Pattern.compile("<artifactId>inside-joke</artifactId>\\s*<version>([^<]+)</version>"),
                read("pom.xml"),
                "version in pom.xml");
        String npm = first(
                Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\""),
                read("frontend/package.json"),
                "version in package.json");
        String changelog = first(
                Pattern.compile("(?m)^## \\[(\\d[^]]*)] — \\d{4}-\\d{2}-\\d{2}$"),
                read("CHANGELOG.md"),
                "a released version in CHANGELOG.md");

        assertThat(maven).matches(SEMVER);
        assertThat(maven).as("release builds carry no -SNAPSHOT").doesNotEndWith("-SNAPSHOT");
        assertThat(npm).isEqualTo(maven);
        assertThat(changelog)
                .as("the newest CHANGELOG release is the project version")
                .isEqualTo(maven);
        for (String module : new String[] {"backend/pom.xml", "frontend/pom.xml"}) {
            String parent = first(
                    Pattern.compile("<parent>[\\s\\S]*?<version>([^<]+)</version>"),
                    read(module),
                    "parent in " + module);
            assertThat(parent).as(module).isEqualTo(maven);
        }
    }

    @Test
    void theChangelogFollowsKeepAChangelog() throws IOException {
        String changelog = read("CHANGELOG.md");
        assertThat(changelog).startsWith("# Changelog").contains("## [Unreleased]");
        assertThat(changelog.indexOf("## [Unreleased]"))
                .as("Unreleased comes first")
                .isLessThan(changelog.indexOf(
                        "## [" + first(Pattern.compile("(?m)^## \\[(\\d[^]]*)]"), changelog, "release") + "]"));
        Matcher headings = Pattern.compile("(?m)^### (.+)$").matcher(changelog);
        while (headings.find()) {
            assertThat(headings.group(1))
                    .as("section heading")
                    .isIn("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security");
        }
    }
}
