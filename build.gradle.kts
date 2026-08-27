// Root build script — the repo has no shared root plugin block; module-level
// scripts own their builds. This file only hosts the cross-module boundary
// check (A6).

tasks.register("checkFeatureBoundaries") {
    group = "verification"
    description = "Fails if any feature-* module gains a dependency on another feature-* module (architecture.md §2, CR-6/A6)."
    doLast {
        val featureModules = rootProject.subprojects
            .filter { it.name.startsWith("feature-") }
            .map { it.path }
            .toSet()

        val violations = rootProject.subprojects
            .filter { it.name.startsWith("feature-") }
            .flatMap { module ->
                val deps = runCatching {
                    module.configurations.getByName("implementation")
                        .dependencyConstraints
                }.getOrDefault(emptySet())
                deps.mapNotNull { dep ->
                    val target = dep.name
                    target.takeIf { it in featureModules && it != module.path }
                }
            }
            .distinct()
            .sorted()

        if (violations.isNotEmpty()) {
            error(
                "Feature-to-feature dependencies must not exist (CR-6/A6):\n" +
                    violations.joinToString("\n") { "  $it" } +
                    "\nMove shared types behind core contracts and bind implementations in :app.",
            )
        }
        logger.lifecycle("checkFeatureBoundaries: no feature-to-feature edges (${featureModules.size} feature modules).")
    }
}