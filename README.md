# Mobile AI Studio

An Android app that runs an AI model on the phone itself (llama.cpp), with no internet needed after the model is downloaded.

## Features

**AI chat**
- Runs a local model on the phone with llama.cpp, no internet needed after the model download
- Model list: download a starter model or import your own .gguf
- Projects, skills, and adding text/code files to a project
- Camera and file attachments, web search, weather
- Voice and the wake word "Hey Ajax"
- Connectors to outside services

**File Manager (modelled on X-plore)**
- Two panes with a tree view: folders expand in place, the teal square follows your selection
- Long-press menus, Trash and Undo (nothing is deleted for good; Undo survives restarts)
- Root, SSH, DLNA, WiFi file sharing, Share to PC (WebDAV) and web storage (Google Drive, Dropbox, OneDrive)
- Vault with catalogs
- File sync tasks: copy, full, two-way and move modes, schedule, servers and Google Drive as source or target, never deletes on servers
- App manager: installed and system apps, uninstall
- Storage bars and folder/file counts on roots and folders
- File icons: image, video, PDF and APK previews, music note, zip and extension badges
- Loading wheels on slow folders

**Look**
- Claude-like theme with an accent colour hue wheel
- Glowing outline icons
- App icon colour and emblem change in place, no extra launcher icon

See [CHANGELOG.md](CHANGELOG.md) for what changed in each update.

## Get the app

1. Open the **Releases** page of this repo on your phone and download `MobileAIStudio.apk` from the **latest build**.
2. Open the file. Android will ask to allow installs from this source: allow it, then tap Install.
3. Open Mobile AI Studio and download the starter model (about 400 MB, Wi-Fi recommended).

Every push to `main` rebuilds the app (about 10-15 minutes). New builds install over the old one and keep your projects.

## Build notes

- `app/src/main/cpp/mobileai.cpp` is the small native wrapper around llama.cpp. llama.cpp itself is fetched by the workflow (pinned to the tag in `.github/workflows/build.yml`).
- `app/src/main/assets/index.html` is the whole screen. The Kotlin code in `app/src/main/java` hosts it and talks to the engine.
- `release.keystore` is a fixed personal signing key, so updates install over older versions. Keep this repository private.
