# Update log

Newest first. Every entry is one build pushed to `main`.

## 2026-11-19
- Make this site an app: a switch "Hide the top bar (full screen)" in the dialog; the web app then opens without the top bar (tap the small arrow at the top right to bring it back)
- Browser and web apps: a button (and menu entry) hides the top bar; a small arrow at the top right brings it back, and it is remembered per web app
- Home-screen icons (Browser, File Manager, web apps) now fill the whole icon shape instead of sitting inside a gap
- Browser: "Make this site an app on the home screen" in the menu. Pick a name, use the site's own icon (found automatically) or choose your own picture, and the home-screen icon opens that website like an app, in its own window with just a back button, the site name and the menu
- Browser: removed the empty gap above the address bar
- Groq (free), OpenRouter (free models) and Mistral (free tier) added in Settings, Connectors, next to Gemini, Claude, ChatGPT and Grok

## 2026-11-18
- ChatGPT (OpenAI key) added next to Gemini, Claude and Grok in Settings, Connectors; Duck.ai shortcut in the left panel and the browser menu
- Browser: uses the app's own downloader (Downloads in the browser menu, with pause, resume and save to the Downloads folder), follows the app theme and accent colour, and all emoji are replaced with vector icons
- Left panel: "Add to desktop" buttons next to File Manager and Browser
- Browser: graphics workaround wraps the game's background workers again, now with file requests resolved against the real page address
- Browser: graphics workaround now also patches the game's background workers as they download
- Browser: game mode now covers the whole screen (status bar, notch and bottom bar); graphics workaround no longer touches background workers
- Browser: Graphics test, and a "Graphics limit workaround" switch for games that refuse to start on phones whose graphics report 256 uniform vectors
- Browser: Desktop site switch in the burger menu
- Browser: burger menu at the top right, bottom bar removed. Controller button starts Fullscreen Mode with an on-screen controller; its layout, size and transparency are changed from the burger menu
- Browser: downloads (saved to your Download folder), bookmarks and history (star button at the bottom)
- New Browser (Firefox engine) with tabs and an Add-ons page: uBlock Origin, Bitwarden, Dark Reader, SponsorBlock, or any add-on link. Opens from the left panel.

## 2026-11-17
- Fix: file list no longer shifts while scrolling (rows keep a fixed height, and icons and counts load only once you stop scrolling)
- Fix: big folders no longer jump back to the top while you scroll (the list keeps its position when icons and counts load)
- File-type icons in Vault, DLNA and server folder lists; tighter indent in every Home list
- Smaller icons, tighter indent, File Manager title, remove top Downloads button
- Smaller file manager icons
- File icons: image/video/PDF/APK previews with size, music note, zip and extension badges for every type

## 2026-11-16
- Storage free/total bar on root rows; folder and file counts for roots and every folder
- Accent colour: hue wheel with brightness slider
- Glowing thin-outline icons in the file manager
- Vector icons for Root/SSH/DLNA/cloud, Theme page, Downloads and Files in left panel, in-place app icon switching
- Accent colour picker, icon bodies, app icon colour and emblem, uninstalled apps leave the list

## 2026-11-15
- Fix app row layout with long version; loading wheels for server folders, DLNA and Vault
- App manager: loading wheel, async app list; add REQUEST_DELETE_PACKAGES so Uninstall works
- App manager inline: Installed and System lists under Home
- File sync: task row box, run icon, Remove-only hold menu
- File sync: servers and Google Drive as From/To (copy, never delete on servers)
- File sync as inline tasks: Add task, Sync task/Logs/Help panel, modes, schedule
- Long-press menus for every Home function; no text selection in the file manager
- Vault catalogs drop down like folders (arrow, expand/collapse without re-asking the password)
- Vault inline in Home: catalogs, Options (Help/New), New catalog form, long-press menu
- DLNA inline in the Home tree: servers and folders expand, square follows selection
- Home: teal square follows the selected item (features, roots, drives, folders, files); title bar shows selection
- WiFi file sharing: Searching row, Server toggle, devices, Add device
- Show/hide per panel; box around each opened Home feature
- Root rows like other folders; Share to PC (WebDAV network drive) server

## 2026-11-14
- File manager: expand Web storage in the Home tree, teal box around open folders, long-press menus
- Web storage: pick Google Drive/Dropbox/OneDrive through Android's storage picker, with explanations

