"""Implementation of the `dokka_multi_module` rule."""

load(":providers.bzl", "DokkaConfigInfo", "DokkaInfo")

_DOKKA_TOOLCHAIN_TYPE = Label("//dokka:toolchain_type")
_DOKKA_RESERVED_ROOT_ENTRIES = [
    "images",
    "index.html",
    "navigation.html",
    "package-list",
    "scripts",
    "styles",
    "ui-kit",
]
_WINDOWS_INVALID_PATH_CHARACTERS = ["<", ">", ":", "\"", "|", "?", "*"]

def _validate_module_path(module_path, label):
    segments = module_path.split("/")
    if (
        not module_path or
        module_path.startswith("/") or
        "\\" in module_path or
        any([
            not segment or segment == "." or segment == ".."
            for segment in segments
        ]) or
        any([
            character in module_path
            for character in _WINDOWS_INVALID_PATH_CHARACTERS
        ])
    ):
        fail(
            (
                "Dokka target {} has invalid module_path '{}': expected a portable, " +
                "relative path without empty, '.' or '..' segments."
            ).format(label, module_path),
        )

    root_entry = segments[0].lower()
    if root_entry in _DOKKA_RESERVED_ROOT_ENTRIES:
        fail(
            (
                "Dokka target {} has invalid module_path '{}': first segment '{}' is reserved " +
                "by Dokka's aggregate output."
            ).format(label, module_path, segments[0]),
        )

def _dokka_multi_module_impl(ctx):
    modules = []
    module_names = {}
    module_paths = {}
    module_outputs = []
    module_includes = []

    for target in ctx.attr.modules:
        module = target[DokkaInfo]
        if module.format != "html" or not module.partial_documentation:
            fail(
                "dokka_multi_module only accepts HTML `dokka` targets, but {} uses format '{}'.".format(
                    target.label,
                    module.format,
                ),
            )

        module_name = module.module_name
        if module_name in module_names:
            fail(
                "Dokka targets {} and {} both resolve to module name '{}'; set unique module_name values.".format(
                    module_names[module_name],
                    target.label,
                    module_name,
                ),
            )
        module_names[module_name] = target.label

        module_path = module.module_path
        _validate_module_path(module_path, target.label)
        if module_path in module_paths:
            fail(
                "Dokka targets {} and {} both resolve to module path '{}'; set unique module_path values.".format(
                    module_paths[module_path],
                    target.label,
                    module_path,
                ),
            )
        module_paths[module_path] = target.label

        includes = module.includes.to_list()

        # The generator maps this rules_dokka manifest to Dokka's typed
        # DokkaModuleDescription implementation. The integration test exercises
        # that version-matched contract end to end.
        modules.append({
            "includes": [include.path for include in includes],
            "name": module_name,
            "relativePathToOutputDirectory": module_path,
            "sourceOutputDirectory": module.partial_documentation.path,
        })
        module_outputs.append(module.partial_documentation)
        module_includes.extend(includes)

    toolchain = ctx.toolchains[_DOKKA_TOOLCHAIN_TYPE].dokka
    config = (
        ctx.attr.config[DokkaConfigInfo] if ctx.attr.config else toolchain.default_config
    )
    settings = config.settings
    if not toolchain.multi_module_plugins.to_list():
        fail("The selected Dokka toolchain does not provide multi-module plugins.")

    plugin_jars = depset(
        transitive = [
            toolchain.plugins["html"],
            toolchain.multi_module_plugins,
            config.plugin_jars,
        ],
    )
    modules = sorted(
        modules,
        key = lambda module: module["relativePathToOutputDirectory"],
    )
    documentation = ctx.actions.declare_directory(ctx.label.name)
    configuration = ctx.actions.declare_file(ctx.label.name + ".dokka.json")
    ctx.actions.write(
        output = configuration,
        content = json.encode({
            "delayTemplateSubstitution": False,
            "finalizeCoroutines": True,
            "includes": [include.path for include in ctx.files.includes],
            "moduleName": ctx.attr.title or ctx.label.name,
            "modules": modules,
            "offlineMode": settings.offline_mode,
            "outputDir": documentation.path,
            "pluginsClasspath": [
                file.path
                for file in sorted(plugin_jars.to_list(), key = lambda file: file.path)
            ],
            "pluginsConfiguration": [
                {
                    "fqPluginName": plugin,
                    "serializationFormat": "JSON",
                    "values": settings.plugins_configuration[plugin],
                }
                for plugin in sorted(settings.plugins_configuration.keys())
            ],
            "runnerMode": "rules_dokka_multi_module",
            "sourceSets": [],
        }),
    )

    arguments = ctx.actions.args()
    arguments.add(configuration)
    ctx.actions.run(
        arguments = [arguments],
        executable = toolchain.generator,
        inputs = depset(
            direct = (
                [configuration] +
                ctx.files.includes +
                module_outputs +
                module_includes
            ),
            transitive = [plugin_jars],
        ),
        mnemonic = "DokkaMultiModule",
        outputs = [documentation],
        progress_message = "Generating multi-module Dokka documentation for %{label}",
        toolchain = _DOKKA_TOOLCHAIN_TYPE,
    )

    return [
        DefaultInfo(files = depset([documentation])),
        OutputGroupInfo(configuration = depset([configuration])),
    ]

_DOKKA_MULTI_MODULE_ATTRS = {
    "config": attr.label(
        doc = "Optional `dokka_config` overriding the toolchain default.",
        providers = [DokkaConfigInfo],
    ),
    "includes": attr.label_list(
        allow_files = [".md"],
        doc = "Markdown files displayed on the aggregate root page.",
    ),
    "modules": attr.label_list(
        allow_empty = False,
        doc = "HTML `dokka` targets to aggregate.",
        mandatory = True,
        providers = [DokkaInfo],
    ),
    "title": attr.string(
        doc = "Displayed aggregate title. Defaults to the target name.",
    ),
}

dokka_multi_module = rule(
    implementation = _dokka_multi_module_impl,
    attrs = _DOKKA_MULTI_MODULE_ATTRS,
    doc = "Generates native multi-module HTML documentation with Dokka.",
    toolchains = [_DOKKA_TOOLCHAIN_TYPE],
)
