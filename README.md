# Mobile AI Studio

An Android app that runs an AI model on the phone itself (llama.cpp), with no internet needed after the model is downloaded.

Version 1: chat, projects, skills, adding text/code files to a project, and a model list (download or import your own .gguf).

## Get the app

1. Open the **Releases** page of this repo on your phone and download `MobileAIStudio.apk` from the **latest build**.
2. Open the file. Android will ask to allow installs from this source: allow it, then tap Install.
3. Open Mobile AI Studio and download the starter model (about 400 MB, Wi-Fi recommended).

Every push to `main` rebuilds the app (about 10-15 minutes). New builds install over the old one and keep your projects.

## Build notes

- `app/src/main/cpp/mobileai.cpp` is the small native wrapper around llama.cpp. llama.cpp itself is fetched by the workflow (pinned to the tag in `.github/workflows/build.yml`).
- `app/src/main/assets/index.html` is the whole screen. The Kotlin code in `app/src/main/java` hosts it and talks to the engine.
- `release.keystore` is a fixed personal signing key, so updates install over older versions. Keep this repository private.
