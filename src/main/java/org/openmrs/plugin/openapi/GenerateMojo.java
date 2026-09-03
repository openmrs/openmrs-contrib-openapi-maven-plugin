package org.openmrs.plugin.openapi;

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
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code generate} goal: OpenAPI specs for the module's REST resources and controllers, then a
 * TypeScript client package built from those specs.
 * <p>
 * The two halves were once two goals. They are one now because the second is worthless without the
 * first — it reads nothing but the files the first writes — and splitting them made every caller
 * responsible for running them in order. What the split really bought was a way to <em>skip</em>
 * the TypeScript half, and {@link #generateTypeScript} buys that for one parameter.
 * <p>
 * They still run under different classloaders; see {@link TypeScriptClientGenerator} for why the
 * ordering in {@link #execute()} is load bearing.
 */
@Mojo(name = "generate",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.TEST)
public class GenerateMojo extends AbstractMojo {

    private static final Logger log = LoggerFactory.getLogger(GenerateMojo.class);

    /**
     * The generator is loaded by name rather than as a class literal: referencing
     * {@code OpenApiSpecGenerator.class} here would resolve it in the plugin's own ClassRealm,
     * defeating the isolation the two-classloader setup exists to provide.
     * <p>
     * The package is derived from this mojo rather than hardcoded so the two stay in step if the
     * package is renamed — a hardcoded literal silently broke when it was.
     */
    private static final String GENERATOR_CLASS_NAME =
            GenerateMojo.class.getPackage().getName() + ".OpenApiSpecGenerator";

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /**
     * Whether to also emit the TypeScript client package.
     * <p>
     * Defaults to <b>on</b>, which is the opposite of how the old separate goal behaved. A module
     * that does not want the package turns it off in its own {@code <configuration>}; the default
     * has to serve the caller that cannot configure anything, and that is the common one —
     * {@code generate.sh} invokes this goal by fully-qualified coordinates against modules that
     * declare no {@code <plugin>} block at all, so it has no way to turn the flag <em>on</em>.
     */
    @Parameter
    private boolean generateTypeScript = true;

    /**
     * npm package name for the generated client. Defaults to {@code @openmrs/<artifactId>}, with
     * dots replaced by hyphens — {@code webservices.rest-omod} →
     * {@code @openmrs/webservices-rest-omod}.
     */
    @Parameter
    private String npmName;

    /**
     * Registry URL written to the package's {@code publishConfig}, for publishing somewhere other
     * than npmjs. Optional; omitted entirely when unset.
     */
    @Parameter
    private String npmRepository;

    /**
     * Specs are generated directly into the module's compiled-resources directory, so the
     * {@code package} phase (which runs after {@code process-classes}) picks them up with no
     * copy step: they ship inside the module JAR at {@code META-INF/openapi/}.
     */
    private String getOutputDirectory() {
        return project.getBuild().getOutputDirectory() + "/META-INF/openapi";
    }

    private String getOutputFileName() {
        return "openapi.json";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if ("pom".equals(project.getPackaging())) {
            log.info("Skipping OpenAPI generation for POM module: {}", project.getArtifactId());
            return;
        }

        log.info("=== OpenMRS REST Representation Analyzer ===");
        log.info("Target module: {}", project.getArtifactId());
        log.debug("Project: {}", project.getName());
        log.debug("Output directory: {}", getOutputDirectory());

        prepareOutputDirectory();

        runGeneratorDirectly();

        verifyOutput();

        // Strictly after runGeneratorDirectly() — which closes its two URLClassLoaders and restores
        // the context classloader in a finally block — because openapi-generator must run in the
        // plugin's own ClassRealm, not the one built to load module classes.
        //
        // And strictly after verifyOutput(), so the specs are reported as written even when this
        // step throws. It can: an operationId collision is fatal. Under the old two-goal split that
        // failure could not touch the specs at all, since they were already on disk from a separate
        // invocation; keeping the order preserves that.
        if (generateTypeScript) {
            new TypeScriptClientGenerator(project, new File(getOutputDirectory()).toPath(),
                    npmName, npmRepository, externalSchemaArtifacts, getLog()).generate();
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
                || fileName.startsWith("webservices.rest-omod-common-")
                // swagger-core's Json31.mapper() registers JavaTimeModule, so jsr310 is part of
                // the tool chain rather than the module's own classpath. Older modules (e.g.
                // appointments, on Jackson 2.11) never depend on it and used to fail with
                // NoClassDefFoundError before generation started. The generator classloader is
                // parent-first, so a module that does ship jsr310 still wins and keeps its own
                // Jackson stack internally consistent; this copy is only a fallback.
                || fileName.startsWith("jackson-datatype-jsr310-")
                // Same story for jackson-dataformat-yaml: swagger-core's Json31.mapper() touches
                // YAMLFactory as it builds the mapper, so it is part of the tool chain. A module
                // that does not ship it (e.g. webservices.rest on the typings branch, whose Swagger
                // removal dropped the transitive copy) failed with
                // NoClassDefFoundError: com/fasterxml/jackson/dataformat/yaml/YAMLFactory before any
                // schema was generated. Parent-first delegation keeps a module's own copy winning.
                || fileName.startsWith("jackson-dataformat-yaml-");
    }

    private void prepareOutputDirectory() {
        File outputDir = new File(getOutputDirectory());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
    }

    /**
     * Lowest openmrs-core version generation has actually been verified against.
     * <p>
     * This used to be 2.6.0, because starting Spring failed below that
     * (`NoSuchMethodError: OpenmrsUtil.getApplicationDataDirectoryAsFile()` below 2.4.4, and an
     * NPE in `OpenmrsConfigurationFactory.getConfiguration()` during Log4j init below 2.6.0).
     * Both were *startup* failures, and nothing starts Spring any more — openmrs-module-appointments
     * on core 2.4.2 generates cleanly. Anything below this is untested rather than known-broken.
     */
    private static final String LOWEST_VERIFIED_CORE_VERSION = "2.4.2";

    /**
     * Scans the module classpath for openmrs-api-*.jar, extracts the version, and notes if it is
     * below {@link #LOWEST_VERIFIED_CORE_VERSION}. Does not throw — generation may well work.
     */
    private void noteCoreVersion(List<String> moduleClasspath) {
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
            log.debug("Could not detect openmrs-core version from module classpath.");
            return;
        }
        org.apache.maven.artifact.versioning.DefaultArtifactVersion detectedV =
                new org.apache.maven.artifact.versioning.DefaultArtifactVersion(detected);
        org.apache.maven.artifact.versioning.DefaultArtifactVersion lowestVerifiedV =
                new org.apache.maven.artifact.versioning.DefaultArtifactVersion(LOWEST_VERIFIED_CORE_VERSION);
        if (detectedV.compareTo(lowestVerifiedV) < 0) {
            log.info("openmrs-core {} detected, below the lowest verified version ({}). "
                    + "Generation is expected to work — report it if it does not.",
                    detected, LOWEST_VERIFIED_CORE_VERSION);
        } else {
            log.debug("openmrs-core {} detected.", detected);
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
     * Spring/OpenMRS/Hibernate/Jackson all delegate upward to the module's versions where the
     * module has them. Because delegation is parent-first, a tool-chain JAR here only takes
     * effect when the module does not supply that library at all — see {@link #isGeneratorJar}.
     *
     * OpenApiSpecGenerator is loaded from the generator classloader and invoked via
     * reflection to avoid class identity conflicts with the plugin's own ClassRealm.
     */
    private void runGeneratorDirectly() throws MojoExecutionException {
        URL[] pluginUrls = ((URLClassLoader) getClass().getClassLoader()).getURLs();

        List<String> moduleClasspath = ModuleClasspathBuilder.buildTargetModuleClasspath(project);

        noteCoreVersion(moduleClasspath);

        // Module classloader: module's test classpath, used verbatim.
        // The old servlet-api juggling (stripping servlet-api-*.jar and injecting a Servlet 3+
        // API) is gone along with the Spring web context — nothing loads WebComponentRegistrar
        // or any other servlet-dependent bean during reflection-only generation.
        // NOTE: do NOT strip Hibernate Search JARs here. With a module-only classloader there is
        // no mixed-version HS scenario; each module carries exactly one HS version, and stripping
        // it breaks openmrs-api beans that reference FullTextSession / SearchSession directly.
        List<URL> moduleUrls = new ArrayList<>();
        for (String path : moduleClasspath) {
            try {
                moduleUrls.add(new File(path).toURI().toURL());
            } catch (MalformedURLException e) {
                throw new MojoExecutionException("Invalid classpath entry: " + path, e);
            }
        }

        // Generator classloader: only plugin tool-chain JARs.
        List<URL> generatorUrls = new ArrayList<>();
        for (URL url : pluginUrls) {
            String path = url.getPath();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if (isGeneratorJar(fileName)) {
                generatorUrls.add(url);
                log.debug("Generator classpath: {}", fileName);
            }
        }

        // Owned locations: this module's compiled output plus any classpath artifact that
        // belongs to the same Maven project (same groupId, artifactId starts with the parent
        // module name). This handles multi-module projects like webservices.rest where the
        // resource handlers live in omod-common rather than the omod's own target/classes.
        String ownedLocations = buildOwnedLocations(moduleClasspath);

        log.debug("Module classloader: {} entries", moduleUrls.size());
        log.debug("Generator classloader: {} entries", generatorUrls.size());

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

            Class<?> generatorClass = generatorCL.loadClass(GENERATOR_CLASS_NAME);
            Object generator = generatorClass.getDeclaredConstructor().newInstance();
            generatorClass.getMethod("setup", String.class, String.class, String.class)
                    .invoke(generator, ownedLocations,
                            project.getName() != null ? project.getName() : project.getArtifactId(),
                            project.getVersion());
            generatorClass.getMethod("generateOpenAPISpec", String.class, String.class).invoke(generator, getOutputDirectory(), getOutputFileName());

            // Read before the finally block closes the loaders. Map/String are system-classloader
            // types, so this crosses the realm boundary cleanly — the same reason setup() takes
            // Strings.
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> owners = (java.util.Map<String, String>)
                    generatorClass.getMethod("getExternalSchemaOwners").invoke(generator);
            externalSchemaArtifacts = toArtifactCoordinates(owners);

        } catch (ClassNotFoundException e) {
            throw new MojoExecutionException("Could not load " + GENERATOR_CLASS_NAME
                    + " from the generator classloader. The plugin JAR on the classpath is probably "
                    + "stale or built from a different package layout — rebuild with 'mvn clean install'.", e);
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

    /**
     * Schema name -> the Maven artifact of the module that defines it, for schemas this module's
     * document references but does not define. Populated by {@link #runGeneratorDirectly()}.
     */
    private java.util.Map<String, org.apache.maven.artifact.Artifact> externalSchemaArtifacts =
            java.util.Collections.emptyMap();

    /**
     * Turns the generator's "this schema came from that JAR" answer into "this schema came from
     * that Maven artifact".
     * <p>
     * The split is deliberate. Inside the isolated classloader all that is knowable is a
     * {@code CodeSource} — a path on disk. Only the mojo has the resolved dependency set that says
     * which artifact that path is, and therefore its version, which is what a generated npm
     * dependency has to declare. {@code ResolutionScope.TEST} covers {@code provided}, which is how
     * every OpenMRS module depends on every other.
     */
    private java.util.Map<String, org.apache.maven.artifact.Artifact> toArtifactCoordinates(
            java.util.Map<String, String> ownersByJarPath) {
        if (ownersByJarPath == null || ownersByJarPath.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        java.util.Map<String, org.apache.maven.artifact.Artifact> byPath = new java.util.HashMap<>();
        for (org.apache.maven.artifact.Artifact artifact
                : (java.util.Set<org.apache.maven.artifact.Artifact>) project.getArtifacts()) {
            if (artifact.getFile() != null) {
                try {
                    byPath.put(artifact.getFile().getCanonicalPath(), artifact);
                } catch (IOException e) {
                    log.debug("Could not canonicalise {}", artifact.getFile());
                }
            }
        }

        java.util.Map<String, org.apache.maven.artifact.Artifact> resolved = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, String> entry : ownersByJarPath.entrySet()) {
            org.apache.maven.artifact.Artifact artifact = byPath.get(entry.getValue());
            if (artifact != null) {
                resolved.put(entry.getKey(), artifact);
            } else {
                // A location that is on the classpath but is not a resolved dependency — the
                // module's own output, or a JAR added by hand. Nothing to depend on, so it is left
                // out rather than guessed at.
                log.debug("No artifact for {} (owner of {})", entry.getValue(), entry.getKey());
            }
        }
        return resolved;
    }

    /** Warns if generation did not produce the spec, listing whatever did land instead. */
    private void verifyOutput() {
        File expectedOutput = new File(getOutputDirectory(), getOutputFileName());
        if (expectedOutput.exists()) {
            log.info("OpenAPI specs written to {} (bundled into the module JAR)", getOutputDirectory());
            return;
        }
        log.warn("Expected output file not found: {}", expectedOutput.getAbsolutePath());
        File[] jsonFiles = new File(getOutputDirectory()).listFiles((dir, name) -> name.endsWith(".json"));
        if (jsonFiles != null && jsonFiles.length > 0) {
            log.warn("Files that were produced:");
            for (File jsonFile : jsonFiles) {
                log.warn("  - {} ({} bytes)", jsonFile.getName(), jsonFile.length());
            }
        }
    }

}
