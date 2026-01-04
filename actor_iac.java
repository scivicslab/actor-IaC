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
        String[] forwardedArgs = translateListCommand(args);

        File jarFile = locateJar();

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(jarFile.getAbsolutePath());
        command.addAll(Arrays.asList(forwardedArgs));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        int exitCode = process.waitFor();
        System.exit(exitCode);
    }

    private static String[] translateListCommand(String[] args) {
        if (args.length > 0 && "list".equalsIgnoreCase(args[0])) {
            List<String> translated = new ArrayList<>();
            translated.add("--list-workflows");
            for (int i = 1; i < args.length; i++) {
                translated.add(args[i]);
            }
            return translated.toArray(new String[0]);
        }
        return args;
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

        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        candidates.add(cwd.resolve("target").resolve("actor-IaC-" + VERSION + ".jar"));
        candidates.add(cwd.resolve("../actor-IaC/target/actor-IaC-" + VERSION + ".jar").normalize());
        candidates.add(cwd.resolve("../actor-IaC-w206/actor-IaC-" + VERSION + ".jar").normalize());

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
