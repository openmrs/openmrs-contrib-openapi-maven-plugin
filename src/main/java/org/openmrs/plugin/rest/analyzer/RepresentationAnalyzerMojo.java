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
import java.util.List;

@Mojo(name = "analyze-representations",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.TEST)
public class RepresentationAnalyzerMojo extends AbstractMojo {

    private static final Logger log = LoggerFactory.getLogger(RepresentationAnalyzerMojo.class);

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

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

        prepareOutputDirectory();

        runGeneratorDirectly();

        try {
            processAnalysisResults();
            bundleOutputIntoJar();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process analysis results", e);
        }

        log.info("Representation analysis completed successfully");
        log.info("==============================");
    }

    /**
     * Returns true for JARs that belong in the generator classloader (plugin tool chain).
     * Everything else — Spring, Hibernate, OpenMRS platform, Jackson — stays in the module
     * classloader, ensuring only one version of each library is ever present.
     */
    private static boolean isGeneratorJar(String fileName) {
        return fileName.startsWith("openmrs-openapi-maven-plugin-")
                || fileName.startsWith("swagger-core-")
                || fileName.startsWith("swagger-models-")
                || fileName.startsWith("swagger-annotations-")
                || fileName.startsWith("webservices.rest-omod-common-");
    }

private void prepareOutputDirectory() {
        File outputDir = new File(getOutputDirectory());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
    }

    /**
     * Runs the spec generator in-process using two layered URLClassLoaders.
     *
     * MODULE classloader — the target module's complete test classpath (its openmrs-core,
     * Spring, Hibernate, Jackson, etc.). This is the "runtime truth" for the module: every
     * platform class resolves to exactly the version the module was built against. HS 5
     * ORM/engine JARs are stripped here — Hibernate Core detects their absence via ServiceLoader
     * and silently skips the integration, which is harmless for schema generation.
     *
     * GENERATOR classloader — only the plugin's tool-chain JARs (swagger-core, the plugin
     * JAR itself, webservices.rest-omod-common). Its parent is the module classloader, so
     * Spring/OpenMRS/Hibernate/Jackson all delegate upward to the module's versions. No
     * version-matching heuristics needed; each library exists in exactly one place.
     *
     * OpenApiSpecGenerator is loaded from the generator classloader and invoked via
     * reflection to avoid class identity conflicts with the plugin's own ClassRealm.
     */
    /** Minimum openmrs-core version known to work. Earlier versions have initialization bugs. */
    private static final String MIN_CORE_VERSION = "2.6.0";

    /**
     * Scans the module classpath for openmrs-api-*.jar, extracts the version, and warns if it is
     * below {@link #MIN_CORE_VERSION}. Does not throw — lets generation proceed so the user sees
     * the actual error in context.
     */
    private void warnIfCoreVersionTooOld(List<String> moduleClasspath) {
        String detected = null;
        for (String path : moduleClasspath) {
            String fileName = new File(path).getName();
            if (fileName.startsWith("openmrs-api-") && fileName.endsWith(".jar")
                    && !fileName.contains("-tests") && !fileName.contains("-sources") && !fileName.contains("-javadoc")) {
                detected = fileName.substring("openmrs-api-".length(), fileName.length() - ".jar".length());
                break;
            }
        }
        if (detected == null) {
            log.warn("Could not detect openmrs-core version from module classpath. Minimum supported version is {}.", MIN_CORE_VERSION);
            return;
        }
        org.apache.maven.artifact.versioning.DefaultArtifactVersion detectedV =
                new org.apache.maven.artifact.versioning.DefaultArtifactVersion(detected);
        org.apache.maven.artifact.versioning.DefaultArtifactVersion minimumV =
                new org.apache.maven.artifact.versioning.DefaultArtifactVersion(MIN_CORE_VERSION);
        if (detectedV.compareTo(minimumV) < 0) {
            log.warn("==========================================================");
            log.warn("openmrs-core {} detected; minimum supported version is {}.", detected, MIN_CORE_VERSION);
            log.warn("Known failures on older versions:");
            log.warn("  < 2.4.4  NoSuchMethodError: OpenmrsUtil.getApplicationDataDirectoryAsFile()");
            log.warn("  < 2.6.0  NullPointerException in OpenmrsConfigurationFactory.getConfiguration()");
            log.warn("Generation will be attempted but may fail. Upgrade openmrs-core to {} or later.", MIN_CORE_VERSION);
            log.warn("==========================================================");
        } else {
            log.debug("openmrs-core {} — supported (minimum {}).", detected, MIN_CORE_VERSION);
        }
    }

    /**
     * Returns a semicolon-delimited list of file paths considered "owned" by this module:
     * the module's own target/classes plus any artifact in the module classpath that shares
     * the same groupId and whose artifactId starts with the parent module's artifactId prefix.
     *
     * This handles multi-module projects (e.g. webservices.rest) where resource handlers live
     * in a sibling sub-module (omod-common) rather than the omod's own target/classes.
     */
    private String buildOwnedLocations(List<String> moduleClasspath) {
        List<String> owned = new ArrayList<>();
        owned.add(project.getBuild().getOutputDirectory());

        org.apache.maven.project.MavenProject parent = project.getParent();
        String prefix = (parent != null && project.getGroupId().equals(parent.getGroupId()))
                ? parent.getArtifactId()
                : project.getArtifactId();
        String groupId = project.getGroupId();

        for (org.apache.maven.artifact.Artifact artifact : (java.util.Set<org.apache.maven.artifact.Artifact>) project.getArtifacts()) {
            if (groupId.equals(artifact.getGroupId())
                    && artifact.getArtifactId().startsWith(prefix)
                    && artifact.getFile() != null) {
                owned.add(artifact.getFile().getAbsolutePath());
                log.debug("Owned artifact: {}", artifact.getArtifactId());
            }
        }

        return String.join(";", owned);
    }

    private void runGeneratorDirectly() throws MojoExecutionException {
        URL[] pluginUrls = ((URLClassLoader) getClass().getClassLoader()).getURLs();

        List<String> moduleClasspath = ModuleClasspathBuilder.buildTargetModuleClasspath(project);

        warnIfCoreVersionTooOld(moduleClasspath);

        // Module classloader: module's test classpath.
        // Strip the old servlet-api-*.jar (pre-Servlet-3.0 artifact ID) — it lacks Servlet 3+
        // classes (FilterRegistration etc.) needed by WebComponentRegistrar at runtime. The
        // Servlet 3+ API is injected below from the plugin's own javax.servlet-api JAR.
        // NOTE: do NOT strip Hibernate Search JARs here. With a module-only classloader there is
        // no mixed-version HS scenario; each module carries exactly one HS version, and stripping
        // it breaks openmrs-api beans that reference FullTextSession / SearchSession directly.
        List<URL> moduleUrls = new ArrayList<>();
        boolean moduleHasServlet3 = false;
        for (String path : moduleClasspath) {
            String fileName = new File(path).getName();
            if (fileName.matches("servlet-api-.*\\.jar")) {
                log.debug("Skipping old servlet-api JAR from module classpath: {}", fileName);
                continue;
            }
            if (fileName.startsWith("javax.servlet-api-")) {
                moduleHasServlet3 = true;
            }
            try {
                moduleUrls.add(new File(path).toURI().toURL());
            } catch (MalformedURLException e) {
                throw new MojoExecutionException("Invalid classpath entry: " + path, e);
            }
        }

        // omodCommonJarPath: the path to the omod-common JAR whose webModuleApplicationContext.xml
        // will be loaded into Spring. MUST match the version of RestServiceImpl that gets loaded —
        // i.e., the version in the MODULE's classpath. Newer versions may inject properties (like
        // executorService) that didn't exist on the class in older versions, causing
        // NotWritablePropertyException. Search the module classpath first; fall back to the plugin
        // ClassRealm only if the module doesn't carry omod-common (unusual but possible).
        String omodCommonJarPath = null;
        for (String path : moduleClasspath) {
            String fileName = new File(path).getName();
            if (fileName.startsWith("webservices.rest-omod-common-") && !fileName.contains("-tests")) {
                omodCommonJarPath = path;
                log.debug("Found omod-common in module classpath: {}", fileName);
                break;
            }
        }

        // Generator classloader: only plugin tool-chain JARs.
        // Also collect javax.servlet-api JAR (for injection into module CL when module lacks
        // Servlet 3+), and fall back to the plugin's omod-common if the module doesn't carry it.
        List<URL> generatorUrls = new ArrayList<>();
        URL pluginServletApiUrl = null;
        String pluginOmodCommonJarPath = null;
        for (URL url : pluginUrls) {
            String path = url.getPath();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if (isGeneratorJar(fileName)) {
                generatorUrls.add(url);
                log.debug("Generator classpath: {}", fileName);
            }
            if (fileName.startsWith("javax.servlet-api-")) {
                pluginServletApiUrl = url;
            }
            if (fileName.startsWith("webservices.rest-omod-common-") && !fileName.contains("-tests")) {
                pluginOmodCommonJarPath = path;
            }
        }
        if (omodCommonJarPath == null && pluginOmodCommonJarPath != null) {
            omodCommonJarPath = pluginOmodCommonJarPath;
            log.debug("omod-common not in module classpath; falling back to plugin version");
        }

        log.info("omodCommonJarPath: {}", omodCommonJarPath != null ? omodCommonJarPath : "(not found)");

        // Owned locations: this module's compiled output plus any classpath artifact that
        // belongs to the same Maven project (same groupId, artifactId starts with the parent
        // module name). This handles multi-module projects like webservices.rest where the
        // resource handlers live in omod-common rather than the omod's own target/classes.
        String ownedLocations = buildOwnedLocations(moduleClasspath);

        // Inject Servlet 3+ API into module CL if the module doesn't already provide it.
        if (!moduleHasServlet3 && pluginServletApiUrl != null) {
            moduleUrls.add(pluginServletApiUrl);
            log.debug("Injecting Servlet 3+ API into module classloader: {}",
                    pluginServletApiUrl.getPath().substring(pluginServletApiUrl.getPath().lastIndexOf('/') + 1));
        }

        log.debug("Module classloader: {} entries (after HS 5 strip)", moduleUrls.size());
        log.debug("Generator classloader: {} entries", generatorUrls.size());

        System.setProperty("useInMemoryDatabase", "true");
        System.setProperty("java.awt.headless", "true");
        if (System.getProperty("OPENMRS_APPLICATION_DATA_DIRECTORY") == null) {
            System.setProperty("OPENMRS_APPLICATION_DATA_DIRECTORY",
                    System.getProperty("java.io.tmpdir"));
        }

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        URLClassLoader moduleCL = new URLClassLoader(
                moduleUrls.toArray(new URL[0]),
                ClassLoader.getSystemClassLoader());
        URLClassLoader generatorCL = new URLClassLoader(
                generatorUrls.toArray(new URL[0]),
                moduleCL);

        try {
            Thread.currentThread().setContextClassLoader(generatorCL);

            Class<?> generatorClass = generatorCL.loadClass(
                    "org.openmrs.plugin.rest.analyzer.OpenApiSpecGenerator");
            Object generator = generatorClass.getDeclaredConstructor().newInstance();
            generatorClass.getMethod("setup", String.class, String.class, String.class, String.class).invoke(generator,
                    ownedLocations, omodCommonJarPath != null ? omodCommonJarPath : "",
                    project.getArtifactId(), project.getBuild().getOutputDirectory());
            generatorClass.getMethod("generateOpenAPISpec", String.class, String.class).invoke(generator, getOutputDirectory(), getOutputFileName());

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new MojoExecutionException("Failed to generate OpenAPI specification", cause);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate OpenAPI specification", e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            try { generatorCL.close(); } catch (IOException e) { log.warn("Failed to close generator CL: {}", e.getMessage()); }
            try { moduleCL.close(); } catch (IOException e) { log.warn("Failed to close module CL: {}", e.getMessage()); }
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

    /**
     * Copies all generated OpenAPI files from target/openapi/ into
     * target/classes/META-INF/openapi/ so they are included in the module JAR.
     * The package phase runs after process-classes and picks up everything in target/classes/.
     */
    private void bundleOutputIntoJar() throws IOException {
        java.nio.file.Path sourceDir = new File(getOutputDirectory()).toPath();
        java.nio.file.Path targetDir = new File(
                project.getBuild().getOutputDirectory(), "META-INF/openapi").toPath();

        if (!sourceDir.toFile().exists()) {
            log.warn("OpenAPI output directory does not exist, skipping JAR bundling: {}", sourceDir);
            return;
        }

        Files.walk(sourceDir).forEach(source -> {
            try {
                java.nio.file.Path relative = sourceDir.relativize(source);
                java.nio.file.Path dest = targetDir.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy OpenAPI file into JAR: " + source, e);
            }
        });

        log.info("Bundled OpenAPI specs into JAR at META-INF/openapi/");
    }
}
