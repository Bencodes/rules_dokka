package rules.dokka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Exercises the standalone runner's exit status in a separate JVM. */
object DokkaGeneratorRunnerTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Files.createTempDirectory(Paths.get(requireNotNull(System.getenv("TEST_TMPDIR"))), "dokka-exit-")
        val source = root.resolve("Api.kt")
        Files.writeString(source, "package fixture\nclass Api\n")
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator)
            .map { Paths.get(it).toAbsolutePath().toString() }
        val plugins = listOf(
            "org.jetbrains.dokka.base.DokkaBase",
            "org.jetbrains.dokka.analysis.kotlin.symbols.plugin.SymbolsAnalysisPlugin",
        ).map { Paths.get(Class.forName(it).protectionDomain.codeSource.location.toURI()).toString() }
        val mapper = ObjectMapper()
        val base = mapper.createObjectNode().apply {
            put("moduleName", "Exit status fixture")
            put("offlineMode", true)
            putArray("pluginsClasspath").also { jars -> plugins.forEach { jars.add(it) } }
            putArray("sourceSets").addObject().apply {
                putObject("sourceSetID").put("scopeId", "fixture").put("sourceSetName", "main")
                putArray("sourceRoots").add(source.toString())
                put("noJdkLink", true)
                put("noStdlibLink", true)
            }
        }

        fun run(name: String, expectedExit: Int, configure: (ObjectNode, Path) -> Unit): Path {
            val output = root.resolve(name)
            val config = base.deepCopy().apply { put("outputDir", output.toString()) }
            configure(config, output)
            val manifest = root.resolve("$name.json")
            mapper.writeValue(manifest.toFile(), config)
            val log = root.resolve("$name.log")
            val java = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            val process = ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", java).toString(),
                "-cp", classpath.joinToString(File.pathSeparator),
                "rules.dokka.DokkaGeneratorRunner", manifest.toString(),
            ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "$name timed out: ${Files.readString(log)}" }
                check(process.exitValue() == expectedExit) {
                    "$name exited ${process.exitValue()}, expected $expectedExit: ${Files.readString(log)}"
                }
            } finally {
                process.destroyForcibly().waitFor()
            }
            return output
        }

        run("write-error", 1) { config, output ->
            config.put("failOnWarning", false)
            Files.createDirectories(output.resolve("index.html"))
        }
        val writeErrorLog = Files.readString(root.resolve("write-error.log"))
        check(writeErrorLog.contains("ERROR: Failed to write")) { writeErrorLog }

        val empty = run("suppressed", 0) { config, _ ->
            (config["sourceSets"][0] as ObjectNode).putArray("suppressedFiles").add(source.toString())
        }
        check(Files.isDirectory(empty))
        Files.list(empty).use { check(!it.findAny().isPresent) }

        run("warning", 0) { config, _ ->
            (config["sourceSets"][0] as ObjectNode).put("reportUndocumented", true)
        }
        check(Files.readString(root.resolve("warning.log")).contains("WARN:"))

        run("strict-warning", 1) { config, _ ->
            config.put("failOnWarning", true)
            (config["sourceSets"][0] as ObjectNode).put("reportUndocumented", true)
        }
        check(Files.readString(root.resolve("strict-warning.log")).contains("DokkaException"))
    }
}
