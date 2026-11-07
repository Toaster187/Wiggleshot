# Wiggleshot

Wiggleshot is an Android app that lets you add animated wiggle effects to your photos using AI — powered by the Gemini API.

> **Open Source** — Licensed under GPL v3.0 with attribution. See [LICENSE.md](./LICENSE.md) for details.

## Features

- Animate still photos with a wiggle/parallax effect
- Gemini AI integration for depth estimation
- Clean Material 3 UI built with Jetpack Compose
- CameraX support for in-app capture
- Simultaneous dual-lens capture (e.g. Pixel 9: 1x + 0.5x) with automatic lens matching:
  scale, rotation, offset and colour of the 0.5x lens are measured on every shot so it lines
  up with the 1x lens — see [docs/LENS_MATCHING.md](./docs/LENS_MATCHING.md)
- Night mode (aligned, motion-robust multi-frame merge) and a measured 3D-strength rating per
  capture — tips for strong wiggles: [docs/3D_GUIDE.md](./docs/3D_GUIDE.md)
- Diagnostics: raw frame / metadata export and a HAL zoom test that compares the matching
  with the one the Pixel camera pipeline uses itself

## Getting Started

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device

Unit tests (including the lens alignment accuracy tests): `./gradlew testDebugUnitTest`

## License

This project is licensed under the **GPL v3.0 with Attribution**. You are free to use, modify, and distribute this code as long as:

- Your project is also open source
- You link back to this repository: [github.com/Toasterfritzi/Wiggleshot](https://github.com/Toasterfritzi/Wiggleshot)

See [LICENSE.md](./LICENSE.md) for the full license text and third-party licenses.
