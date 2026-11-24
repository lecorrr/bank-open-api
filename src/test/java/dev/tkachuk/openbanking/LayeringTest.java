package dev.tkachuk.openbanking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The package rules of ARCHITECTURE.md, checked on the imports of the main sources. "api" is the code generated
 * from the contract, which only the controllers' packages use.
 */
class LayeringTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java", "dev", "tkachuk", "openbanking");
    private static final String BASE_PACKAGE = "dev.tkachuk.openbanking.";
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);

    private static final Map<String, Set<String>> MAY_DEPEND_ON = Map.of(
            "common", Set.of(),
            "bankcore", Set.of("common"),
            "security", Set.of("common"),
            "consents", Set.of("common", "api", "security"),
            "accounts", Set.of("common", "bankcore", "consents", "api", "security"));

    // Which package alone may use a kind of outside access or framework: HTTP calls to bank-core, SQL, and
    // Spring Security, which the other packages see only as AuthenticatedProvider.
    private static final Map<String, String> ONLY_PACKAGE_USING = Map.of(
            "org.springframework.web.client.", "bankcore",
            "org.springframework.jdbc.", "consents",
            "org.springframework.security.", "security");

    private static List<SourceFile> sources;

    @BeforeAll
    static void readSources() throws IOException {
        assertThat(MAIN_SOURCES).as("LayeringTest reads the sources: run the tests from the project root")
                .isDirectory();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).map(SourceFile::read).toList();
        }
        assertThat(sources).as("Java sources under " + MAIN_SOURCES).isNotEmpty();
    }

    @Test
    void shouldHaveDependencyRuleForEveryPackage() {
        List<String> withoutRule = sources.stream()
                .map(SourceFile::module)
                .filter(module -> !module.isEmpty() && !MAY_DEPEND_ON.containsKey(module))
                .distinct()
                .toList();

        assertThat(withoutRule).as("new packages need a line in MAY_DEPEND_ON and in ARCHITECTURE.md").isEmpty();
    }

    @Test
    void shouldDependOnlyInAllowedDirection() {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : sources) {
            Set<String> allowed = MAY_DEPEND_ON.getOrDefault(source.module(), Set.of());
            for (String imported : source.imports()) {
                String target = moduleOf(imported);
                if (target == null || target.equals(source.module())) {
                    continue;
                }
                if (!source.module().isEmpty() && !allowed.contains(target)) {
                    violations.add(source.relativePath() + " imports " + imported);
                }
            }
        }

        assertThat(violations).as("imports against the dependency direction").isEmpty();
    }

    @Test
    void shouldKeepHttpClientsSqlAndSecurityInTheirPackages() {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : sources) {
            for (String imported : source.imports()) {
                ONLY_PACKAGE_USING.forEach((prefix, owner) -> {
                    if (imported.startsWith(prefix) && !source.module().equals(owner)) {
                        violations.add(source.relativePath() + " imports " + imported);
                    }
                });
            }
        }

        assertThat(violations).as("HTTP clients, SQL or Spring Security outside their packages").isEmpty();
    }

    private static String moduleOf(String imported) {
        if (!imported.startsWith(BASE_PACKAGE)) {
            return null;
        }
        String rest = imported.substring(BASE_PACKAGE.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? "" : rest.substring(0, dot);
    }

    private record SourceFile(String relativePath, String module, List<String> imports) {

        static SourceFile read(Path path) {
            String relativePath = MAIN_SOURCES.relativize(path).toString().replace('\\', '/');
            int slash = relativePath.indexOf('/');
            String module = slash < 0 ? "" : relativePath.substring(0, slash);
            try {
                Matcher matcher = IMPORT.matcher(Files.readString(path));
                List<String> imports = new ArrayList<>();
                while (matcher.find()) {
                    imports.add(matcher.group(1));
                }
                return new SourceFile(relativePath, module, List.copyOf(imports));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + path, e);
            }
        }
    }
}
