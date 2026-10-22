NuvioMobile-Enhanced — Hero carousel rendering correction

This is a corrected v2 patch. It also adds the missing fillMaxHeight import that caused the prior GitHub Actions compilation error.

This is a corrective patch for the previously supplied hero-styles patch. Apply it
on top of the checkout where the Coverflow Poster Stack and Peek Carousel options
are already present. It changes only HomeHeroSection.kt and leaves the settings,
extended-info preference, and Home catalog/category chooser untouched.

Rendering changes:
- Coverflow: centered narrow poster, adjacent poster previews, tinted backdrop.
- Peek: wide rounded card with the next/previous card peeking from the edges.
- Reserved-space layout now recognizes both styles so the Home list does not overlap.

Apply from the repository root by extracting this ZIP with overwrite enabled.
Then inspect with:
  git diff --check
  git diff -- composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroSection.kt

The Android build has not been run in this environment. Use the existing GitHub
Actions workflow to build and verify the APK before treating the patch as validated.
