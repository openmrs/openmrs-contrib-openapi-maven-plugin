package org.openmrs.plugin.rest.analyzer;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for building the target module's runtime classpath entries.
 * These entries are loaded into a URLClassLoader (with the plugin's classloader as parent)
 * so that the target module's compiled classes are visible to the generator at runtime.
 * OpenMRS platform JARs and the plugin's own classes are available via the parent classloader.
 */
public class ModuleClasspathBuilder {

    private static final Logger log = LoggerFactory.getLogger(ModuleClasspathBuilder.class);

    /**
     * Builds the classpath for the target module's compiled classes and test artifacts.
     * The returned entries are used to construct a URLClassLoader for the analysis run.
     *
     * @param project The target module's Maven project
     * @return List of classpath entries as absolute file paths
     */
    public static List<String> buildTargetModuleClasspath(MavenProject project) {
        log.debug("Building classpath for target module: {}", project.getArtifactId());

        List<String> classpath = new ArrayList<>();

        // Add target module's own compiled classes
        String outputDir = project.getBuild().getOutputDirectory();
        if (outputDir != null) {
            classpath.add(outputDir);
            log.debug("Added module classes: {}", outputDir);
        }

        // Add target module's test output classes
        String testOutputDir = project.getBuild().getTestOutputDirectory();
        if (testOutputDir != null) {
            classpath.add(testOutputDir);
            log.debug("Added module test classes: {}", testOutputDir);
        }

        // Add target module's resolved test artifacts (transitive dependencies).
        int dependencyCount = 0;
        for (Object artifactObj : project.getTestArtifacts()) {
            Artifact artifact = (Artifact) artifactObj;
            File file = artifact.getFile();

            if (file != null && file.exists()) {
                classpath.add(file.getAbsolutePath());
                dependencyCount++;

                if (isImportantDependency(artifact)) {
                    log.debug("Added important dependency: {}:{}",
                            artifact.getGroupId(), artifact.getArtifactId());
                }
            }
        }

        log.info("Built classpath with {} entries for module: {}",
                classpath.size(), project.getArtifactId());
        log.debug("  - Module classes: 1");
        log.debug("  - Test classes: 1");
        log.debug("  - Dependencies: {}", dependencyCount);

        return classpath;
    }

    /**
     * Checks if an artifact is an important dependency worth logging.
     */
    private static boolean isImportantDependency(Artifact artifact) {
        String groupId = artifact.getGroupId();
        String artifactId = artifact.getArtifactId();

        return groupId.contains("openmrs") ||
               artifactId.contains("webservices") ||
               artifactId.contains("rest") ||
               groupId.contains("springframework");
    }

    /**
     * Extracts the module name from the artifact ID.
     * Examples:
     * - "openmrs-module-queue" -> "queue"
     * - "queue-omod" -> "queue"
     * - "webservices.rest-omod-2.4" -> "webservices.rest"
     * - "webservices.rest" -> "webservices.rest"
     */
    public static String extractModuleName(String artifactId) {
        if (artifactId.startsWith("openmrs-module-")) {
            return artifactId.substring("openmrs-module-".length());
        }

        if (artifactId.endsWith("-omod")) {
            return artifactId.substring(0, artifactId.length() - "-omod".length());
        }

        if (artifactId.startsWith("webservices.rest-omod")) {
            return "webservices.rest";
        }

        return artifactId;
    }

    /**
     * Auto-detects likely resource package patterns for an OpenMRS module.
     */
    public static List<String> detectResourcePackages(MavenProject project) {
        String moduleName = extractModuleName(project.getArtifactId());
        String groupId = project.getGroupId();

        List<String> packages = new ArrayList<>();

        if (groupId.equals("org.openmrs.module")) {
            packages.add("org.openmrs.module." + moduleName + ".web.resources");
            packages.add("org.openmrs.module." + moduleName + ".web.resource");
            packages.add("org.openmrs.module." + moduleName + ".rest.resources");
        }

        if (moduleName.equals("webservices.rest")) {
            packages.add("org.openmrs.module.webservices.rest.web.v1_0.resource");
            packages.add("org.openmrs.module.webservices.rest.web.v2_0.resource");
        }

        packages.add(groupId + "." + moduleName + ".web.resources");
        packages.add(groupId + ".web.resources");

        log.debug("Auto-detected resource packages for {}: {}", project.getArtifactId(), packages);
        return packages;
    }
}
