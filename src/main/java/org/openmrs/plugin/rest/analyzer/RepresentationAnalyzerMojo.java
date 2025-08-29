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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Mojo(name = "analyze-representations", 
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.TEST)
public class RepresentationAnalyzerMojo extends AbstractMojo {
    
    private static final Logger log = LoggerFactory.getLogger(RepresentationAnalyzerMojo.class);

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "300", property = "timeoutSeconds")
    private int timeoutSeconds;

    @Parameter(defaultValue = "2.4.x", property = "openmrsVersion")
    private String openmrsVersion;
    
    @Parameter(property = "scanPackages")
    private List<String> scanPackages;
    
    @Parameter(property = "autoDetectResources", defaultValue = "true")
    private boolean autoDetectResources;

    /**
     * Gets the hardcoded output directory for OpenAPI specifications.
     * Always uses target/openapi for consistency and predictability.
     * 
     * @return the output directory path
     */
    private String getOutputDirectory() {
        return project.getBuild().getDirectory() + "/openapi";
    }

    /**
     * Gets the hardcoded output file name for OpenAPI specifications.
     * Always uses openapi.json for consistency and predictability.
     * 
     * @return the output file name
     */
    private String getOutputFileName() {
        return "openapi.json";
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        
        log.info("=== OpenMRS REST Representation Analyzer ===");
        log.info("Target module: {}", project.getArtifactId());
        log.debug("Project: {}", project.getName());
        log.debug("Output directory: {}", getOutputDirectory());
        
        // Prepare configuration
        prepareScanPackages();
        prepareOutputDirectory();
        
        // Execute single-version generation
        try {
            log.info("Generating OpenAPI specification for OpenMRS version: {}", openmrsVersion);
            
            int exitCode = runTestInForkedProcess(openmrsVersion, getOutputFileName());
            
            if (exitCode != 0) {
                throw new MojoExecutionException("Test execution failed with exit code: " + exitCode);
            }
            
            processAnalysisResults();
            
            log.info("Representation analysis completed successfully");
            log.info("=============================="); 
        } catch (IOException | InterruptedException e) {
            log.error("Process execution error: {}", e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new MojoExecutionException("Failed to execute analysis process", e);
        }
    }

    /**
     * Validates the plugin configuration and reports any issues.
     */
    /**
     * Prepares scan packages using auto-detection if needed.
     */
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

    /**
     * Creates output directory if it doesn't exist.
     */
    private void prepareOutputDirectory() {
        File outputDir = new File(getOutputDirectory());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
    }

    /**
     * Determines which versions to generate specs for.
     */
    /**
     * Runs the analysis in a forked JVM process to avoid ClassLoader conflicts.
     * This is essential because the plugin needs to load OpenMRS classes that may
     * conflict with Maven's runtime environment.
     */
    private int runTestInForkedProcess(String targetVersion, String targetOutputFile) throws IOException, InterruptedException {
        
        List<String> classpath = ModuleClasspathBuilder.buildTargetModuleClasspath(project);
        String classpathString = String.join(File.pathSeparator, classpath);
        
        log.debug("Forked process will use {} classpath entries for module: {}", 
                classpath.size(), project.getArtifactId());
        
        List<String> command = new ArrayList<>();
        command.add(getJavaExecutable());
        command.add("-cp");
        command.add(classpathString);
        
        command.add("-DdatabaseUrl=jdbc:h2:mem:openmrs;DB_CLOSE_DELAY=-1");
        command.add("-DdatabaseDriver=org.h2.Driver");
        command.add("-DuseInMemoryDatabase=true");
        command.add("-DdatabaseUsername=sa");
        command.add("-DdatabasePassword=");
        command.add("-Djava.awt.headless=true");
        
        command.add("-Dtarget.module.groupId=" + project.getGroupId());
        command.add("-Dtarget.module.artifactId=" + project.getArtifactId());
        command.add("-Dtarget.module.version=" + project.getVersion());
        command.add("-Dtarget.module.packages=" + String.join(",", scanPackages));
        command.add("-Dtarget.module.classesDir=" + project.getBuild().getOutputDirectory());
        
        command.add("-DanalysisOutputDir=" + getOutputDirectory());
        command.add("-DanalysisOutputFile=" + targetOutputFile);
        command.add("-Dopenmrs.version=" + targetVersion);
        
        command.add("org.junit.platform.console.ConsoleLauncher");
        command.add("--select-class");
        command.add("org.openmrs.plugin.rest.analyzer.test.OpenmrsOpenapiSpecGeneratorTest");
        
        log.info("Executing analysis for module: {} with packages: {}", 
                project.getArtifactId(), scanPackages);
        log.debug("Executing command: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        pb.directory(project.getBasedir());
        
        Process process = pb.start();
        
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Test execution timed out after " + timeoutSeconds + " seconds");
        }
        
        return process.exitValue();
    }
    
    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            return "java";
        }
        
        File javaExe = new File(javaHome, "bin/java");
        if (javaExe.exists()) {
            return javaExe.getAbsolutePath();
        }
        
        javaExe = new File(javaHome, "bin/java.exe");
        if (javaExe.exists()) {
            return javaExe.getAbsolutePath();
        }
        
        return "java";
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
        
        if (content.contains("\"resourceCount\"")) {
            log.debug("Resource analysis completed successfully");
        } else if (content.contains("\"resources\"")) {
            log.debug("Resource analysis completed successfully");
        }
        
        File finalOutputFile = new File(getOutputDirectory(), getOutputFileName());
        Files.copy(expectedOutput.toPath(), finalOutputFile.toPath(), 
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.debug("Final output: {}", finalOutputFile.getAbsolutePath());
        
        log.debug("==============================");
    }
    
}
