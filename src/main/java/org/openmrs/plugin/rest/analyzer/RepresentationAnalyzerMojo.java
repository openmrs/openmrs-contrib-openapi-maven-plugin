package org.openmrs.plugin.rest.analyzer;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Mojo(name = "analyze-representations",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.TEST)
public class RepresentationAnalyzerMojo extends AbstractMojo {

    private static final Logger log = LoggerFactory.getLogger(RepresentationAnalyzerMojo.class);

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "2.4.x", property = "openmrsVersion")
    private String openmrsVersion;

    @Parameter(property = "scanPackages")
    private List<String> scanPackages;

    @Parameter(property = "autoDetectResources", defaultValue = "true")
    private boolean autoDetectResources;

    private String getOutputDirectory() {
        return project.getBuild().getDirectory() + "/openapi";
    }

    private String getOutputFileName() {
        return "openapi.json";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if ("pom".equals(project.getPackaging())) {
            log.info("Skipping analyze-representations for POM module: {}", project.getArtifactId());
            return;
        }

        log.info("=== OpenMRS REST Representation Analyzer ===");
        log.info("Target module: {}", project.getArtifactId());
        log.debug("Project: {}", project.getName());
        log.debug("Output directory: {}", getOutputDirectory());

        prepareScanPackages();
        prepareOutputDirectory();

        log.info("Generating OpenAPI specification for OpenMRS version: {}", openmrsVersion);
        runGeneratorDirectly();

        try {
            processAnalysisResults();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process analysis results", e);
        }

        log.info("Representation analysis completed successfully");
        log.info("==============================");
    }

    private void prepareScanPackages() {
        if (autoDetectResources && (scanPackages == null || scanPackages.isEmpty())) {
            scanPackages = ModuleClasspathBuilder.detectResourcePackages(project);
            log.info("Auto-detected resource packages: {}", scanPackages);
        } else if (scanPackages != null && !scanPackages.isEmpty()) {
            log.info("Using configured scan packages: {}", scanPackages);
        } else {
            log.warn("No scan packages specified and auto-detection disabled. May not find resources.");
            scanPackages = new ArrayList<>();
        }

        File outputDir = new File(getOutputDirectory());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
    }

    private void prepareOutputDirectory() {
        File outputDir = new File(getOutputDirectory());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
    }

    /**
     * Runs the spec generator in-process using a fully isolated URLClassLoader.
     *
     * To avoid classloader conflicts (e.g. OpenMRS's Class.forName() calls using the
     * plugin ClassRealm which lacks module-specific JARs like legacyui), we build one
     * flat classpath that combines:
     *   1. All JARs from the plugin's own ClassRealm (OpenMRS platform, Swagger, Spring, etc.)
     *   2. The target module's compiled classes and test artifact JARs
     *
     * This isolated URLClassLoader uses the JDK platform classloader as parent (not the
     * Maven plugin ClassRealm), so all OpenMRS/Spring class loading happens within the
     * isolated loader — mirroring what the forked JVM did, but in-process.
     *
     * The generator is invoked via reflection to avoid class identity conflicts
     * between the isolated loader and the plugin ClassRealm.
     */
    private void runGeneratorDirectly() throws MojoExecutionException {
        // 1. Collect plugin ClassRealm URLs (OpenMRS platform, Swagger, Spring, etc.)
        URL[] pluginUrls = ((URLClassLoader) getClass().getClassLoader()).getURLs();

        // 2. Collect target module classpath entries
        List<String> moduleClasspath = ModuleClasspathBuilder.buildTargetModuleClasspath(project);
        URL[] moduleUrls = new URL[moduleClasspath.size()];
        for (int i = 0; i < moduleClasspath.size(); i++) {
            try {
                moduleUrls[i] = new File(moduleClasspath.get(i)).toURI().toURL();
            } catch (MalformedURLException e) {
                throw new MojoExecutionException("Invalid classpath entry: " + moduleClasspath.get(i), e);
            }
        }

        // 3. Combine: plugin JARs first (their versions take precedence), then module deps.
        // Deduplicate by filename to prevent the same JAR from being loaded twice
        // (e.g. omod-common.jar appears in both plugin ClassRealm and module test artifacts).
        Set<String> pluginFileNames = new java.util.HashSet<>();
        for (URL url : pluginUrls) {
            String path = url.getPath();
            pluginFileNames.add(path.substring(path.lastIndexOf('/') + 1));
        }
        List<URL> allUrls = new ArrayList<>(Arrays.asList(pluginUrls));
        for (URL moduleUrl : moduleUrls) {
            String path = moduleUrl.getPath();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if (!pluginFileNames.contains(fileName)) {
                allUrls.add(moduleUrl);
            } else {
                log.debug("Skipping duplicate module URL (already in plugin ClassRealm): {}", fileName);
            }
        }

        log.debug("Isolated URLClassLoader: {} plugin URLs + {} module URLs",
                pluginUrls.length, moduleUrls.length);

        // 4. Set system properties (previously passed as -D flags to the forked JVM)
        System.setProperty("useInMemoryDatabase", "true");
        System.setProperty("databaseUrl", "jdbc:h2:mem:openmrs;DB_CLOSE_DELAY=-1");
        System.setProperty("databaseDriver", "org.h2.Driver");
        System.setProperty("databaseUsername", "sa");
        System.setProperty("databasePassword", "");
        System.setProperty("java.awt.headless", "true");
        System.setProperty("target.module.groupId", project.getGroupId());
        System.setProperty("target.module.artifactId", project.getArtifactId());
        System.setProperty("target.module.version", project.getVersion());
        System.setProperty("target.module.packages", String.join(",", scanPackages));
        System.setProperty("target.module.classesDir", project.getBuild().getOutputDirectory());
        System.setProperty("analysisOutputDir", getOutputDirectory());
        System.setProperty("analysisOutputFile", getOutputFileName());
        System.setProperty("openmrs.version", openmrsVersion);

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        // Use the system classloader as parent so JDK platform modules (java.sql, etc.) are
        // accessible. This is Java 8 compatible and in Java 9+ it chains through the platform
        // classloader, which provides javax.sql, javax.xml, etc. without exposing Maven internals.
        URLClassLoader isolatedClassLoader = new URLClassLoader(
                allUrls.toArray(new URL[0]),
                ClassLoader.getSystemClassLoader());

        try {
            Thread.currentThread().setContextClassLoader(isolatedClassLoader);

            // Load and invoke via reflection to avoid class identity conflicts
            Class<?> generatorClass = isolatedClassLoader.loadClass(
                    "org.openmrs.plugin.rest.analyzer.OpenApiSpecGenerator");
            Object generator = generatorClass.getDeclaredConstructor().newInstance();
            generatorClass.getMethod("setup").invoke(generator);
            generatorClass.getMethod("generateOpenAPISpec").invoke(generator);

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new MojoExecutionException("Failed to generate OpenAPI specification", cause);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate OpenAPI specification", e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            try {
                isolatedClassLoader.close();
            } catch (IOException e) {
                log.warn("Failed to close isolated URLClassLoader: {}", e.getMessage());
            }
        }
    }

    private void processAnalysisResults() throws IOException {
        File expectedOutput = new File(getOutputDirectory(), getOutputFileName());

        if (!expectedOutput.exists()) {
            log.warn("Expected output file not found: {}", expectedOutput.getAbsolutePath());

            File targetDir = new File(getOutputDirectory());
            File[] jsonFiles = targetDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (jsonFiles != null && jsonFiles.length > 0) {
                log.info("Found alternative output files:");
                for (File jsonFile : jsonFiles) {
                    log.info("  - {} ({} bytes)", jsonFile.getName(), jsonFile.length());
                }
            }
            return;
        }

        String content = new String(Files.readAllBytes(expectedOutput.toPath()));
        log.debug("=== Analysis Results Summary ===");
        log.debug("Analysis output: {}", expectedOutput.getAbsolutePath());
        log.debug("Output size: {} characters", content.length());

        if (content.contains("\"resourceCount\"") || content.contains("\"resources\"")) {
            log.debug("Resource analysis completed successfully");
        }

        File finalOutputFile = new File(getOutputDirectory(), getOutputFileName());
        Files.copy(expectedOutput.toPath(), finalOutputFile.toPath(),
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.debug("Final output: {}", finalOutputFile.getAbsolutePath());

        log.debug("==============================");
    }
}
