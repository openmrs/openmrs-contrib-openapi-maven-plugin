package org.openmrs.plugin.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a publishable TypeScript npm package from the module's already-generated OpenAPI specs.
 *
 * <h2>Controllers and resources</h2>
 *
 * The package covers both the module's {@code @Controller} endpoints and its REST
 * <em>resources</em>. A resource is the harder half: its GET response shape depends on the
 * {@code ?v=} representation, which the spec expresses as an {@code anyOf} over {@code Get_ref} /
 * {@code Get_default} / {@code Get_full} / {@code Get_custom}, and openapi-generator flattens such a
 * union into a single merged interface — a client typed that way would claim every representation
 * returns every field.
 * <p>
 * The concrete per-representation interfaces <em>are</em> generated, so what the stock generator
 * cannot express is only the signature that selects between them.
 * {@link #overloadRepresentationReturns} adds it afterwards, as TypeScript overloads:
 *
 * <pre>
 * const p = await patients.retrieve({ uuid, v: 'full' });   // PatientGetFull
 * const l = await patients.getAll({ v: 'ref' });            // results: PatientGetRef[]
 * </pre>
 *
 * Resource methods are named after the {@code MainResourceController} /
 * {@code MainSubResourceController} methods that serve them — see {@link DispatchControllerNames} —
 * and the class after the resource, so a caller writes {@code new PatientResource(config)}.
 *
 * <h2>Not a mojo, and it must run in the plugin's own ClassRealm</h2>
 *
 * This is the last step of the {@code generate} goal, not a goal of its own — see
 * {@link GenerateMojo}. It is a plain collaborator rather than inlined code because the two halves
 * have nothing in common but their order: {@code generate} executes the module's resource handlers
 * by reflection and needs the isolated two-classloader setup, while this is a pure JSON-to-text
 * transform over the files that step just wrote.
 * <p>
 * That difference is load-bearing. {@link GenerateMojo} calls this <b>after</b> its generator
 * classloaders are closed and the context classloader is restored, so openapi-generator runs in
 * the plugin's own realm. Running it inside that block would load it under the classloader built
 * for module classes.
 * <p>
 * Consequently, none of the openapi-generator artifacts may be listed in
 * {@code GenerateMojo.isGeneratorJar()}: they have no business in the classloader that loads
 * module classes.
 *
 * <h2>What is published</h2>
 *
 * TypeScript sources plus the npm scaffolding openapi-generator emits — {@code package.json} with
 * {@code build} and {@code prepare} scripts, CommonJS and ESM {@code tsconfig}s, {@code .npmignore}.
 * The Maven build stays free of Node: nothing here runs {@code npm}. {@code package.json} points
 * {@code main}/{@code typings} at {@code dist/}, and its {@code prepare} script compiles, so
 * {@code npm publish} (and installing straight from git) produces the compiled artifact without
 * this build ever needing a toolchain.
 */
class TypeScriptClientGenerator {

    /** npm scope the generated packages are published under. */
    private static final String NPM_SCOPE = "@openmrs";

    /**
     * {@code typescript-fetch} targets the platform {@code fetch} that OpenMRS frontends already
     * use and pulls in no HTTP client dependency. Not configurable: every other generator would
     * need its own {@code package.json} rewrite and its own verification that the output compiles.
     */
    private static final String GENERATOR_NAME = "typescript-fetch";

    /**
     * Always replace the {@code {version}} path variable with the single value the spec permits,
     * so the generated client takes no version argument. See
     * {@code TypeScriptSpecAssembler.inlineVersionSegment} — the value is read from the parameter's
     * own enum, so a spec that ever admits a second version is left alone automatically. That makes
     * the switch redundant.
     */
    private static final boolean INLINE_VERSION_SEGMENT = true;

    private final MavenProject project;
    private final Log log;

    /** Where {@code generate} put the specs: the module's compiled-resources tree. */
    private final Path specDirectory;

    /** Root of the generated npm package, always {@code target/generated-typescript}. */
    private final File outputDirectory;

    /** From {@code <npmName>}, or null to derive it from the artifactId. */
    private final String npmName;

    /** From {@code <npmRepository>}: registry URL written to {@code publishConfig}. */
    private final String npmRepository;

    /** Schema name -> the Maven artifact of the module that defines it. @see #externalPackages */
    private final Map<String, org.apache.maven.artifact.Artifact> externalSchemaArtifacts;

    TypeScriptClientGenerator(MavenProject project, Path specDirectory, String npmName,
            String npmRepository,
            Map<String, org.apache.maven.artifact.Artifact> externalSchemaArtifacts, Log log) {
        this.project = project;
        this.specDirectory = specDirectory;
        this.outputDirectory = new File(project.getBuild().getDirectory(), "generated-typescript");
        this.npmName = npmName;
        this.npmRepository = npmRepository;
        this.externalSchemaArtifacts = externalSchemaArtifacts;
        this.log = log;
    }

    void generate() throws MojoExecutionException, MojoFailureException {
        Path controllersDir = specDirectory.resolve("controllers");
        if (!Files.isDirectory(controllersDir)) {
            log.info("No controller specs at " + controllersDir + " — nothing to generate.");
            return;
        }

        String packageName = npmName != null ? npmName : defaultNpmName();
        // The Maven version, always: it is already valid semver for both releases and -SNAPSHOT
        // (npm reads the latter as a prerelease), and a client whose version disagreed with the
        // module it was generated from would be actively misleading.
        String packageVersion = project.getVersion();

        TypeScriptSpecAssembler.Result assembled;
        Path assembledSpec;
        try {
            assembled = new TypeScriptSpecAssembler().assemble(
                    controllersDir,
                    specDirectory.resolve("openapi.json"),
                    packageName,
                    "TypeScript client for the controller endpoints of " + moduleLabel() + ".",
                    packageVersion,
                    INLINE_VERSION_SEGMENT);
            assembledSpec = writeAssembledSpec(assembled.document);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to assemble the controller spec", e);
        }

        if (assembled.operationCount == 0) {
            log.info("No controller operations found — nothing to generate.");
            return;
        }

        ExternalTypes external = externalPackages(assembled);
        reportAssembly(assembled, assembledSpec, external);
        checkOperationIds(assembled);

        runGenerator(assembledSpec, packageName, packageVersion, external);
        checkGeneratedNameCollisions();
        overloadRepresentationReturns(assembled);
        importExternalTypes(external);
        rewritePackageJson(packageName, packageVersion, external);
        excludeGeneratorBookkeepingFromTarball();

        log.info("TypeScript package written to " + outputDirectory);
        log.info("  npm package: " + packageName + "@" + packageVersion);
        log.info("  build it with: cd " + outputDirectory + " && npm install && npm run build");
    }

    /**
     * {@code webservices.rest-omod} → {@code @openmrs/webservices.rest-omod}.
     * <p>
     * The artifactId is carried through verbatim, so the npm package is recognisably the same
     * artifact as the Maven one it was generated from — including the {@code -omod} suffix, which
     * says which submodule of a multi-module project the endpoints came from, and the dots, which
     * are legal in npm names ({@code socket.io}).
     * <p>
     * The only rules npm imposes that an artifactId can break are that the name must be lowercase
     * and cannot begin with {@code .} or {@code _}, so those are all that is enforced here.
     */
    private String defaultNpmName() {
        String slug = project.getArtifactId().toLowerCase()
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("^[._]+", "");
        return NPM_SCOPE + "/" + slug;
    }

    /**
     * The cross-module types this package refers to: TypeScript type name -> the npm package that
     * publishes it, plus that package's version. Empty when the module has no cross-module refs.
     * <p>
     * Restricted to names the assembler actually reported as unresolved, so a schema this module
     * does define is never sourced from elsewhere even if another module happens to define one by
     * the same name — the local definition is the one the document means.
     */
    private ExternalTypes externalPackages(TypeScriptSpecAssembler.Result assembled)
            throws MojoFailureException {
        ExternalTypes types = new ExternalTypes();
        for (String schemaName : assembled.unresolvedRefs) {
            org.apache.maven.artifact.Artifact artifact = externalSchemaArtifacts.get(schemaName);
            if (artifact == null) {
                types.unowned.add(schemaName);
                continue;
            }
            String pkg = NPM_SCOPE + "/" + npmSlugForArtifact(artifact.getArtifactId());
            String previous = types.versionByPackage.put(pkg, artifact.getVersion());
            if (previous != null && !previous.equals(artifact.getVersion())) {
                // Several artifacts of one module collapse to one npm package, so they must agree
                // on a version. They always do when a module declares them through one property,
                // which is the convention — emrapi's nine webservices.rest artifacts all read
                // ${webservices.restVersion}. A disagreement means the dependency declaration is
                // itself inconsistent, and picking one silently would bake that into a published
                // package.
                throw new MojoFailureException("Cannot pick a version for " + pkg
                        + ": its Maven artifacts disagree (" + previous + " and "
                        + artifact.getVersion() + "). Make this module depend on one version of"
                        + " that module.");
            }
            types.packageByType.put(toTypeScriptModelName(schemaName), pkg);
            types.packageBySchema.put(schemaName, pkg);
        }
        return types;
    }

    /** What {@link #externalPackages} worked out, kept together because three steps need it. */
    private static final class ExternalTypes {
        /** TypeScript type name -> npm package publishing it, sorted for reproducible output. */
        final Map<String, String> packageByType = new java.util.TreeMap<>();
        /** OpenAPI schema name -> the same package, for the generator's schema mappings. */
        final Map<String, String> packageBySchema = new java.util.TreeMap<>();
        /** npm package -> version, from the Maven dependency it was resolved through. */
        final Map<String, String> versionByPackage = new java.util.TreeMap<>();
        /** Unresolved names no dependency claims; these still get a free-form stub. */
        final List<String> unowned = new ArrayList<>();

        boolean isEmpty() {
            return packageByType.isEmpty();
        }
    }

    /**
     * The npm package name for a Maven artifact of another OpenMRS module: everything up to and
     * including {@code -omod}, so {@code webservices.rest-omod-1.8} becomes
     * {@code webservices.rest-omod}. The {@code -omod} stays; the tail after it goes.
     *
     * <h4>What that tail is</h4>
     *
     * An <b>OpenMRS platform version</b>, not a version of the module. Nothing appends it: in the
     * 2.x era the REST module supported many cores by compiling each core's resources in its own
     * hand-declared Maven submodule — {@code omod-1.8} through {@code omod-2.2} plus
     * {@code omod-common} — each built against that core's API. That is also why the classes are
     * named {@code ConceptResource1_8}, and why {@code getResourceName()} strips a trailing
     * {@code 1_8}.
     *
     * <h4>Why the collapse is needed</h4>
     *
     * A module publishes <b>one</b> npm package, named after its aggregate omod, but a dependent
     * module depends on the <em>submodules</em> and typically never on the aggregate: emrapi
     * declares nine {@code webservices.rest-omod-*} artifacts and no {@code webservices.rest-omod}.
     * Left alone they would each name a different package, and
     * {@code @openmrs/webservices.rest-omod-1.8} is not something anyone publishes.
     * <p>
     * The invariant this has to hold is agreement with {@link #defaultNpmName()}, which is the rule
     * the owning module uses when generating its <em>own</em> package from
     * {@code project.getArtifactId()}. That artifactId is the aggregate, {@code webservices.rest-omod},
     * on which this method is a no-op — so a dependent module's import names exactly the package
     * the owning module publishes. The two rules live next to each other for that reason.
     *
     * <h4>Scope: 2.x dependencies only</h4>
     *
     * The submodules are <b>gone</b> as of REST {@code 3.x} (RESTWS-983, which raised the lowest
     * supported core to 2.4.x): the version-specific resources are now packages inside one
     * {@code omod}, at {@code .../v1_0/resource/openmrs1_8/}. So a module depending on REST 3.x
     * sees a single {@code webservices.rest-omod} artifact and this method does nothing at all. It
     * exists for modules still on a 2.x-era dependency, which today is emrapi (2.42.0).
     * <p>
     * An artifactId with no {@code -omod} segment is used as-is — it is not an omod submodule, so
     * there is nothing to collapse.
     */
    private static String npmSlugForArtifact(String artifactId) {
        int omod = artifactId.indexOf("-omod");
        String base = omod < 0 ? artifactId : artifactId.substring(0, omod + "-omod".length());
        return base.toLowerCase().replaceAll("[^a-z0-9._-]", "-").replaceAll("^[._]+", "");
    }

    /**
     * The TypeScript type openapi-generator will name a schema, mirroring its camelize step:
     * {@code ConceptGet_ref} → {@code ConceptGetRef}.
     * <p>
     * Has to match exactly, because it is the key of both the schema mapping (which suppresses
     * local generation) and the import that replaces it.
     */
    static String toTypeScriptModelName(String schemaName) {
        StringBuilder name = new StringBuilder();
        for (String part : schemaName.split("_")) {
            if (!part.isEmpty()) {
                name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return name.toString();
    }

    private String moduleLabel() {
        return project.getName() != null ? project.getName() : project.getArtifactId();
    }

    /**
     * Writes the assembled document next to (not inside) the package, so it is inspectable and
     * diffable without being mistaken for package content.
     */
    private Path writeAssembledSpec(ObjectNode document) throws IOException {
        Path dir = new File(project.getBuild().getDirectory(), "openapi-typescript").toPath();
        Files.createDirectories(dir);
        Path file = dir.resolve("controllers-openapi.json");
        ObjectMapper mapper = new ObjectMapper();
        Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
        return file;
    }

    /** Names to list in full before switching to a count; a whole module's schemas is not a log. */
    private static final int NAMES_WORTH_LISTING = 8;

    private void reportAssembly(TypeScriptSpecAssembler.Result r, Path spec, ExternalTypes external) {
        log.info("Assembled " + r.controllerCount + " controller(s) and "
                + r.resourceOperationCount + " resource operation(s), " + r.operationCount
                + " operation(s) in all, " + r.schemaCount + " schema(s) into "
                + spec.getFileName());

        if (!r.backfilledSchemas.isEmpty()) {
            // Expected, not a problem: a controller DTO holding a REST resource refers to a schema
            // the resource half of the spec owns. Naming them all was useful when there was one;
            // now that resource paths are merged too it is every schema in the module (637 for
            // webservices.rest), which is not something anyone reads.
            log.info("  pulled " + r.backfilledSchemas.size() + " schema(s) from openapi.json"
                    + (r.backfilledSchemas.size() <= NAMES_WORTH_LISTING
                        ? ": " + String.join(", ", r.backfilledSchemas) : ""));
        }
        for (Map.Entry<String, java.util.Set<String>> e : r.tagOwners.entrySet()) {
            if (e.getValue().size() > 1) {
                log.warn("  controllers " + String.join(", ", e.getValue())
                        + " share the API name '" + e.getKey()
                        + "'; their operations are merged into one class");
            }
        }
        for (String collision : r.inlinedVersionCollisions) {
            log.warn("  inlining the version segment collided: " + collision);
        }
        for (Map.Entry<String, String> e : external.versionByPackage.entrySet()) {
            log.info("  depends on " + e.getKey() + "@" + e.getValue()
                    + ", for the schema(s) it defines that this module references");
        }
        if (!external.unowned.isEmpty()) {
            // No dependency claims these, so there is nothing to import them from. They keep the
            // free-form stub, which is honest about what this build can see.
            log.warn("  " + external.unowned.size() + " $ref(s) belong to no declared dependency,"
                    + " so they stay free-form objects: " + String.join(", ", external.unowned));
        }
    }

    private void checkOperationIds(TypeScriptSpecAssembler.Result r) throws MojoFailureException {
        List<String> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : r.operationIdCollisions.entrySet()) {
            if (e.getValue().size() > 1) {
                duplicates.add(e.getKey() + " claimed by " + String.join(", ", e.getValue()));
            }
        }
        if (duplicates.isEmpty()) {
            return;
        }
        String message = "Duplicate operationId(s) across this module's controllers:\n  "
                + String.join("\n  ", duplicates)
                + "\nEach one is an exported function in the generated client, so they must be unique."
                + "\nAnnotate one of the colliding methods with"
                + " @io.swagger.v3.oas.annotations.Operation(operationId = \"...\").";
        // Always fatal, with no switch to soften it. An operationId is an exported function name in
        // the generated client; left to itself openapi-generator resolves a duplicate by appending
        // a digit to whichever it processes second, so the name a consumer imports would depend on
        // iteration order and adding an unrelated controller could rename an existing export.
        // Failing puts the fix where it belongs: an explicit @Operation(operationId = "...") on one
        // of the two methods. Warning instead would publish the ambiguity.
        throw new MojoFailureException(message);
    }

    private void runGenerator(Path spec, String packageName, String packageVersion,
            ExternalTypes external) throws MojoExecutionException {
        try {
            // The output is regenerated wholesale. Without this, a controller or model that stopped
            // being generated would leave its file behind and still be exported from a stale
            // index.ts — the same staleness the spec generator's clearGeneratedJson() prevents.
            deleteRecursively(outputDirectory.toPath());
            Files.createDirectories(outputDirectory.toPath());

            CodegenConfigurator configurator = new CodegenConfigurator()
                    .setGeneratorName(GENERATOR_NAME)
                    .setInputSpec(spec.toAbsolutePath().toString())
                    .setOutputDir(outputDirectory.getAbsolutePath())
                    // Every generator treats a tag as "the class this method goes on" and emits the
                    // operation once per tag, so a second tag would duplicate the method onto a
                    // second class. ControllerDocumenter puts the controller's own tag first
                    // precisely so this rule can discard the rest: one class per controller, while
                    // tags stay usable as the semantic channel they are meant to be. This rewrites
                    // only the in-memory model — the document on disk keeps all of its tags.
                    .addOpenapiNormalizer("KEEP_ONLY_FIRST_TAG_IN_OPERATION", "true");

            // A schema mapping says "this schema is an existing type of that name" — so the
            // generator uses the name where the type appears and does not emit a model file for it.
            // That is what turns emrapi's codedAnswer from `{ [key: string]: any }` into
            // `ConceptGetRef`.
            //
            // The obvious companion, addImportMapping(type, package), does NOT work here:
            // typescript-fetch's toModelImport never consults importMapping, so it emits
            // `from './ConceptGetRef'` — a relative path to the file the schema mapping correctly
            // stopped it generating. Verified with runtime checks both on and off. The import is
            // therefore written afterwards by importExternalTypes().
            for (Map.Entry<String, String> e : external.packageBySchema.entrySet()) {
                configurator.addSchemaMapping(e.getKey(), toTypeScriptModelName(e.getKey()));
            }

            // MainResourceController.delete() is the name the operation should carry, but `delete`
            // is a JavaScript operator, so AbstractTypeScriptClientCodegen.toSafeIdentifier()
            // escapes it to `_delete`. It does not need escaping: a class member may be called
            // `delete` (Map and Set both have one), and `resource.delete({uuid})` is valid
            // TypeScript. An operationIdNameMapping entry is returned from
            // getOrGenerateOperationId() before toOperationId() is ever called, so it bypasses the
            // escape. Keyed on the name *after* prefix removal, which is what that method compares.
            configurator.addOperationIdNameMapping("delete", "delete");

            for (Map.Entry<String, Object> e : generatorProperties(packageName, packageVersion).entrySet()) {
                configurator.addAdditionalProperty(e.getKey(), e.getValue());
            }

            ClientOptInput input = configurator.toClientOptInput();
            new DefaultGenerator().opts(input).generate();
        } catch (Exception e) {
            throw new MojoExecutionException("openapi-generator failed for " + spec, e);
        }
    }

    private Map<String, Object> generatorProperties(String packageName, String packageVersion) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("npmName", packageName);
        props.put("npmVersion", packageVersion);
        if (npmRepository != null) {
            props.put("npmRepository", npmRepository);
        }
        props.put("supportsES6", "true");
        // Off by default in typescript-fetch. Without it every model gets a runtime
        // instanceOf<Model> guard, which for OpenMRS payloads means asserting on fields the
        // representation may legitimately omit.
        props.put("withoutRuntimeChecks", "true");
        // Emitted into every generated file's header otherwise, which would make the output differ
        // between runs and defeat the determinism the specs are generated with.
        props.put("hideGenerationTimestamp", "true");
        // Drops the "Api" that DefaultCodegen.toApiName() otherwise appends to every tag, so the
        // tag PatientResource becomes the class PatientResource in the file PatientResource.ts.
        // toApiFilename() delegates to toApiName() and the default PascalCase fileNaming is a
        // pass-through, so class and file move together.
        //
        // It has to go through additionalProperties. CodegenConfigurator.setApiNameSuffix("") is a
        // silent no-op: an isNotEmpty guard skips the additionalProperties write there and again in
        // GeneratorSettings, and toClientOptInput() never calls the config setter — so the only
        // channel processOpts() reads is this one, where a containsKey check lets "" through.
        // (CodegenConstants.API_NAME_SUFFIX_DESC claims only ruby/python/jaxrs support this. That
        // note is stale; the mechanism is entirely in DefaultCodegen and typescript-fetch overrides
        // neither toApiName nor the suffix handling.)
        props.put("apiNameSuffix", "");
        // Resource operationIds are "<Tag>_<method>" — the prefix is what makes them unique across
        // the document, which openapi-generator requires, while the method name is what a caller
        // should see. This drops the first "_"-delimited segment, leaving PatientResource.retrieve().
        // Controller operationIds are Java method names with no "_", and the split-and-drop is
        // guarded on length > 1, so they pass through untouched.
        props.put("removeOperationIdPrefix", "true");
        props.put("removeOperationIdPrefixCount", "1");
        // Required by the line above, not optional. typescript-fetch names each method's
        // parameter interface "<operationIdCamelCase>Request", and shortening the method names to
        // retrieve/create/update/get made those collide: every resource file exported a
        // GetRequest and a RetrieveRequest, and the root index.ts re-exports all of them with
        // `export *` (42 × TS2308). Prefixing with the class gives PatientResourceRetrieveRequest.
        props.put("prefixParameterInterfaces", "true");
        // This set is the whole contract; there is no pass-through for arbitrary extras. Each entry
        // is load bearing (see the comments above and the npm metadata rewrite below), and a caller
        // able to override them could quietly break determinism or the package layout the rewrite
        // assumes.
        return props;
    }

    /**
     * Fails the build if an API class name collides with a model or a runtime export.
     * <p>
     * The root {@code index.ts} re-exports {@code runtime}, {@code apis/index} and
     * {@code models/index} into one namespace, and {@code withoutRuntimeChecks} makes
     * {@code models/index.ts} declare its types inline rather than re-export them per file. So two
     * things sharing a name is a duplicate export (TS2308) — and dropping the {@code Api} suffix
     * removed the only thing that kept the API and model namespaces apart.
     * <p>
     * They are disjoint by convention rather than by construction: a tag ends in {@code Resource}
     * or {@code Controller} ({@code getResourceApiTag} / {@code apiTagFor} guarantee that much) and
     * a model ends in a representation suffix. Measured across the four verified modules, nothing
     * collides. Convention is not an invariant, so this fails loudly rather than emitting a package
     * that does not compile.
     */
    private void checkGeneratedNameCollisions() throws MojoFailureException,
            MojoExecutionException {
        Path apis = outputDirectory.toPath().resolve("src/apis");
        if (!Files.isDirectory(apis)) {
            return;
        }
        Set<String> taken = new java.util.TreeSet<>(emittedModelNames());
        Path runtime = outputDirectory.toPath().resolve("src/runtime.ts");
        if (Files.isRegularFile(runtime)) {
            try {
                Matcher m = Pattern.compile(
                        "export (?:declare )?(?:class|interface|type|const|function|enum) (\\w+)")
                        .matcher(new String(Files.readAllBytes(runtime),
                                java.nio.charset.StandardCharsets.UTF_8));
                while (m.find()) {
                    taken.add(m.group(1));
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read " + runtime, e);
            }
        }
        List<String> collisions = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.list(apis)) {
            walk.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".ts") && !n.equals("index.ts"))
                    .map(n -> n.substring(0, n.length() - ".ts".length()))
                    .sorted()
                    .filter(taken::contains)
                    .forEach(collisions::add);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to list " + apis, e);
        }
        if (!collisions.isEmpty()) {
            throw new MojoFailureException("Generated API class name(s) collide with a model or"
                    + " runtime export, which the root index.ts re-exports into one namespace: "
                    + String.join(", ", collisions)
                    + ". Rename the resource or controller, or restore an apiNameSuffix.");
        }
    }

    /**
     * Gives every resource GET a return type that depends on the {@code ?v=} representation asked
     * for, by declaring TypeScript overloads above the generated method.
     *
     * <pre>
     * retrieve(rp: Req &amp; { v: 'full' },      …): Promise&lt;QueueGetFull&gt;;
     * retrieve(rp: Req &amp; { v: 'ref' },       …): Promise&lt;QueueGetRef&gt;;
     * retrieve(rp: Req &amp; { v?: undefined }, …): Promise&lt;QueueGetDefault&gt;;
     * retrieve(rp: Req,                      …): Promise&lt;Partial&lt;QueueGetCustom&gt;&gt;;
     * async retrieve(rp: Req, …): Promise&lt;any&gt; { … }   // body unchanged
     * </pre>
     *
     * openapi-generator cannot express this: the spec says the response is an {@code anyOf} over
     * the representations, which it flattens into one interface carrying every representation's
     * fields — a client that claims {@code ?v=ref} returns everything {@code ?v=full} does. The
     * concrete per-representation interfaces <em>are</em> generated, so the only thing missing is a
     * signature that selects between them, and overloads are compile-time only: the emitted
     * JavaScript is untouched.
     *
     * <h4>Order is load bearing</h4>
     *
     * TypeScript takes the <b>first</b> matching signature, so the named representations come
     * first, then the {@code v}-omitted case, then the catch-all. The omitted case is written
     * {@code v?: undefined} rather than {@code Omit<Req, 'v'>} deliberately: a caller passing a
     * variable of type {@code Req} (whose {@code v} is {@code string | undefined}) is structurally
     * assignable to {@code Omit<Req, 'v'>} and would wrongly be typed as the default, whereas
     * {@code string} is not assignable to {@code undefined}, so it correctly falls through to the
     * custom catch-all.
     *
     * <h4>What each overload claims</h4>
     *
     * A named representation is exact. The catch-all fires for {@code ?v=custom:(…)} and is typed
     * {@code Partial<…GetCustom>}: {@code Get_custom} is the plugin's superset of every
     * {@code @PropertyGetter}, but the server returns only the fields asked for, so
     * {@code ?v=custom:(uuid,display)} genuinely has two. {@code Partial} keeps the whole field
     * list available to autocompletion while forcing the presence check that is the real contract.
     *
     * <h4>Why the implementation signature is widened to {@code Promise<any>}</h4>
     *
     * An overload's return type must be assignable to the implementation's, and the implementation
     * returns the {@code XGet} union — which openapi-generator has flattened into a single merged
     * interface. {@code XGetFull} is assignable to that only by structural luck, and fails outright
     * wherever a property's type differs between the merged shape and the variant (three such in
     * {@code webservices.rest}: {@code ConceptDescription}, {@code ConceptName},
     * {@code CustomDatatype}). Enumerating a union of the concrete types instead would hit the same
     * problem from the other side, since the list overloads return an
     * {@code Omit<…> & { results?: … }} intersection. {@code Promise<any>} is one rule that cannot
     * be missed, and it never reaches a consumer: for an overloaded method {@code tsc --declaration}
     * emits only the overload signatures.
     *
     * <h4>Types are gated on what was actually generated</h4>
     *
     * openapi-generator only emits a named model for a schema that has content; a free-form schema
     * is inlined at each use site instead. 82 of {@code webservices.rest}'s 402 {@code Get_*}
     * schemas are empty (the {@code @RepHandler} issue — see {@code plan-representation-typing.md}),
     * so {@code CohortGetRef} is never declared and an overload naming it would not compile. Any
     * name absent from {@link #emittedModelNames} is therefore replaced by
     * {@value #FREE_FORM_TYPE}, which is precisely what the spec says about it
     * ({@code additionalProperties: true}) and which becomes a real type on its own once those
     * schemas are filled in. Dropping the overload instead would let {@code {v:'ref'}} fall through
     * to the custom catch-all — a lie rather than an honest absence.
     *
     * <h4>The {@code …Raw} variants</h4>
     *
     * Not overloaded, and made {@code protected} here. They hand back the whole
     * {@code ApiResponse} rather than the parsed body, which is an escape hatch for a caller that
     * needs headers or a status code — reachable from a subclass, but not part of the API surface.
     * typescript-fetch has no option for this ({@code apis.mustache} emits them unconditionally with
     * no visibility modifier), and they are referenced only from inside their own class, so this is
     * a safe rewrite. A vendored template would be the alternative, at the price of owning an 18 KB
     * mustache file across generator upgrades.
     */
    private void overloadRepresentationReturns(TypeScriptSpecAssembler.Result assembled)
            throws MojoExecutionException {
        Map<String, List<String>> representations = representationsByResource(assembled.document);
        Path apis = outputDirectory.toPath().resolve("src/apis");
        if (!Files.isDirectory(apis)) {
            return;
        }
        // Collected once, before anything rewrites a generated file. rewriteRepresentationUnions()
        // turns `export interface XGet` into `export type XGet`, and the two passes must not
        // disagree about what exists.
        Set<String> emitted = emittedModelNames();

        List<Path> files;
        try (java.util.stream.Stream<Path> walk = Files.list(apis)) {
            files = walk.filter(p -> p.getFileName().toString().endsWith(".ts"))
                    .filter(p -> !p.getFileName().toString().equals("index.ts"))
                    .sorted().collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to list the generated API classes", e);
        }

        int methods = 0;
        int hidden = 0;
        List<String> unmatched = new ArrayList<>();
        for (Path file : files) {
            String className = file.getFileName().toString()
                    .substring(0, file.getFileName().toString().length() - ".ts".length());
            String base = representationUnionFor(className, representations);
            try {
                String body = new String(Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                StringBuilder rewritten = new StringBuilder();
                java.util.SortedSet<String> referenced = new java.util.TreeSet<>();
                int added = 0;
                int protectedHere = 0;
                int wrappers = 0;
                int recognised = 0;
                for (String line : body.split("\n", -1)) {
                    Matcher raw = GENERATED_RAW_METHOD.matcher(line);
                    if (raw.matches()) {
                        rewritten.append(raw.group(1)).append("protected ")
                                .append(line.substring(raw.group(1).length())).append('\n');
                        protectedHere++;
                        continue;
                    }
                    if (PUBLIC_WRAPPER.matcher(line).matches()) {
                        wrappers++;
                    }
                    Matcher m = GENERATED_METHOD.matcher(line);
                    if (m.matches()) {
                        recognised++;
                    }
                    if (m.matches() && base != null) {
                        String overloads = overloadsFor(m.group(1), m.group(2), m.group(3),
                                m.group(4), base, representations.get(base), emitted, referenced);
                        if (overloads != null) {
                            rewritten.append(overloads);
                            // Widen the implementation signature, which the overloads must all be
                            // assignable to. Only when overloads were actually added.
                            line = m.group(1) + "async " + m.group(2) + "(requestParameters: "
                                    + m.group(3) + (line.contains(" = {}") ? " = {}" : "")
                                    + ", initOverrides?: RequestInit"
                                    + " | runtime.InitOverrideFunction): Promise<any> {";
                            added++;
                        }
                    }
                    rewritten.append(line).append('\n');
                }
                // split(-1) leaves a trailing empty element for the final newline; drop the extra.
                rewritten.setLength(rewritten.length() - 1);
                String updated = importModelTypes(rewritten.toString(), referenced, emitted);
                if (!updated.equals(body)) {
                    Files.write(file,
                            updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                methods += added;
                hidden += protectedHere;
                if (recognised != wrappers) {
                    unmatched.add(className + " (" + (wrappers - recognised) + " of " + wrappers
                            + ")");
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to add overloads to " + file, e);
            }
        }
        log.info("  representation overloads on " + methods + " resource method(s); "
                + hidden + " raw method(s) made protected");

        // The 66 list GETs that silently received no overload were found by hand-auditing the
        // generated output, because nothing here said a word.
        //
        // The signal is GENERATED_METHOD failing to match a method it should have. Every public
        // wrapper openapi-generator emits has that shape, so a mismatch means the generated
        // signature drifted — which is exactly how those 66 went missing and how a generator
        // upgrade would silently drop overloads again. Note this deliberately does NOT check that
        // every resource received an overload: ModuleAction and TaskAction have a Get_default
        // schema (the POST 201 response) and no GET route at all, which is legitimate.
        if (!unmatched.isEmpty()) {
            throw new MojoExecutionException("GENERATED_METHOD did not match every generated"
                    + " wrapper in " + unmatched.size() + " file(s): "
                    + String.join(", ", unmatched)
                    + ". openapi-generator's method signature has probably changed shape, which"
                    + " would silently drop ?v= overloads.");
        }
        rewriteRepresentationUnions(representations, emitted);
    }

    /** What an overload uses for a representation openapi-generator never declared a model for. */
    static final String FREE_FORM_TYPE = "{ [key: string]: any }";

    /**
     * Every type name {@code models/index.ts} declares.
     * <p>
     * Both {@code export interface} and {@code export type} — a union rewritten by
     * {@link #rewriteRepresentationUnions} is the latter, and collecting only interfaces would
     * under-report and substitute {@link #FREE_FORM_TYPE} over a type that does exist.
     */
    private Set<String> emittedModelNames() throws MojoExecutionException {
        Set<String> names = new java.util.TreeSet<>();
        Path models = outputDirectory.toPath().resolve("src/models/index.ts");
        if (!Files.isRegularFile(models)) {
            return names;
        }
        try {
            Matcher m = Pattern.compile("export (?:interface|type) (\\w+)")
                    .matcher(new String(Files.readAllBytes(models),
                            java.nio.charset.StandardCharsets.UTF_8));
            while (m.find()) {
                names.add(m.group(1));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read " + models, e);
        }
        return names;
    }

    /**
     * The {@code XGet} union name for a generated API class, or null if the class is not a resource.
     * <p>
     * Taken from the <b>file name</b>: {@code PatientResource} → {@code Patient} → {@code PatientGet}.
     * The previous rule recovered it from the list wrapper's {@code results} item type, which had to
     * be both a named model <em>and</em> end in {@code Ref} — neither holds for a resource whose
     * {@code Get_ref} schema is empty, so 66 of 184 GET methods in {@code webservices.rest} got no
     * overload while their instance GETs did. Every generated file carries exactly one tag, so its
     * name identifies the resource outright.
     * <p>
     * {@code Resource} is stripped anchored at the <b>end</b>, the same rule
     * {@code OpenMRSResourceModelResolver.getResourceName()} applies, so the sub-resource
     * {@code FormResourceResource} yields {@code FormResource} and not {@code Form}. A controller
     * ({@code DiagnosisController}) strips nothing, misses the table, and returns null — which is
     * why the coverage check above can treat a null as "not a resource" rather than a failure.
     */
    private static String representationUnionFor(String className,
            Map<String, List<String>> representations) {
        if (!className.endsWith("Resource")) {
            return null;
        }
        String union = className.substring(0, className.length() - "Resource".length()) + "Get";
        return representations.containsKey(union) ? union : null;
    }

    /**
     * Adds any model type an overload names to the file's {@code import type { ... } from
     * '../models/index'} block.
     * <p>
     * Necessary because openapi-generator imports exactly what the <em>implementation</em> uses,
     * and the implementation returns only the union - {@code QueueEntryGet}. Every concrete
     * representation an overload names would otherwise be an unresolved identifier. The names are
     * merged into the existing block and re-sorted, so the result does not depend on the order the
     * overloads were emitted in.
     * <p>
     * It also drops any name that {@code models/index.ts} does not declare <em>and</em> the file
     * does not use. openapi-generator emits one: for a request body with several media types it
     * takes the first for the parameter interface but still records an import for the alternatives'
     * inline models, which it then never generates. {@code POST /obs} accepts both
     * {@code application/json} and {@code multipart/form-data}, so {@code ObsResource} imported an
     * {@code ObsUpload} that nothing declared. Only unused names are dropped — a *used* one is a
     * real problem and should stay a compile error rather than be silently deleted.
     */
    private static String importModelTypes(String body, java.util.SortedSet<String> referenced,
            Set<String> emitted) {
        Matcher block = Pattern.compile("import type \\{\\n(.*?)\\n\\} from '\\.\\./models/index';",
                Pattern.DOTALL).matcher(body);
        if (!block.find()) {
            return body;
        }
        java.util.SortedSet<String> names = new java.util.TreeSet<>(referenced);
        for (String existing : block.group(1).split(",")) {
            String name = existing.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (emitted.contains(name) || usedOutsideImport(body, name, block.end())) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return body;
        }
        StringBuilder replacement = new StringBuilder("import type {\n");
        for (String name : names) {
            replacement.append("  ").append(name).append(",\n");
        }
        replacement.append("} from '../models/index';");
        return body.substring(0, block.start()) + replacement + body.substring(block.end());
    }

    /** Whether {@code name} appears as an identifier anywhere after the import block. */
    private static boolean usedOutsideImport(String body, String name, int afterImport) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b")
                .matcher(body).find(afterImport);
    }

    /**
     * The generated wrapper method, whose shape is fixed by the pinned generator version:
     * {@code     async name(requestParameters: Req = {}, initOverrides?: …): Promise<Ret> {}.
     * The {@code async} keyword is what distinguishes it from the overloads added above it, so
     * re-running over already-overloaded output would be a no-op rather than a duplication —
     * though it never happens, since the output directory is deleted first.
     */
    private static final Pattern GENERATED_METHOD = Pattern.compile(
            "^(\\s*)async (\\w+)\\(requestParameters: (\\w+)(?: = \\{\\})?,"
            + " initOverrides\\?: RequestInit \\| runtime\\.InitOverrideFunction\\)"
            + ": Promise<([\\w.]+)> \\{$");

    /**
     * The {@code …Raw} twin, matched so it can be made {@code protected}. Its return type is
     * {@code runtime.ApiResponse<…>}, and it may take no {@code requestParameters} at all.
     */
    private static final Pattern GENERATED_RAW_METHOD = Pattern.compile(
            "^(\\s*)async (\\w+Raw)\\(.*\\): Promise<runtime\\.ApiResponse<.*>> \\{$");

    /**
     * A public wrapper that <em>could</em> be a representation-bearing GET: it takes
     * {@code requestParameters} and returns a named type. Counted against
     * {@link #GENERATED_METHOD}, which pins the exact parameter list, so a drift in the generated
     * signature fails the build instead of silently dropping overloads.
     * <p>
     * The two shapes deliberately excluded are ones a representation GET cannot have. An operation
     * with no parameters at all emits {@code async sessionControllerGet(initOverrides?: …)}, but a
     * representation GET always has at least {@code v}. And a return type openapi-generator inlined
     * rather than named — {@code Promise<{ [key: string]: any; }>} — is never the {@code XGet} union
     * or a {@code …200Response} wrapper, both of which are always named.
     * <p>
     * The {@code …Raw} twins are consumed before this is reached.
     */
    private static final Pattern PUBLIC_WRAPPER = Pattern.compile(
            "^\\s*async \\w+\\(requestParameters: .*\\): Promise<[\\w.]+> \\{$");

    /** Builds the overload block for one method, or null if it takes no representation. */
    private String overloadsFor(String indent, String method, String requestType, String returnType,
            String base, List<String> reps, Set<String> emitted,
            java.util.Set<String> referenced) {
        boolean list = returnType.endsWith("200Response");
        if (!list && !returnType.equals(base)) {
            // Neither the resource's union nor its list wrapper: not a representation-bearing GET.
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String rep : reps) {
            if ("custom".equals(rep)) {
                continue;
            }
            out.append(signature(indent, method, requestType, "{ v: '" + rep + "' }",
                    returns(base + toTypeScriptModelName("_" + rep), false, list, returnType,
                            emitted, referenced)));
        }
        boolean hasDefault = reps.contains("default");
        out.append(signature(indent, method, requestType, "{ v?: undefined }",
                returns(hasDefault ? base + "Default" : base, false, list, returnType, emitted,
                        referenced)));
        if (reps.contains("custom")) {
            out.append(signature(indent, method, requestType, null,
                    returns(base + "Custom", true, list, returnType, emitted, referenced)));
        }
        return out.toString();
    }

    /**
     * {@code Promise<X>} for an instance GET, or the list wrapper with its {@code results} retyped.
     *
     * @param partial wrap in {@code Partial<…>} — the {@code ?v=custom:(…)} catch-all, which
     *                over-promises otherwise
     */
    private static String returns(String type, boolean partial, boolean list, String wrapper,
            Set<String> emitted, java.util.Set<String> referenced) {
        String rendered;
        if (emitted.contains(type)) {
            referenced.add(type);
            rendered = partial ? "Partial<" + type + ">" : type;
        } else {
            // openapi-generator inlined this schema instead of naming it, because it is free-form.
            // Partial<> would be meaningless over an index signature, so it is dropped here.
            rendered = FREE_FORM_TYPE;
        }
        if (!list) {
            return "Promise<" + rendered + ">";
        }
        referenced.add(wrapper);
        return "Promise<Omit<" + wrapper + ", 'results'> & { results?: Array<" + rendered + "> }>";
    }

    private static String signature(String indent, String method, String requestType,
            String narrowing, String returns) {
        String parameter = narrowing == null ? requestType : requestType + " & " + narrowing;
        return indent + method + "(requestParameters: " + parameter
                + ", initOverrides?: RequestInit | runtime.InitOverrideFunction): "
                + returns + ";\n";
    }

    /**
     * Resource union name -> its representation suffixes, read from the {@code anyOf} the plugin
     * emits for {@code <Resource>Get}. Taken from the document rather than hardcoded to
     * default/full/ref/custom, because a resource may carry more: {@code ConceptGet} also has
     * {@code fullchildren} and {@code fullchildreninternal}, and a resource with subtypes has
     * {@code _order} / {@code _drugorder} / {@code _testorder} variants.
     * <p>
     * Keyed off the {@code Get_} split rather than the presence of an {@code XGet} schema, so a
     * resource that has only one representation still gets an entry — {@code ConceptSearch} has a
     * {@code ConceptSearchGet_ref} and no union at all.
     */
    private static Map<String, List<String>> representationsByResource(ObjectNode document) {
        Map<String, List<String>> byResource = new java.util.TreeMap<>();
        com.fasterxml.jackson.databind.JsonNode schemas =
                document.path("components").path("schemas");
        schemas.fieldNames().forEachRemaining(name -> {
            int marker = name.indexOf("Get_");
            if (marker <= 0) {
                return;
            }
            String union = toTypeScriptModelName(name.substring(0, marker) + "Get");
            byResource.computeIfAbsent(union, k -> new ArrayList<>())
                    .add(name.substring(marker + "Get_".length()));
        });
        for (List<String> reps : byResource.values()) {
            java.util.Collections.sort(reps);
        }
        return byResource;
    }

    /**
     * Turns each flattened {@code XGet} interface in {@code models/index.ts} into a real union.
     * <p>
     * openapi-generator collapses the {@code anyOf} the plugin emits into one interface carrying
     * every representation's fields at once, so {@code PatientGet} claims that a {@code ?v=ref}
     * response has {@code auditInfo}. A response is exactly one representation, so the union is the
     * true type. It stays exported either way — existing code can keep naming {@code PatientGet}
     * and migrate to the concrete subtypes call site by call site.
     * <p>
     * Only representations openapi-generator actually declared are named, for the same reason
     * {@link #returns} gates on that. A resource whose union was never emitted but which has at
     * least one representation gets the alias appended instead ({@code ConceptSearch}); one with no
     * emitted representation at all is left alone.
     */
    private void rewriteRepresentationUnions(Map<String, List<String>> representations,
            Set<String> emitted) throws MojoExecutionException {
        Path models = outputDirectory.toPath().resolve("src/models/index.ts");
        if (!Files.isRegularFile(models)) {
            return;
        }
        String body;
        try {
            body = new String(Files.readAllBytes(models), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read " + models, e);
        }

        StringBuilder appended = new StringBuilder();
        int replaced = 0;
        int added = 0;
        for (Map.Entry<String, List<String>> entry : representations.entrySet()) {
            String union = entry.getKey();
            List<String> members = new ArrayList<>();
            for (String rep : entry.getValue()) {
                String member = union + toTypeScriptModelName("_" + rep);
                if (emitted.contains(member)) {
                    members.add(member);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            String alias = "export type " + union + " = " + String.join(" | ", members) + ";";
            String declaration = declarationOf(body, union);
            if (declaration != null) {
                body = body.substring(0, body.indexOf(declaration)) + alias
                        + body.substring(body.indexOf(declaration) + declaration.length());
                replaced++;
            } else if (!emitted.contains(union)) {
                appended.append('\n').append(alias).append('\n');
                added++;
            }
        }
        if (replaced == 0 && added == 0) {
            return;
        }
        try {
            Files.write(models, (body + appended)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to rewrite " + models, e);
        }
        log.info("  " + replaced + " flattened representation union(s) replaced with a real union, "
                + added + " added");
    }

    /**
     * The full {@code export interface <name> { … }} block, or null if there is none.
     * <p>
     * Scans to the first line that is exactly {@code "}"} rather than brace-matching: the generated
     * declarations are flat, and a property type legitimately contains braces
     * ({@code { [key: string]: any; }}). The name is matched on an identifier boundary because
     * {@code PatientGetCustom} also starts with {@code PatientGet}.
     */
    private static String declarationOf(String body, String name) {
        Matcher start = Pattern.compile("^export interface " + Pattern.quote(name) + " \\{$",
                Pattern.MULTILINE).matcher(body);
        if (!start.find()) {
            return null;
        }
        int close = body.indexOf("\n}\n", start.end());
        return close < 0 ? null : body.substring(start.start(), close + "\n}".length());
    }

    /**
     * Writes the {@code import type} lines for the cross-module types, which openapi-generator
     * names but never imports.
     * <p>
     * {@code import type} rather than {@code import}: with {@code withoutRuntimeChecks} the models
     * are plain interfaces, so a type-only import erases at compile time and the emitted JavaScript
     * carries no {@code require} of the other package. It still belongs in {@code dependencies}
     * rather than {@code devDependencies}, because the type appears in this package's published
     * {@code .d.ts} and a consumer has to be able to resolve it.
     * <p>
     * Only files that actually name one of the types are touched, and the imports are grouped by
     * package and sorted, so the result is reproducible.
     */
    private void importExternalTypes(ExternalTypes external) throws MojoExecutionException {
        if (external.isEmpty()) {
            return;
        }
        Map<String, java.util.SortedSet<String>> typesByPackage = new java.util.TreeMap<>();
        for (Map.Entry<String, String> e : external.packageByType.entrySet()) {
            typesByPackage.computeIfAbsent(e.getValue(), k -> new java.util.TreeSet<>())
                    .add(e.getKey());
        }

        List<Path> sources;
        try (java.util.stream.Stream<Path> walk = Files.walk(outputDirectory.toPath().resolve("src"))) {
            sources = walk.filter(p -> p.getFileName().toString().endsWith(".ts"))
                    .sorted().collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to list the generated sources", e);
        }

        int touched = 0;
        for (Path source : sources) {
            try {
                String body = new String(Files.readAllBytes(source),
                        java.nio.charset.StandardCharsets.UTF_8);
                StringBuilder imports = new StringBuilder();
                for (Map.Entry<String, java.util.SortedSet<String>> e : typesByPackage.entrySet()) {
                    List<String> used = new ArrayList<>();
                    for (String type : e.getValue()) {
                        if (referencesType(body, type)) {
                            used.add(type);
                        }
                    }
                    if (!used.isEmpty()) {
                        imports.append("import type { ").append(String.join(", ", used))
                                .append(" } from '").append(e.getKey()).append("';\n");
                    }
                }
                if (imports.length() == 0) {
                    continue;
                }
                Files.write(source, (imports + "\n" + body)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                touched++;
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to add imports to " + source, e);
            }
        }
        log.info("  imported " + external.packageByType.size() + " type(s) from "
                + typesByPackage.size() + " other module package(s), into " + touched + " file(s)");
    }

    /** Whole-word match, so {@code Concept} does not match {@code ConceptGetRef}. */
    private static boolean referencesType(String body, String type) {
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(type) + "\\b")
                .matcher(body).find();
    }

    /**
     * Replaces the placeholder metadata openapi-generator writes ("OpenAPI-Generator" as author,
     * a {@code GIT_USER_ID/GIT_REPO_ID} repository URL) with the Maven project's own, and adds the
     * registry configuration if one was given.
     */
    private void rewritePackageJson(String packageName, String packageVersion,
            ExternalTypes external) throws MojoExecutionException {
        File packageJson = new File(outputDirectory, "package.json");
        if (!packageJson.exists()) {
            log.warn("No package.json was generated; skipping metadata rewrite.");
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = (ObjectNode) mapper.readTree(packageJson);

            node.put("name", packageName);
            node.put("version", packageVersion);
            node.put("description", "TypeScript client for the controller endpoints of "
                    + moduleLabel() + ", generated from its OpenAPI specification.");
            node.put("author", "OpenMRS");

            // maven-project 2.2.1 predates generics, so this list arrives raw.
            @SuppressWarnings("unchecked")
            List<org.apache.maven.model.License> licenses = project.getLicenses();
            if (licenses != null && !licenses.isEmpty()) {
                node.put("license", spdxLicense(licenses.get(0).getName()));
            }
            String scmUrl = scmUrl();
            if (scmUrl != null) {
                node.putObject("repository")
                        .put("type", "git")
                        .put("url", scmUrl);
            } else {
                node.remove("repository");
            }
            if (npmRepository != null) {
                node.putObject("publishConfig").put("registry", npmRepository);
            }

            // The version comes from the Maven dependency this module already declares on the other
            // module, so the two agree by construction: emrapi builds against
            // webservices.rest 2.42.0, so its npm package depends on
            // @openmrs/webservices.rest-omod@2.42.0. Pinned exactly rather than given a range,
            // because that is what the Maven side means — a range would claim a compatibility this
            // build never checked.
            if (!external.isEmpty()) {
                ObjectNode dependencies = node.putObject("dependencies");
                for (Map.Entry<String, String> e : external.versionByPackage.entrySet()) {
                    dependencies.put(e.getKey(), e.getValue());
                }
            }

            Files.write(packageJson.toPath(), writeJson(mapper, node));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to rewrite " + packageJson, e);
        }
    }


    /**
     * The project's SCM URL with the inherited submodule path removed.
     * <p>
     * Maven appends each module's directory to a {@code <scm>} URL inherited from the parent, so an
     * omod reports {@code .../openmrs-module-emrapi.git/emrapi-omod} — a URL that does not resolve.
     * Only a trailing segment that matches this module's own artifactId or directory name is
     * stripped, so a genuinely module-specific URL is left alone.
     */
    private String scmUrl() {
        if (project.getScm() == null || project.getScm().getUrl() == null) {
            return null;
        }
        String url = project.getScm().getUrl();
        for (String suffix : new String[] { project.getArtifactId(), project.getBasedir().getName() }) {
            if (url.endsWith("/" + suffix)) {
                return url.substring(0, url.length() - suffix.length() - 1);
            }
        }
        return url;
    }

    /**
     * Maps a Maven license name onto its SPDX identifier, which is what npm's {@code license} field
     * expects. OpenMRS declares "Mozilla Public License, Version 2.0 with Healthcare Disclaimer";
     * npm warns on anything that is not an SPDX expression, and MPL-2.0 is the licence proper — the
     * disclaimer rides along in the LICENSE file. Anything unrecognised is passed through as
     * {@code SEE LICENSE IN ...}, which is SPDX's own escape hatch for a non-standard licence.
     */
    private static String spdxLicense(String mavenLicenseName) {
        if (mavenLicenseName == null || mavenLicenseName.trim().isEmpty()) {
            return "SEE LICENSE IN LICENSE";
        }
        String normalized = mavenLicenseName.toLowerCase();
        if (normalized.contains("mozilla public license") && normalized.contains("2.0")) {
            return "MPL-2.0";
        }
        if (normalized.matches("^[a-z0-9.\\-]+$")) {
            // Already looks like an SPDX identifier (e.g. "Apache-2.0").
            return mavenLicenseName;
        }
        return "SEE LICENSE IN LICENSE";
    }

    /**
     * Jackson's default pretty printer writes {@code "key" : value}. Every other tool that touches
     * a package.json writes {@code "key": value}, and the file is one a human edits after
     * publishing, so match the convention rather than leaving a diff behind on first save.
     */
    private static byte[] writeJson(ObjectMapper mapper, ObjectNode node)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        com.fasterxml.jackson.core.util.DefaultPrettyPrinter printer =
                new com.fasterxml.jackson.core.util.DefaultPrettyPrinter() {
                    @Override
                    public com.fasterxml.jackson.core.util.DefaultPrettyPrinter createInstance() {
                        return this;
                    }

                    @Override
                    public void writeObjectFieldValueSeparator(
                            com.fasterxml.jackson.core.JsonGenerator g) throws java.io.IOException {
                        g.writeRaw(": ");
                    }
                };
        printer.indentArraysWith(
                com.fasterxml.jackson.core.util.DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        return (mapper.writer(printer).writeValueAsString(node) + "\n").getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Keeps openapi-generator's own bookkeeping out of the published tarball.
     * <p>
     * {@code .openapi-generator/FILES}, {@code .openapi-generator/VERSION} and
     * {@code .openapi-generator-ignore} are how the generator tracks what it wrote, and
     * {@code npm pack} ships them otherwise — verified. They stay on disk, where they are useful
     * for spotting a file the generator no longer owns; they just do not belong in what a consumer
     * installs. The generated {@code .npmignore} lists only {@code README.md}, so this appends
     * rather than replaces.
     */
    private void excludeGeneratorBookkeepingFromTarball() throws MojoExecutionException {
        Path npmIgnore = new File(outputDirectory, ".npmignore").toPath();
        List<String> entries = java.util.Arrays.asList(".openapi-generator", ".openapi-generator-ignore");
        try {
            List<String> lines = Files.exists(npmIgnore)
                    ? new ArrayList<>(Files.readAllLines(npmIgnore, java.nio.charset.StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean changed = false;
            for (String entry : entries) {
                if (!lines.contains(entry)) {
                    lines.add(entry);
                    changed = true;
                }
            }
            if (changed) {
                Files.write(npmIgnore,
                        (String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to update " + npmIgnore, e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
