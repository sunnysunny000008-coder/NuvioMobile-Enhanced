Nuvio Native TMDB Empty Rows Fix + Native Taste Builder

Overlay these files onto the existing NuvioMobile-Enhanced repository.

Fix:
- Restores the native catalog key contract: Home chooser keys are `native_rec_<row key>`.
- Fixes native catalog registration and settings lookup to use the same stable key.
- Migrates any short-lived bare row keys while preserving saved enabled state, order, custom titles and row shape.

Taste Builder:
- Adds a local-only Build My Taste Profile section under Native TMDB Recommendations.
- Lets users select favorite genres and applies those preferences to native TMDB recommendation ranking.
- Does not modify HomeScreen, Gemini recommendations, the existing Home chooser UI, or other settings.

Extract this ZIP into the repository root, overwriting the listed source files. Review the changes before committing. Static checks passed, but a Gradle build could not be completed in this environment because Gradle 9.4.1 could not be downloaded (network DNS unavailable).
