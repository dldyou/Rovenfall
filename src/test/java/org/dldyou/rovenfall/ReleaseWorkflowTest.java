package org.dldyou.rovenfall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReleaseWorkflowTest {
    private static final Path WORKFLOW = findWorkflow();

    @Test
    void manualRunBuildsReleaseCandidateWithoutPublishing() throws IOException {
        String workflow = Files.readString(WORKFLOW);
        Map<String, WorkflowStep> steps = steps(workflow);
        WorkflowStep upload = step(steps, "Upload release-candidate assets");
        WorkflowStep publish = step(steps, "Create or update GitHub Release");

        assertTrue(workflow.contains("workflow_dispatch:"));
        assertTrue(step(steps, "Build and run required tests").run()
                .startsWith("./gradlew clean build recoveryRehearsal"));
        assertTrue(step(steps, "Inspect distributable JAR").run()
                .startsWith("./gradlew inspectReleaseJar --warning-mode all"));
        assertTrue(workflow.indexOf("- name: Inspect distributable JAR")
                < workflow.indexOf("- name: Prepare release assets"));
        assertEquals("github.event_name == 'workflow_dispatch'", upload.condition());
        assertEquals("actions/upload-artifact@v7", upload.uses());
        assertEquals("github.event_name == 'push'", publish.condition());
        assertTrue(publish.run().contains("gh release create"));
        assertTrue(steps.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("Create or update GitHub Release"))
                .noneMatch(entry -> entry.getValue().run().contains("gh release")));
    }

    @Test
    void tagReleasePublishesOnlyVersionedJarAndChecksum() throws IOException {
        String workflow = Files.readString(WORKFLOW);
        Map<String, WorkflowStep> steps = steps(workflow);
        WorkflowStep validation = step(steps, "Validate release version");
        WorkflowStep assets = step(steps, "Prepare release assets");
        WorkflowStep publish = step(steps, "Create or update GitHub Release");

        assertTrue(validation.run().contains("git cat-file -t \"${tag}\""));
        assertTrue(validation.run().contains("git merge-base --is-ancestor"));
        assertTrue(assets.run().contains(
                "jar=\"build/libs/rovenfall-${{ steps.version.outputs.version }}.jar\""));
        assertTrue(assets.run().contains("sha256sum \"${jar_name}\""));
        assertTrue(publish.run().contains(
                "gh release create \"${tag}\" \"${jar}\" \"${checksum}\""));
        assertFalse(workflow.contains("build/moddev"));
        assertFalse(workflow.contains("build/classes"));
    }

    private static WorkflowStep step(Map<String, WorkflowStep> steps, String name) {
        assertTrue(steps.containsKey(name), "Missing workflow step: " + name);
        return steps.get(name);
    }

    private static Map<String, WorkflowStep> steps(String workflow) {
        String[] lines = workflow.split("\\R", -1);
        Map<String, WorkflowStep> steps = new LinkedHashMap<>();
        String name = null;
        String condition = "";
        String uses = "";
        String run = "";
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.startsWith("      - name: ")) {
                if (name != null) {
                    steps.put(name, new WorkflowStep(condition, uses, run));
                }
                name = line.substring("      - name: ".length());
                condition = "";
                uses = "";
                run = "";
            } else if (name != null && line.startsWith("        if: ")) {
                condition = line.substring("        if: ".length());
            } else if (name != null && line.startsWith("        uses: ")) {
                uses = line.substring("        uses: ".length());
            } else if (name != null && line.startsWith("        run: ")) {
                String value = line.substring("        run: ".length());
                if (!value.equals("|")) {
                    run = value;
                    continue;
                }
                StringBuilder block = new StringBuilder();
                while (++index < lines.length && (lines[index].isBlank() || lines[index].startsWith("          "))) {
                    block.append(lines[index].length() >= 10 ? lines[index].substring(10) : "")
                            .append('\n');
                }
                index--;
                run = block.toString();
            }
        }
        if (name != null) {
            steps.put(name, new WorkflowStep(condition, uses, run));
        }
        return steps;
    }

    private static Path findWorkflow() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(Path.of(".github", "workflows", "release.yml"));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not locate .github/workflows/release.yml");
    }

    private record WorkflowStep(String condition, String uses, String run) {
    }
}
