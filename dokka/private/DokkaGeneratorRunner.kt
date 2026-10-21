package rules.dokka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.DokkaConfigurationImpl
import org.jetbrains.dokka.DokkaGenerator
import org.jetbrains.dokka.DokkaModuleDescriptionImpl
import org.jetbrains.dokka.GlobalDokkaConfiguration
import org.jetbrains.dokka.PluginConfigurationImpl
import org.jetbrains.dokka.apply
import org.jetbrains.dokka.defaultLinks
import org.jetbrains.dokka.utilities.DokkaConsoleLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Runs Dokka in-process with paths resolved inside the Bazel action's execution root.
 *
 * Normal configurations use Dokka's documented JSON parser. Multi-module manifests are converted
 * to typed module descriptions because Dokka does not document the aggregate `modules` JSON
 * representation.
 */
object DokkaGeneratorRunner {
    private const val MULTI_MODULE_MODE = "rules_dokka_multi_module"
    private val objectMapper = ObjectMapper()

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "usage: DokkaGeneratorRunner CONFIGURATION" }

        val root = objectMapper.readTree(Paths.get(args[0]).toFile()) as ObjectNode
        val configuration =
            if (root.path("runnerMode").asText() == MULTI_MODULE_MODE) {
                multiModuleConfiguration(root)
            } else {
                standardConfiguration(root)
            }
        Files.createDirectories(configuration.outputDir.toPath())
        DokkaGenerator(configuration, DokkaConsoleLogger()).generate()
        // Dokka skips analysis cleanup when there is nothing to document.
        exitProcess(0)
    }

    private fun standardConfiguration(root: ObjectNode): DokkaConfiguration {
        absolutizeField(root, "outputDir")
        absolutizeOptionalField(root, "cacheRoot")
        absolutizeArray(root, "includes")
        absolutizeArray(root, "pluginsClasspath")

        val sourceSets = root.path("sourceSets")
        if (sourceSets.isArray) {
            sourceSets.forEach { sourceSetNode ->
                val sourceSet = sourceSetNode as ObjectNode
                listOf(
                    "classpath",
                    "includes",
                    "samples",
                    "sourceRoots",
                    "suppressedFiles",
                ).forEach { field -> absolutizeArray(sourceSet, field) }

                val sourceLinks = sourceSet.path("sourceLinks")
                if (sourceLinks.isArray) {
                    sourceLinks.forEach { sourceLinkNode ->
                        absolutizeOptionalField(sourceLinkNode as ObjectNode, "localDirectory")
                    }
                }
            }
        }

        val json = objectMapper.writeValueAsString(root)
        val configuration =
            DokkaConfigurationImpl(json).apply(GlobalDokkaConfiguration(json))
        configuration.sourceSets.forEach { sourceSet ->
            @Suppress("UNCHECKED_CAST")
            val externalDocumentationLinks =
                sourceSet.externalDocumentationLinks
                    as MutableSet<DokkaConfiguration.ExternalDocumentationLink>
            externalDocumentationLinks.addAll(defaultLinks(sourceSet))
        }
        return configuration
    }

    private fun multiModuleConfiguration(root: ObjectNode): DokkaConfiguration {
        val outputDirectory = absolutePath(requiredText(root, "outputDir"))
        val moduleNodes = root.path("modules")
        require(moduleNodes.isArray && !moduleNodes.isEmpty) {
            "Multi-module Dokka manifest must contain modules"
        }

        val modules =
            moduleNodes.map { moduleNode ->
                val module = moduleNode as ObjectNode
                val portableRelativeOutput =
                    requiredText(module, "relativePathToOutputDirectory")
                val relativeOutput = Paths.get(portableRelativeOutput)
                require(
                    !relativeOutput.isAbsolute &&
                        outputDirectory.resolve(relativeOutput).normalize().startsWith(outputDirectory),
                ) {
                    "Module output escapes the aggregate documentation directory: $relativeOutput"
                }
                DokkaModuleDescriptionImpl(
                    requiredText(module, "name"),
                    PortableModulePath(relativeOutput, portableRelativeOutput),
                    files(module.path("includes")),
                    absolutePath(requiredText(module, "sourceOutputDirectory")).toFile(),
                )
            }

        val pluginNodes = root.path("pluginsConfiguration")
        val pluginConfigurations =
            if (pluginNodes.isArray) {
                pluginNodes.map { pluginNode ->
                    PluginConfigurationImpl(
                        requiredText(pluginNode, "fqPluginName"),
                        DokkaConfiguration.SerializationFormat.valueOf(
                            requiredText(pluginNode, "serializationFormat"),
                        ),
                        requiredText(pluginNode, "values"),
                    )
                }
            } else {
                emptyList()
            }

        // AllModulesPageGeneration ignores the single-module warning and suppression settings.
        return DokkaConfigurationImpl(
            requiredText(root, "moduleName"),
            optionalText(root, "moduleVersion"),
            outputDirectory.toFile(),
            optionalFile(root, "cacheRoot"),
            root.path("offlineMode").asBoolean(),
            emptyList(),
            files(root.path("pluginsClasspath")).toList(),
            pluginConfigurations,
            modules,
            false,
            false,
            true,
            files(root.path("includes")),
            false,
            root.path("finalizeCoroutines").asBoolean(true),
        )
    }

    private fun files(nodes: JsonNode): Set<File> =
        if (nodes.isArray) {
            nodes.mapTo(linkedSetOf()) { node -> absolutePath(node.asText()).toFile() }
        } else {
            emptySet()
        }

    private fun absolutizeArray(
        node: ObjectNode,
        field: String,
    ) {
        val values = node.path(field)
        if (!values.isArray) {
            return
        }
        val absoluteValues = objectMapper.createArrayNode()
        values.forEach { value -> absoluteValues.add(absolutePath(value.asText()).toString()) }
        node.set<ArrayNode>(field, absoluteValues)
    }

    private fun absolutizeField(
        node: ObjectNode,
        field: String,
    ) {
        node.put(field, absolutePath(requiredText(node, field)).toString())
    }

    private fun absolutizeOptionalField(
        node: ObjectNode,
        field: String,
    ) {
        optionalText(node, field)
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> node.put(field, absolutePath(value).toString()) }
    }

    private fun optionalFile(
        node: ObjectNode,
        field: String,
    ): File? =
        optionalText(node, field)
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> absolutePath(value).toFile() }

    private fun requiredText(
        node: JsonNode,
        field: String,
    ): String =
        requireNotNull(optionalText(node, field)?.takeIf(String::isNotEmpty)) {
            "Missing required Dokka manifest field: $field"
        }

    private fun optionalText(
        node: JsonNode,
        field: String,
    ): String? = node.get(field)?.takeUnless(JsonNode::isNull)?.asText()

    private fun absolutePath(path: String): Path = Paths.get(path).toAbsolutePath().normalize()

    /**
     * Preserves a URL-compatible module path when Dokka renders aggregate links.
     *
     * Dokka 2.2's all-modules-page plugin appends `relativePathToOutputDirectory` directly to a
     * string when it creates module URLs. A regular [File] renders that path with backslashes on
     * Windows. File operations still use the native path stored by [File]; only its string
     * representation remains portable.
     */
    private class PortableModulePath(
        nativePath: Path,
        private val portablePath: String,
    ) : File(nativePath.toString()) {
        override fun toString(): String = portablePath

        private companion object {
            private const val serialVersionUID = 1L
        }
    }
}
