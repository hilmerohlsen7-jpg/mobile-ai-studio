# Update log

Newest first. Every entry is one build pushed to `main`.

## 2026-11-24
- File Manager: tick boxes on the app rows (App manager) with a bar to back up or uninstall all ticked apps in one go
- File Manager: **Configure buttons** (choose which buttons show on the side strip), **Tweaks** (show hidden files, compact rows, start sort order) and **Add shortcut** for the current folder (shows on Home)
- File Manager: keyboard shortcuts for a connected keyboard: Ctrl+A select all, Ctrl+C clipboard, Ctrl+V paste, Ctrl+F find, Ctrl+N or F7 new folder, F2 rename, F5 copy, F6 move, F8 or Delete delete, F9 refresh, Backspace up, Tab switch panel
- LAN servers: new tick box "Also allow old SMB1" for very old routers and NAS boxes (newer SMB2/3 stays the default)

## 2026-11-23 (c)
- File Manager and Browser no longer appear in the phone's app list on install: they are added only when you tap "Add to desktop" next to them
- Removed Duck.ai from the left menu
- Theme: new "Button shape" choice (Square, Rounded, Pill) for the menu buttons, rows and cards
- Downloads: the browser's Downloads now opens the same Downloads screen as the AI side (one list, one set of settings); a browser download just shows "Added to Downloads"
- Browser: new Bing logo in the search engine list

## 2026-11-23 (b)
- Browser: YouTube floating window now plays the video in the app's own small player (no YouTube bar, smooth), using public Piped/Invidious services to find the video file. The list of services is instances.json in this repo: the app reads the newest copy at start, so a broken service can be swapped without a new APK. If no service answers, it falls back to the shrunken page

## 2026-11-23
- Browser: floating video window for YouTube now plays YouTube's own embed player of the same video (no header, starts where you were) and hands the position back when you expand it. Other sites scroll the page to the video instead. Videos playing in the normal page also shrink when you press Home

## 2026-11-22 (f)
- Browser: picture-in-picture. Play a video full screen, press Home, and it keeps playing in a small floating window

## 2026-11-22 (e)
- Browser: videos can go full screen like the YouTube app: all bars and the system bars hide, the phone turns sideways, and Back (or the video's own button) leaves full screen

## 2026-11-22 (d)
- Browser home: shortcut tiles show each website's own icon (letter until it loads); the search bar has the engine's icon with a drop-down arrow
- Tapping the engine icon opens Firefox's "This time search in:" list (engine icons, Bookmarks, Tabs, History, Search settings); picking an engine uses it for the next search only
- Search settings: added Google Lens, Home screen widget and Show trending suggestions rows, and the "Address bar - Firefox Suggest" heading, like Firefox (Lens, widget and trending switches are saved but do nothing yet)

## 2026-11-22 (c)
- Browser: tab cards in the tab tray now show a small picture of the page (not for private tabs)

## 2026-11-22 (b)
- Browser: search suggestions under the address bar (from your search engine, bookmarks, history, recent searches and the clipboard, each controlled by its setting)
- Browser: the "expanded" toolbar option now puts a bottom bar with back, forward, reload, tabs and menu

## 2026-11-22
- Browser redone to follow Firefox for Android: new bottom-sheet menu, full Settings list (Search, Tabs, Homepage, Customise, Accessibility, Language, Private browsing, HTTPS-Only, DNS over HTTPS, Enhanced Tracking Protection, Site settings, Delete browsing data, Open links in apps, Downloads, Extensions, Remote debugging), History, Bookmarks, Downloads and Extensions pages
- Private tabs (separate Normal/Private tab tray, nothing saved to history), grid tab tray, recently closed tabs
- Search engine picker with add-your-own engines, voice search button, theme light/dark/follow device, "opening screen" (homepage, last tabs, or homepage after four hours)
- Not included, they need Mozilla's servers or are not possible here: Firefox account sync, built-in VPN, stories/sponsored shortcuts, AI controls/page summaries/translations, Firefox Labs, camera sign-in. Not tested on a phone yet

## 2026-11-19
- Make this site an app: a "Shortcut style" choice. "Old style" uses the launcher's older shortcut method, which may drop the ring around the icon on some phones
- Make this site an app: you choose "Included" or "Excluded (full screen)" for the top bar in the dialog; an excluded bar is gone completely, with no button left on screen
- Browser and web apps: a button (and menu entry) hides the top bar; a small arrow at the top right brings it back, and it is remembered per web app
- File Manager and Browser are now their own entries in the phone's app list, with full-fill icons like the main app icon (no ring); the "Add to desktop" buttons point you there. Web apps stay as pinned shortcuts
- Home-screen icons (Browser, File Manager, web apps) now fill the launcher's whole icon shape with no gap or ring, with the glyph at the same size as before
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


## 2026-11-24 (batch 2-3)
- Checksums (MD5, SHA-1, SHA-256) in the file details box.
- Extract now also opens 7z and RAR files (into a new folder; passwords supported).
- Zip, 7z and RAR files now open like folders (tap them); Back leaves the archive. Items are unpacked to a temporary copy; copy files out to keep them.
- Vault and DLNA rows show counts (vaults, devices, items per DLNA folder).
- Removed the experimental graphics-chip (Vulkan) option: it crashed on the Redmi and the build was giving garbled replies.
- Hold menu: "Open as archive" for many kinds of archives and packages (zip, rar, 7z, tar, gz, bz2, xz, iso, deb, rpm, cab, docx/xlsx/jar/epub and more). Disk images (vhd, vmdk, qcow, dmg, ntfs, ext4...) and split parts (.001) are listed but may not open.
- Open as archive also joins split parts (.001), finds the archive inside self-extracting .exe files, and reads zstd.
