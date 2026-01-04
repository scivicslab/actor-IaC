//usr/bin/env jbang "$0" "$@" ; exit $?

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thin wrapper that locates a locally built actor-IaC JAR and forwards CLI
 * arguments to it. It checks the Maven local repository first, then falls back
 * to common build locations under the current workspace. Users can override
 * detection by setting the ACTOR_IAC_JAR environment variable.
 */
class actor_iac {

    private static final String VERSION = "2.9.0";

    public static void main(String[] args) throws Exception {
        File jarFile = locateJar();

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(jarFile.getAbsolutePath());
        command.addAll(Arrays.asList(args));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        int exitCode = process.waitFor();
        System.exit(exitCode);
    }

    private static File locateJar() {
        List<Path> candidates = new ArrayList<>();

        String envPath = System.getenv("ACTOR_IAC_JAR");
        if (envPath != null && !envPath.isBlank()) {
            candidates.add(Paths.get(envPath));
        }

        candidates.add(Paths.get(System.getProperty("user.home"),
                                 ".m2", "repository", "com", "scivicslab", "actor-IaC",
                                 VERSION, "actor-IaC-" + VERSION + ".jar"));

        for (Path candidate : candidates) {
            File file = candidate.toFile();
            if (file.exists()) {
                return file;
            }
        }

        String message = "actor-IaC jar not found.\nChecked locations:\n"
            + candidates.stream()
                        .map(Path::toString)
                        .collect(Collectors.joining("\n - ", " - ", ""))
            + "\nRun `mvn install` (or `mvn -pl actor-IaC package`) "
            + "or set ACTOR_IAC_JAR to the jar path.";
        throw new IllegalStateException(message);
    }
}
