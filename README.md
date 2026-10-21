# rules_dokka

Bzlmod-first Bazel rules for generating Kotlin and Java API documentation with
[Dokka](https://kotlinlang.org/docs/dokka-introduction.html).

## Features

- Dokka 2.2.0 with pinned Maven dependencies
- HTML, GFM, and Javadoc output
- Native multi-module HTML aggregation
- Reusable `dokka_config` policy and plugin profiles
- Version-matched, in-process Dokka generation
- Hermetic configuration and declared inputs
- JVM dependency classpaths, samples, includes, suppressed files, and custom plugins
- A Bazel toolchain API for overriding the bundled Dokka distribution
- Analysis tests plus real documentation-generation tests

## Requirements

- Bazel 8.4.2 or newer
- A Java 17 or newer tool runtime
- Bzlmod

`WORKSPACE` setup is intentionally unsupported.

## Setup

Add the module to your `MODULE.bazel`:

```starlark
bazel_dep(name = "rules_dokka", version = "<release>")
```

The module registers its bundled Dokka 2.2.0 toolchain automatically. A
toolchain registered by the root module takes precedence if you need another
version.

## Usage

```starlark
load("@rules_dokka//dokka:defs.bzl", "dokka")

dokka(
    name = "api_docs",
    srcs = glob([
        "src/main/java/**/*.java",
        "src/main/kotlin/**/*.kt",
    ]),
    deps = [
        "//lib:api",
    ],
    format = "html",
    includes = ["module.md"],
    module_name = "Example API",
)
```

The target produces one directory artifact named after the target. If all sources
are suppressed, the directory is empty. HTML is the default output format. Set
`format` to `gfm` or `javadoc` for the other bundled formats.

Without a target-level `config`, `dokka` uses the default `dokka_config`
provided by its selected toolchain. The bundled default sets
`offline_mode = True`, preventing network access during the Dokka action.

### Configuration

Use a target-level `dokka_config` to replace the selected toolchain's default
policy. The same override can be shared across multiple targets:

```starlark
load("@rules_dokka//dokka:defs.bzl", "dokka", "dokka_config")

dokka_config(
    name = "public_api_config",
    documented_visibilities = [
        "PUBLIC",
        "PROTECTED",
    ],
    fail_on_warning = True,
    jdk_version = 17,
    language_version = "2.1",
    no_jdk_link = True,
    no_stdlib_link = True,
    report_undocumented = True,
    skip_deprecated = True,
)

dokka(
    name = "client_docs",
    srcs = glob(["client/src/**/*.kt"]),
    config = ":public_api_config",
    module_name = "Client API",
)

dokka(
    name = "server_docs",
    srcs = glob(["server/src/**/*.kt"]),
    config = ":public_api_config",
    module_name = "Server API",
)
```

A configuration owns analysis, visibility, warning, linking, filtering,
source-set, and custom-plugin settings. `format`, `module_name`, `module_path`,
`module_version`, `srcs`, `deps`, `includes`, `samples`, and
`suppressed_files` remain on each documentation target.

Configuration selection is whole-target replacement rather than per-attribute
merging: a target-level `config` takes precedence when present; otherwise the
toolchain's `default_config` is used.

### Multi-module documentation

`dokka_multi_module` uses Dokka's native all-modules and templating plugins to
generate one project index, process each module's delayed templates, and place
the finalized modules under the same output:

```starlark
load(
    "@rules_dokka//dokka:defs.bzl",
    "dokka",
    "dokka_multi_module",
)

dokka(
    name = "api_docs",
    srcs = glob(["api/src/**/*.kt"]),
    config = ":public_api_config",
    module_name = "API",
    module_path = "api",
)

dokka(
    name = "data_docs",
    srcs = glob(["data/src/**/*.kt"]),
    config = ":public_api_config",
    module_name = "Data",
    module_path = "data",
)

dokka_multi_module(
    name = "docs",
    config = ":public_api_config",
    modules = [
        ":api_docs",
        ":data_docs",
    ],
    title = "Project API Reference",
)
```

The `docs` directory contains the aggregate `index.html` plus
`api/index.html`, `data/index.html`, and their supporting files. The individual
HTML targets remain usable as standalone, fully finalized documentation; their
delayed-template outputs are requested only when an aggregate depends on them.

Both module attributes are optional. `module_name` defaults to the target name.
`module_path` defaults to the target's full Bazel package path, preserving
nested project structure; a target in the root package falls back to its target
name. Use either attribute to override its derived value. Module paths must be
portable relative paths, unique within an aggregate, and must not collide with
Dokka's root assets or index files. Only HTML `dokka` targets can be aggregated.

The aggregate rule uses the same precedence and consumes the selected
configuration's `offline_mode`, `plugins`, and `plugins_configuration` settings.
Source-generation settings such as `fail_on_warning`,
`suppress_inherited_members`, and `suppress_obvious_functions` apply to the
individual `dokka` module targets instead. Root-level Markdown remains
target-specific through `includes`.

### Common options

```starlark
dokka_config(
    name = "api_docs_config",
    api_version = "2.1",
    documented_visibilities = [
        "PUBLIC",
        "PROTECTED",
    ],
    fail_on_warning = True,
    jdk_version = 17,
    language_version = "2.1",
    no_jdk_link = True,
    no_stdlib_link = True,
    report_undocumented = True,
    skip_deprecated = True,
    skip_empty_packages = True,
    suppress_annotated_with = ["com.example.InternalApi"],
)

dokka(
    name = "api_docs",
    srcs = glob(["src/**/*.kt"]),
    config = ":api_docs_config",
    module_name = "Example API",
    module_version = "1.0.0",
    samples = glob(["samples/**/*.kt"]),
)
```

See [the Dokka CLI configuration reference](https://kotlinlang.org/docs/dokka-cli.html)
for the meaning of these options.

### Custom plugins

`plugins` accepts Java targets. Their transitive runtime jars are appended to
the plugin classpath for the selected format. Plugins and their configuration
belong on `dokka_config`:

```starlark
dokka_config(
    name = "api_docs_config",
    plugins = ["@my_maven//:com_example_my_dokka_plugin"],
    plugins_configuration = {
        "com.example.MyPlugin": "{\"enabled\":true}",
    },
)

dokka(
    name = "api_docs",
    srcs = glob(["src/**/*.kt"]),
    config = ":api_docs_config",
)
```

Values in `plugins_configuration` are JSON strings expected by the named
plugin.

### Custom Dokka toolchain

Resolve the desired Dokka, Jackson, and plugin artifacts in the root module,
build a version-matched generator, define a toolchain, and register it:

```starlark
# MODULE.bazel
bazel_dep(name = "rules_dokka", version = "<release>")
bazel_dep(name = "rules_java", version = "9.3.0")
bazel_dep(name = "rules_jvm_external", version = "7.1")

maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    name = "custom_dokka",
    artifacts = [
        "com.fasterxml.jackson.core:jackson-databind:<jackson-version>",
        "org.freemarker:freemarker:2.3.32",
        "org.jetbrains.dokka:all-modules-page-plugin:<version>",
        "org.jetbrains.dokka:analysis-kotlin-symbols:<version>",
        "org.jetbrains.dokka:dokka-base:<version>",
        "org.jetbrains.dokka:dokka-cli:<version>",
        "org.jetbrains.kotlinx:kotlinx-html-jvm:0.9.1",
    ],
    repositories = ["https://repo1.maven.org/maven2"],
)
use_repo(maven, "custom_dokka")

register_toolchains("//tools:dokka_toolchain")
```

```starlark
# tools/BUILD.bazel
load(
    "@rules_dokka//dokka:defs.bzl",
    "dokka_config",
    "dokka_generator",
    "dokka_toolchain",
)

dokka_config(
    name = "default_config",
)

dokka_generator(
    name = "dokka_generator",
    dokka_cli = "@custom_dokka//:org_jetbrains_dokka_dokka_cli",
    jackson_databind = "@custom_dokka//:com_fasterxml_jackson_core_jackson_databind",
)

dokka_toolchain(
    name = "dokka_toolchain",
    default_config = ":default_config",
    generator = ":dokka_generator",
    html_plugins = [
        "@custom_dokka//:org_freemarker_freemarker",
        "@custom_dokka//:org_jetbrains_dokka_analysis_kotlin_symbols",
        "@custom_dokka//:org_jetbrains_dokka_dokka_base",
        "@custom_dokka//:org_jetbrains_kotlinx_kotlinx_html_jvm",
    ],
    multi_module_plugins = [
        "@custom_dokka//:org_jetbrains_dokka_all_modules_page_plugin",
    ],
)
```

Provide `gfm_plugins` and `javadoc_plugins` too if targets select those formats.
Provide `multi_module_plugins` to use `dokka_multi_module`; the all-modules
artifact contributes its templating and Markdown dependencies transitively.
The generator must be built against the same Dokka release used by the
toolchain because it invokes Dokka's generator API directly.
For reproducible builds, pin the custom Maven installation with a
`maven_install.json` lock file.

## Migrating from the original rules

The modernization removes the `WORKSPACE` macros, centralizes default
configuration in the Dokka toolchain, and normalizes rule attributes:

| Old attribute | New attribute |
| --- | --- |
| `moduleName` | `module_name` |
| `moduleDisplayName` | `source_set_display_name` |
| `offlineMode` | `offline_mode` |
| `failOnWarning` | `fail_on_warning` |
| `noStdlibLink` | `no_stdlib_link` |
| `noJdkLink` | `no_jdk_link` |
| `jdkVersion` | `jdk_version` |
| `skipEmptyPackages` | `skip_empty_packages` |
| `skipDeprecated` | `skip_deprecated` |
| `reportUndocumented` | `report_undocumented` |
| `includeNonPublic` | `documented_visibilities` |

The old rule defaulted to Javadoc; the new rule defaults to HTML. Select
`format = "javadoc"` to retain the prior output format. `plugins` now adds
custom plugins to the selected built-in format.

## Development

```console
bazel test //...
bazel run //:buildifier.check

# After changing MODULE.bazel's Maven artifacts:
REPIN=1 bazel run @unpinned_rules_dokka_dependencies//:pin

cd e2e/smoke
bazel build //:docs
```
