package org.openmrs.plugin.rest.analyzer;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for building the complete runtime classpath of a target OpenMRS module.
 * This ensures the forked JVM has access to all necessary dependencies including
 * webservices.rest framework classes if the module depends on them.
 */
public class ModuleClasspathBuilder {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleClasspathBuilder.class);
    
    /**
     * Builds the complete runtime classpath for the target module including:
     * - Plugin's test JAR (containing OpenmrsOpenapiSpecGeneratorTest)
     * - Module's compiled classes
     * - Module's test classes (for OpenMRS test context)
     * - All module dependencies (including webservices.rest if present)
     * - OpenMRS platform JARs
     * 
     * Forked JVM Requirement: This classpath is used by a forked JVM process
     * to avoid ClassLoader conflicts between the Maven plugin environment and the OpenMRS 
     * runtime environment. The plugin needs to instantiate OpenMRS resources and run them
     * in a full OpenMRS test context, which requires different versions of libraries
     * (Spring, Hibernate, etc.) than what Maven uses internally.
     * 
     * @param project The target module's Maven project
     * @return List of classpath entries as absolute file paths
     * @throws RuntimeException if plugin test JAR cannot be resolved
     */
    public static List<String> buildTargetModuleClasspath(MavenProject project) {
        log.debug("Building classpath for target module: {}", project.getArtifactId());
        
        List<String> classpath = new ArrayList<>();
        
        String pluginTestJar = resolvePluginTestJar();
        classpath.add(pluginTestJar);
        log.info("Added plugin test JAR: {}", pluginTestJar);
        
        String pluginMainJar = resolvePluginMainJar();
        if (pluginMainJar != null) {
            classpath.add(pluginMainJar);
            log.info("Added plugin main JAR: {}", pluginMainJar);
        }
        
        // Add plugin's own dependencies (Swagger, JUnit Platform) from Maven repository
        List<String> pluginDependencies = resolvePluginDependencies();
        for (String dependency : pluginDependencies) {
            classpath.add(dependency);
            log.debug("Added plugin dependency: {}", dependency);
        }
        if (!pluginDependencies.isEmpty()) {
            log.info("Added {} plugin dependency JARs", pluginDependencies.size());
        }
        
        String outputDir = project.getBuild().getOutputDirectory();
        if (outputDir != null) {
            classpath.add(outputDir);
            log.debug("Added module classes: {}", outputDir);
        }
        
        String testOutputDir = project.getBuild().getTestOutputDirectory();
        if (testOutputDir != null) {
            classpath.add(testOutputDir);
            log.debug("Added module test classes: {}", testOutputDir);
        }
        
        // Dependencies are now managed through the module's own pom.xml test dependencies
        // See INTEGRATION_GUIDE.md for required dependency configuration
        log.debug("Using module's declared test dependencies for OpenMRS web and test JARs");
        
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
        log.debug("  - Plugin test JAR: 1");
        log.debug("  - Module classes: 1");
        log.debug("  - Test classes: 1"); 
        log.debug("  - Dependencies: {}", dependencyCount);
        
        return classpath;
    }
    
    /**
     * Resolves the plugin's test JAR using multiple fallback strategies.
     * 
     * @return Absolute path to the plugin test JAR
     * @throws RuntimeException if test JAR cannot be found
     */
    private static String resolvePluginTestJar() {
        
        String devTestJar = tryDevelopmentEnvironment();
        if (devTestJar != null) {
            log.debug("Using development test JAR: {}", devTestJar);
            return devTestJar;
        }
        
        String repoTestJar = tryMavenRepository();
        if (repoTestJar != null) {
            log.debug("Using repository test JAR: {}", repoTestJar);
            return repoTestJar;
        }
        
        throw new RuntimeException("Plugin test JAR not found. Please run 'mvn clean install' in the plugin directory first.");
    }
    
    /**
     * Resolves the plugin's main JAR using multiple fallback strategies.
     * 
     * @return Absolute path to the plugin main JAR, or null if not found
     */
    private static String resolvePluginMainJar() {
        
        String devMainJar = tryDevelopmentMainJar();
        if (devMainJar != null) {
            log.debug("Using development main JAR: {}", devMainJar);
            return devMainJar;
        }
        
        String repoMainJar = tryMavenRepositoryMainJar();
        if (repoMainJar != null) {
            log.debug("Using repository main JAR: {}", repoMainJar);
            return repoMainJar;
        }
        
        log.warn("Could not resolve plugin main JAR - Swagger dependencies may not be available");
        return null;
    }
    
    /**
     * Resolves the plugin's own dependencies (Swagger, JUnit Platform, etc.) from Maven repository.
     * This avoids hardcoding versions while ensuring required dependencies are available.
     * 
     * @return List of paths to plugin dependency JARs
     */
    private static List<String> resolvePluginDependencies() {
        List<String> dependencyJars = new ArrayList<>();
        String[] repoPaths = getMavenRepositoryPaths();
        
        // Plugin dependencies as declared in pom.xml (but resolved dynamically)
        String[][] dependencies = {
            // Swagger dependencies
            {"io.swagger.core.v3", "swagger-models", "2.2.15"},
            {"io.swagger.core.v3", "swagger-core", "2.2.15"},
            {"io.swagger.core.v3", "swagger-annotations", "2.2.15"},
            {"com.fasterxml.jackson.datatype", "jackson-datatype-jsr310", "2.13.4"},
            // JUnit Platform dependencies
            {"org.junit.platform", "junit-platform-console-standalone", "1.8.2"}
        };
        
        for (String repoPath : repoPaths) {
            if (repoPath == null) continue;
            
            for (String[] dep : dependencies) {
                String groupPath = dep[0].replace('.', File.separatorChar);
                String jarPath = repoPath + File.separator +
                    groupPath + File.separator + 
                    dep[1] + File.separator + 
                    dep[2] + File.separator + 
                    dep[1] + "-" + dep[2] + ".jar";
                
                File jarFile = new File(jarPath);
                if (jarFile.exists()) {
                    dependencyJars.add(jarFile.getAbsolutePath());
                    log.debug("Found plugin dependency: {}", jarFile.getName());
                }
            }
            
            if (!dependencyJars.isEmpty()) {
                break;
            }
        }
        
        return dependencyJars;
    }

    /**
     * Try to find test JAR in development environment (plugin's own target directory).
     */
    private static String tryDevelopmentEnvironment() {
        try {
            File currentDir = new File(System.getProperty("user.dir"));
            File targetDir = new File(currentDir, "target");
            
            if (targetDir.exists()) {
                File[] testJars = targetDir.listFiles((dir, name) -> 
                    name.endsWith("-tests.jar") && name.contains("openmrs-rest-analyzer")
                );
                
                if (testJars != null && testJars.length > 0) {
                    return testJars[0].getAbsolutePath();
                }
            }
            
            File parentDir = currentDir.getParentFile();
            if (parentDir != null) {
                File parentTarget = new File(parentDir, "openmrs-rest-representation-analyzer/target");
                if (parentTarget.exists()) {
                    File[] testJars = parentTarget.listFiles((dir, name) -> 
                        name.endsWith("-tests.jar") && name.contains("openmrs-rest-analyzer")
                    );
                    
                    if (testJars != null && testJars.length > 0) {
                        return testJars[0].getAbsolutePath();
                    }
                }
            }
        } catch (SecurityException e) {
            log.debug("Security restriction accessing file system for test JAR resolution: {}", e.getMessage());
        } catch (NullPointerException e) {
            log.warn("Unexpected null value during test JAR path resolution", e);
        }
        return null;
    }
    
    /**
     * Try to find test JAR in Maven repository.
     */
    private static String tryMavenRepository() {
        String[] repoPaths = getMavenRepositoryPaths();
        String version = "1.0.0-SNAPSHOT";  // TODO: Make this dynamic
        
        for (String repoPath : repoPaths) {
            if (repoPath == null) continue;
            
            String testJarPath = repoPath + File.separator +
                "org" + File.separator + 
                "openmrs" + File.separator + 
                "plugin" + File.separator + 
                "openmrs-rest-analyzer" + File.separator + 
                version + File.separator + 
                "openmrs-rest-analyzer-" + version + "-tests.jar";
            
            File testJar = new File(testJarPath);
            if (testJar.exists()) {
                return testJar.getAbsolutePath();
            }
        }
        return null;
    }
    
    /**
     * Get possible Maven repository paths for different platforms.
     */
    private static String[] getMavenRepositoryPaths() {
        List<String> paths = new ArrayList<>();
        
        // Unix/Mac standard
        String home = System.getProperty("user.home");
        if (home != null) {
            paths.add(home + File.separator + ".m2" + File.separator + "repository");
        }
        
        // Windows standard
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null) {
            paths.add(userProfile + File.separator + ".m2" + File.separator + "repository");
        }
        
        // Custom repository
        String customRepo = System.getProperty("maven.repo.local");
        if (customRepo != null) {
            paths.add(customRepo);
        }
        
        return paths.toArray(new String[0]);
    }
    
    /**
     * Try to find main JAR in development environment (plugin's own target directory).
     */
    private static String tryDevelopmentMainJar() {
        try {
            File currentDir = new File(".");
            
            File targetDir = new File(currentDir, "target");
            if (targetDir.exists()) {
                File[] mainJars = targetDir.listFiles((dir, name) -> 
                    name.endsWith(".jar") && 
                    name.contains("openmrs-rest-analyzer") && 
                    !name.endsWith("-tests.jar") &&
                    !name.contains("-sources") &&
                    !name.contains("-javadoc")
                );
                
                if (mainJars != null && mainJars.length > 0) {
                    return mainJars[0].getAbsolutePath();
                }
            }
            
            File parentDir = currentDir.getParentFile();
            if (parentDir != null) {
                File parentTarget = new File(parentDir, "openmrs-rest-representation-analyzer/target");
                if (parentTarget.exists()) {
                    File[] mainJars = parentTarget.listFiles((dir, name) -> 
                        name.endsWith(".jar") && 
                        name.contains("openmrs-rest-analyzer") && 
                        !name.endsWith("-tests.jar") &&
                        !name.contains("-sources") &&
                        !name.contains("-javadoc")
                    );
                    
                    if (mainJars != null && mainJars.length > 0) {
                        return mainJars[0].getAbsolutePath();
                    }
                }
            }
        } catch (SecurityException e) {
            log.debug("Security restriction accessing file system for main JAR resolution: {}", e.getMessage());
        } catch (NullPointerException e) {
            log.warn("Unexpected null value during main JAR path resolution", e);
        }
        return null;
    }
    
    /**
     * Try to find main JAR in Maven repository.
     */
    private static String tryMavenRepositoryMainJar() {
        String[] repoPaths = getMavenRepositoryPaths();
        String version = "1.0.0-SNAPSHOT";  // TODO: Make this dynamic
        
        for (String repoPath : repoPaths) {
            if (repoPath == null) continue;
            
            String mainJarPath = repoPath + File.separator +
                "org" + File.separator + 
                "openmrs" + File.separator + 
                "plugin" + File.separator + 
                "openmrs-rest-analyzer" + File.separator + 
                version + File.separator + 
                "openmrs-rest-analyzer-" + version + ".jar";
            
            File mainJar = new File(mainJarPath);
            if (mainJar.exists()) {
                return mainJar.getAbsolutePath();
            }
        }
        return null;
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
