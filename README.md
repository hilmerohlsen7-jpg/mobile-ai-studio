# Mobile AI Studio

An Android app that runs an AI model on the phone itself (llama.cpp), with no internet needed after the model is downloaded.

## Features

**AI chat**
- Runs a local model on the phone with llama.cpp, no internet needed after the model download
- Model list: download a starter model or import your own .gguf
- Projects: rename, clear chat, add text/code files, switch skills on or off per project
- Online models (Gemini, Claude, ChatGPT, Grok) when you add an API key; they show as "(online)" in the model list
- Duck.ai (DuckDuckGo AI chat) opens in the built-in browser
- Left panel shortcuts: Downloads, File Manager, Settings
- Camera and file attachments, web search, weather
- Voice and the wake word "Hey Ajax"
- Connectors to outside services

**Browser**
- Built-in Firefox-engine browser, set to use little memory, with tabs
- Uses the app's downloader: pause, resume, save to the Downloads folder
- Bookmarks and history
- Follows the app theme and accent colour
- Add File Manager and Browser to the home screen from the left panel
- Turn any website into an app on the home screen, with the site icon or your own picture
- Hide the browser top bar for a full-screen look
- Fullscreen controller overlay (sends keyboard keys) with movable buttons, size and transparency
- Add-ons page: install uBlock Origin, Bitwarden, Dark Reader, SponsorBlock or any Firefox add-on link

**File Manager**
- Two panes with a tree view: folders expand in place, the teal square follows your selection
- Long-press menus, Trash and Undo (nothing is deleted for good; Undo survives restarts)
- Root, SSH, DLNA, WiFi file sharing, Share to PC (WebDAV) and web storage (Google Drive, Dropbox, OneDrive)
- Vault with catalogs
- File sync tasks: copy, full, two-way and move modes, schedule, servers and Google Drive as source or target, never deletes on servers
- App manager: installed and system apps, uninstall
- Storage bars and folder/file counts on roots and folders
- Configure buttons, Tweaks, Add shortcut, keyboard shortcuts, tick boxes on app rows, old SMB1 option
- File icons: image, video, PDF and APK previews, music note, zip and extension badges
- Loading wheels on slow folders

**Look**
- Claude-like theme with an accent colour hue wheel
- Glowing outline icons
- App icon colour and emblem change in place, no extra launcher icon

## Not done yet

- Browser: add-on toolbar buttons and popups
- Folder counts and image/APK previews in Vault, DLNA and server folder lists (they have type icons now)
- Weather city bug

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
