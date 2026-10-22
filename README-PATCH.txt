Nuvio Native Recommendations compile fix

Contains the settings model resource import, the native recommendations UI converted to a LazyListScope extension matching the existing settings-page architecture, and the required string resource.

Apply from the repository root. This is a targeted patch and does not modify Home catalog chooser logic.

Validation: ZIP integrity and static source checks passed. A full Gradle compile could not be run in this environment because Gradle 9.4.1 is not cached and network access to services.gradle.org is unavailable. Run the GitHub Actions build after applying; do not treat this patch as build-verified.
