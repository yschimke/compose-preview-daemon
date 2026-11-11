package ee.schimke.composeai.daemon.warm.impl

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys

/**
 * The friend paths of this compile (`-Xfriend-paths`): class directories whose `internal`
 * declarations the sources may use. The warm compile reads the module's other files from its class
 * directory rather than from source, so that directory has to be a friend, as Gradle makes a main
 * source set for its tests; without it, an edit to a file that calls an `internal` function of
 * another file fails with "Cannot access …: it is internal in file".
 */
internal fun friendPaths(configuration: CompilerConfiguration): List<String> =
  configuration.get(JVMConfigurationKeys.FRIEND_PATHS).orEmpty()
