Nuvio Native TMDB Recommendations + Taste Builder combined patch

TARGET: NuvioMobile-Enhanced, branch enhanced

CHANGES
- Adds a dedicated Native TMDB Recommendations page under Settings > Integrations.
- Adds movie-themed Theaters icon entry.
- Adds genre, release era, original language, minimum TMDB rating, and popular/hidden-gem preferences.
- Applying the taste profile saves preferences and triggers recommendation refresh.
- Native catalog rows are included in the Home catalog settings list only while the master switch is enabled.
- Saved native row preferences (enabled state, custom title, order, row shape) are retained while hidden.
- Home native rows respect both the row-level switch and Home catalog chooser selection; custom titles are honored.
- Gemini implementation/settings are not intentionally modified.

INSTALL
1. Make a backup/commit of your current working tree first.
2. Extract this ZIP into the root of your NuvioMobile-Enhanced repository, preserving folders, and allow overwriting the listed files.
3. Review with: git diff --check
4. Build with your normal Android Gradle build workflow before committing.

IMPORTANT
This patch was prepared against the supplied NuvioMobile-Enhanced source archive. It has NOT been build-verified here because Gradle 9.4.1 distribution was not available offline. Do not commit until your build succeeds. If build errors occur, send the complete error output and the fix can be adjusted.
