// Internal compiler APIs, Kotlin 2.3 shape: the 2.4 pipeline (src/k24) with 2.3's older seams —
// the project environment and library list built by top-level functions rather than
// `JvmFrontendPipelinePhase`, the diagnostics collector carried by the configuration artifact, a
// source session told whether it is a leaf HMPP module, and compiler-plugin extensions registered
// in the project rather than in a per-configuration storage. WarmCompilerEntry refuses to run on a
// compiler outside the 2.3 line.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION", "DEPRECATION_ERROR")

package ee.schimke.composeai.daemon.warm.impl

import org.jetbrains.kotlin.backend.common.phaser.then
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.SessionConstructionUtils
import org.jetbrains.kotlin.cli.common.allFiles
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.collectSources
import org.jetbrains.kotlin.cli.common.fileBelongsToModuleForLt
import org.jetbrains.kotlin.cli.common.isCommonSourceForLt
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.createLibraryListForJvm
import org.jetbrains.kotlin.cli.jvm.compiler.legacy.pipeline.createProjectEnvironment
import org.jetbrains.kotlin.cli.pipeline.AbstractCliPipeline
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.ConfigurationPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.FrontendFilesForPluginsGenerationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelineContext
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmBackendPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmWriteOutputsPhase
import org.jetbrains.kotlin.cli.plugins.processCompilerPluginsOptions
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.config.phaser.CompilerPhase
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.buildFirViaLightTree
import org.jetbrains.kotlin.fir.pipeline.resolveAndCheckFir
import org.jetbrains.kotlin.fir.pipeline.runPlatformCheckers
import org.jetbrains.kotlin.fir.session.FirJvmSessionFactory
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.util.PerformanceManager
import org.jetbrains.kotlin.util.PhaseType

/**
 * What the stock frontend phase rebuilds on every compile although it depends only on the class
 * path: the project environment (jar file system, class-path roots index, project services) and the
 * FIR shared and library sessions with their deserialized-symbol caches.
 *
 * Valid only while every class the library session has deserialized is still ABI-identical on disk.
 * [WarmCompilerEntry] guarantees that: it installs only ABI-identical classes, and throws this
 * state away when anything else touches the module's classes.
 */
internal class ClasspathState(
  val environment: VfsBasedProjectEnvironment,
  val librariesScope: AbstractProjectFileSearchScope,
  val libraryList: DependencyListForCliModule,
  val sharedSession: FirSession,
  val librarySession: FirSession,
)

/** The Compose (or any other) compiler plugin, loaded once instead of once per compile. */
internal class LoadedPlugins(
  val registrars: List<CompilerPluginRegistrar>,
  val processors: List<CommandLineProcessor>,
  val options: List<String>,
)

/**
 * Applies the cached plugins' options to the compile's configuration. Unlike 2.4, the registrars
 * are not added to `COMPILER_PLUGIN_REGISTRARS`: 2.3 registers their extensions in the project when
 * it creates one, for the project's lifetime, and the warm compiler keeps its project across
 * compiles. [WarmFrontendPhase] registers them per compile instead.
 */
internal class InjectPluginsPhase(private val plugins: LoadedPlugins) :
  PipelinePhase<ConfigurationPipelineArtifact, ConfigurationPipelineArtifact>(
    name = "WarmInjectPlugins"
  ) {
  override fun executePhase(input: ConfigurationPipelineArtifact): ConfigurationPipelineArtifact {
    if (plugins.options.isNotEmpty()) {
      processCompilerPluginsOptions(input.configuration, plugins.options, plugins.processors)
    }
    return input
  }
}

/**
 * The stock 2.3 `JvmFrontendPipelinePhase` for a single light-tree JVM module with no incremental
 * compilation context, except that the class-path-only state comes from [WarmFrontendPhase.state]
 * when one is cached. Each compile still gets a fresh source session for its own files.
 */
internal class WarmFrontendPhase(
  private val keepAlive: () -> Disposable,
  private val plugins: LoadedPlugins,
) :
  PipelinePhase<ConfigurationPipelineArtifact, JvmFrontendPipelineArtifact>(
    name = "WarmFrontend",
    postActions =
      setOf(
        PerformanceNotifications.AnalysisFinished,
        CheckCompilationErrors.CheckDiagnosticCollector,
      ),
  ) {

  /** Class-path state, built on the first compile and kept until [WarmCompilerEntry] drops it. */
  var state: ClasspathState? = null

  /** Whether the last compile's sources declared an inline function another file could inline. */
  var declaresNonPrivateInline: Boolean = false
    private set

  /** Owns the plugin extensions registered in the project for the current compile. */
  private var pluginExtensions: Disposable? = null

  override fun executePhase(input: ConfigurationPipelineArtifact): JvmFrontendPipelineArtifact? {
    val configuration = input.configuration
    val diagnosticsCollector = input.diagnosticCollector
    val messageCollector = configuration.messageCollector
    val moduleName = configuration.moduleName!!
    val rootName = Name.special("<$moduleName>")

    val cached = state
    val environment =
      cached?.environment
        ?: createProjectEnvironment(
          configuration,
          keepAlive(),
          EnvironmentConfigFiles.JVM_CONFIG_FILES,
          messageCollector,
        )
    registerPlugins(configuration, environment.project)
    val registrars = FirExtensionRegistrar.getInstances(environment.project)

    val classpath =
      cached
        ?: run {
          val librariesScope = environment.getSearchScopeForProjectLibraries()
          val libraryList = createLibraryListForJvm(moduleName, configuration, emptyList())
          val context = FirJvmSessionFactory.Context(configuration, environment, librariesScope)
          val shared =
            FirJvmSessionFactory.createSharedLibrarySession(
              rootName,
              registrars,
              configuration.languageVersionSettings,
              context,
            )
          val library =
            FirJvmSessionFactory.createLibrarySession(
              shared,
              libraryList.moduleDataProvider,
              registrars,
              configuration.languageVersionSettings,
              context,
            )
          ClasspathState(environment, librariesScope, libraryList, shared, library).also {
            state = it
          }
        }

    val perfManager = configuration.perfManager
    val sources = collectSources(configuration, environment.project, messageCollector)
    val allSources = sources.allFiles
    perfManager?.notifyPhaseFinished(PhaseType.Initialization)
    perfManager?.notifyPhaseStarted(PhaseType.Analysis)

    val context = FirJvmSessionFactory.Context(configuration, environment, classpath.librariesScope)
    val javaSourcesScope = environment.getSearchScopeForProjectJavaSources()
    val sessionsWithSources =
      SessionConstructionUtils.prepareSessions(
        allSources,
        configuration,
        rootName,
        JvmPlatforms.unspecifiedJvmPlatform,
        metadataCompilationMode = false,
        classpath.libraryList,
        registrars,
        isCommonSource = sources.isCommonSourceForLt,
        isScript = { false },
        fileBelongsToModule = sources.fileBelongsToModuleForLt,
        createMetadataSessionFactoryContextForHmppCommonLibrarySession = {
          error("warm compile is single-platform")
        },
        createSharedLibrarySession = { classpath.sharedSession },
        createLibrarySession = { classpath.librarySession },
        createSourceSession = { _, moduleData, isForLeafHmppModule, sessionConfigurator ->
          FirJvmSessionFactory.createSourceSession(
            moduleData,
            javaSourcesScope,
            createIncrementalCompilationSymbolProviders = { null },
            registrars,
            configuration,
            context,
            needRegisterJavaElementFinder = true,
            isForLeafHmppModule = isForLeafHmppModule,
            sessionConfigurator,
          )
        },
      )

    val outputs = sessionsWithSources.map { (session, files) ->
      val raw = session.buildFirViaLightTree(files, diagnosticsCollector, null)
      resolveAndCheckFir(session, raw, diagnosticsCollector)
    }
    outputs.runPlatformCheckers(diagnosticsCollector)
    @OptIn(DirectDeclarationsAccess::class)
    declaresNonPrivateInline = outputs.any { output ->
      output.fir.any { file -> file.declarations.any(::isNonPrivateInline) }
    }
    return JvmFrontendPipelineArtifact(
      AllModulesFrontendOutput(outputs),
      configuration,
      environment,
      diagnosticsCollector,
      allSources,
    )
  }

  /**
   * Registers the plugins' extensions in the project for this compile only, built from this
   * compile's configuration — the way 2.4 fills a new configuration's extension storage. Kept for
   * the project's lifetime, as stock 2.3 does, they would carry the first compile's configuration
   * (the Compose IR extension captures its message collector) into every later one.
   */
  private fun registerPlugins(configuration: CompilerConfiguration, project: Project) {
    pluginExtensions?.let { Disposer.dispose(it) }
    val owner = Disposer.newDisposable("warm-compiler-plugins")
    Disposer.register(keepAlive(), owner)
    pluginExtensions = owner
    val storage = CompilerPluginRegistrar.ExtensionStorage()
    for (registrar in plugins.registrars) {
      with(registrar) { storage.registerExtensions(configuration) }
    }
    for ((descriptor, extensions) in storage.registeredExtensions) {
      @Suppress("UNCHECKED_CAST")
      val point =
        project.extensionArea.getExtensionPoint(
          descriptor.extensionPointName as ExtensionPointName<Any>
        )
      for (extension in extensions) point.registerExtension(extension, owner)
    }
    for (disposable in storage.disposables) Disposer.register(owner) { disposable.dispose() }
  }

  // Direct access is fine here: these are the compile's own resolved source files.
  @OptIn(DirectDeclarationsAccess::class)
  private fun isNonPrivateInline(declaration: FirDeclaration): Boolean =
    when (declaration) {
      is FirRegularClass ->
        declaration.status.visibility != Visibilities.Private &&
          declaration.declarations.any(::isNonPrivateInline)
      is FirProperty ->
        inlineAndVisible(declaration) ||
          declaration.getter?.let {
            it.status.isInline && it.status.visibility != Visibilities.Private
          } == true ||
          declaration.setter?.let {
            it.status.isInline && it.status.visibility != Visibilities.Private
          } == true
      is FirFunction -> inlineAndVisible(declaration)
      else -> false
    }

  private fun inlineAndVisible(declaration: FirMemberDeclaration): Boolean =
    declaration.status.isInline && declaration.status.visibility != Visibilities.Private
}

internal class WarmCliPipeline(
  perf: PerformanceManager,
  private val plugins: LoadedPlugins,
  private val frontend: WarmFrontendPhase,
) : AbstractCliPipeline<K2JVMCompilerArguments>() {
  override val defaultPerformanceManager: PerformanceManager = perf

  override fun createCompoundPhase(
    arguments: K2JVMCompilerArguments
  ): CompilerPhase<PipelineContext, ArgumentsPipelineArtifact<K2JVMCompilerArguments>, *> =
    JvmConfigurationPipelinePhase then
      InjectPluginsPhase(plugins) then
      frontend then
      FrontendFilesForPluginsGenerationPipelinePhase() then
      JvmFir2IrPipelinePhase then
      JvmBackendPipelinePhase then
      JvmWriteOutputsPhase
}

/** The plugins' frontend phase, for [WarmCompilerEntry]: 2.3's needs the plugins themselves. */
internal fun warmFrontendPhase(keepAlive: () -> Disposable, plugins: LoadedPlugins) =
  WarmFrontendPhase(keepAlive, plugins)

/**
 * Runs one compile. 2.3's pipeline gives each compile a root disposable of its own and disposes it
 * at the end, which is harmless: the cached project hangs off [keepAlive], not off that root.
 */
internal fun runWarmPipeline(
  arguments: K2JVMCompilerArguments,
  plugins: LoadedPlugins,
  frontend: WarmFrontendPhase,
  messages: MessageCollector,
  @Suppress("UNUSED_PARAMETER") keepAlive: Disposable,
): ExitCode =
  WarmCliPipeline(K2JVMCompiler().defaultPerformanceManager, plugins, frontend)
    .execute(arguments, Services.EMPTY, messages)
