//usr/bin/env jbang "$0" "$@" ; exit $?

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper that locates the locally installed actor-IaC JAR (built via
 * {@code mvn install}) and forwards any CLI arguments to it.
 */
class actor_iac {

    private static final String JAR_RELATIVE_PATH =
        "/.m2/repository/com/scivicslab/actor-IaC/2.9.0/actor-IaC-2.9.0.jar";

    public static void main(String[] args) throws Exception {
        File jarFile = new File(System.getProperty("user.home") + JAR_RELATIVE_PATH);

        if (!jarFile.exists()) {
            throw new IllegalStateException(
                "actor-IaC jar not found. Make sure `mvn install` has been run: "
                    + jarFile.getAbsolutePath());
        }

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(jarFile.getAbsolutePath());
        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        int exitCode = process.waitFor();
        System.exit(exitCode);
    }
}
