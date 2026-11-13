"""Reusable Dokka configuration."""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load(":providers.bzl", "DokkaConfigInfo")

DOCUMENTED_VISIBILITIES = [
    "INTERNAL",
    "PACKAGE",
    "PRIVATE",
    "PROTECTED",
    "PUBLIC",
]

def _dokka_config_attrs():
    """Returns attributes accepted by `dokka_config`."""
    return {
        "analysis_platform": attr.string(
            default = "jvm",
            doc = "Platform used for source analysis.",
            values = ["common", "js", "jvm", "native", "wasm"],
        ),
        "api_version": attr.string(
            doc = "Kotlin API version used for source analysis.",
        ),
        "documented_visibilities": attr.string_list(
            default = ["PUBLIC"],
            doc = "Declaration visibilities to document.",
        ),
        "fail_on_warning": attr.bool(
            doc = "Treat Dokka warnings as build failures. Errors always fail.",
        ),
        "jdk_version": attr.int(
            default = 8,
            doc = "JDK documentation version used for external links.",
        ),
        "language_version": attr.string(
            doc = "Kotlin language version used for source analysis.",
        ),
        "no_jdk_link": attr.bool(
            doc = "Disable links to JDK documentation.",
        ),
        "no_stdlib_link": attr.bool(
            doc = "Disable links to Kotlin standard-library documentation.",
        ),
        "offline_mode": attr.bool(
            default = True,
            doc = "Prevent Dokka from resolving remote package lists.",
        ),
        "plugins": attr.label_list(
            cfg = "exec",
            doc = "Additional Dokka plugin targets and their runtime dependencies.",
            providers = [JavaInfo],
        ),
        "plugins_configuration": attr.string_dict(
            doc = "Plugin class names mapped to JSON configuration strings.",
        ),
        "report_undocumented": attr.bool(
            doc = "Warn about visible declarations without documentation.",
        ),
        "skip_deprecated": attr.bool(
            doc = "Do not document deprecated declarations.",
        ),
        "skip_empty_packages": attr.bool(
            doc = "Do not generate pages for empty packages.",
        ),
        "source_set_display_name": attr.string(
            default = "JVM",
            doc = "Displayed name of the source set.",
        ),
        "source_set_name": attr.string(
            default = "main",
            doc = "Technical name of the source set.",
        ),
        "suppress_annotated_with": attr.string_list(
            doc = "Fully qualified annotation names whose declarations are omitted.",
        ),
        "suppress_inherited_members": attr.bool(
            doc = "Suppress inherited members that are not overridden.",
        ),
        "suppress_obvious_functions": attr.bool(
            default = True,
            doc = "Suppress obvious inherited and generated functions.",
        ),
    }

def dokka_settings(attrs):
    """Returns a struct containing reusable Dokka settings."""
    return struct(
        analysis_platform = attrs.analysis_platform,
        api_version = attrs.api_version,
        documented_visibilities = attrs.documented_visibilities,
        fail_on_warning = attrs.fail_on_warning,
        jdk_version = attrs.jdk_version,
        language_version = attrs.language_version,
        no_jdk_link = attrs.no_jdk_link,
        no_stdlib_link = attrs.no_stdlib_link,
        offline_mode = attrs.offline_mode,
        plugins_configuration = attrs.plugins_configuration,
        report_undocumented = attrs.report_undocumented,
        skip_deprecated = attrs.skip_deprecated,
        skip_empty_packages = attrs.skip_empty_packages,
        source_set_display_name = attrs.source_set_display_name,
        source_set_name = attrs.source_set_name,
        suppress_annotated_with = attrs.suppress_annotated_with,
        suppress_inherited_members = attrs.suppress_inherited_members,
        suppress_obvious_functions = attrs.suppress_obvious_functions,
    )

def validate_documented_visibilities(documented_visibilities):
    """Fails if a documented visibility value is invalid."""
    invalid_visibilities = [
        visibility
        for visibility in documented_visibilities
        if visibility not in DOCUMENTED_VISIBILITIES
    ]
    if invalid_visibilities:
        fail(
            "documented_visibilities contains invalid values {}. Expected values from {}.".format(
                invalid_visibilities,
                DOCUMENTED_VISIBILITIES,
            ),
        )

def _runtime_jars(dependencies):
    return depset(
        transitive = [
            dependency[JavaInfo].transitive_runtime_jars
            for dependency in dependencies
        ],
    )

def _dokka_config_impl(ctx):
    settings = dokka_settings(ctx.attr)
    validate_documented_visibilities(settings.documented_visibilities)
    return [DokkaConfigInfo(
        plugin_jars = _runtime_jars(ctx.attr.plugins),
        settings = settings,
    )]

dokka_config = rule(
    implementation = _dokka_config_impl,
    attrs = _dokka_config_attrs(),
    doc = "Defines policy and plugin settings required by Dokka documentation targets.",
)
