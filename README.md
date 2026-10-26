# SHIELD TV debloat + Zen Home

Make your NVIDIA SHIELD TV fast again. One command removes the telemetry, promo apps and the
ad-filled home screen, and replaces it with **Zen Home**, a clean Apple TV-style launcher with
4K nature wallpapers.

![Zen Home on a SHIELD TV](docs/zen-home.jpg)

- **Safe and reversible.** Nothing is uninstalled, only disabled. A backup is taken first, and
  one command puts everything back.
- **Nothing important is touched.** AI upscaling, Dolby/audio, remotes, GeForce NOW, Chromecast,
  Google Assistant, screensaver and system updates all keep working.
- **Noticeably faster.** On the test SHIELD, swap use fell from 516 MB to 138 MB and I/O wait from
  66% to 0%. Zen Home uses ~65 MB in the background; the stock launcher plus its ad feed uses ~440 MB.

---

## Install

You need the SHIELD and a computer (Mac, Windows or Linux) on the **same network**.
It takes about 5 minutes. Nothing needs to be installed beforehand: the installer downloads
Google's official `adb` tool by itself if your computer doesn't have it.

### Step 1: Turn on Network debugging on the SHIELD

This lets your computer talk to the SHIELD. You only do it once.

1. On the SHIELD, open **Settings → Device Preferences → About**.
2. Scroll down to **Build** and click it **7 times**, until it says *"You are now a developer"*.
3. Go back to **Device Preferences → Developer options** and turn on **Network debugging**.

### Step 2: Run the installer on your computer

**Mac or Linux:** open the **Terminal** app, paste this line and press Enter:

```bash
curl -fsSL https://raw.githubusercontent.com/ranguera/shield-tv-debloat/main/install.sh | bash
```

**Windows:** open **PowerShell** (Start menu → type *PowerShell*), paste this line and press Enter:

```powershell
irm https://raw.githubusercontent.com/ranguera/shield-tv-debloat/main/install.ps1 | iex
```

The installer finds your SHIELD on the network automatically. If it can't, it asks for the IP
address, which is shown under the **Network debugging** switch.

### Step 3: Allow the connection on your TV

The first time, your TV shows **"Allow USB debugging?"**. Tick **Always allow from this
computer** and choose **OK**. (It says "USB" even though it's over the network.)

### Step 4: Pick an option

```
  1) Debloat + Zen Home launcher   (recommended)
  2) Debloat only                  (keep the stock Google TV home screen)
  3) Undo everything
```

The installer shows exactly which apps it will disable and asks before changing anything.
When it says **Done!**, press **Home** on the remote.

That's it. You can turn **Network debugging** off again afterwards if you like; turn it back on
when you want to undo or update.

---

## Undo

Run the same command from Step 2 again and choose **3) Undo everything**. Every disabled app is
turned back on and the stock home screen returns. It asks whether to uninstall Zen Home too.

Single apps can be turned back on from the SHIELD itself: **Settings → Apps → See all apps →
Show system apps**, pick the app, **Enable**.

## Update Zen Home

Run the same command again and choose **1**. It installs the newest Zen Home over the old one;
your dock, app order and hidden apps are kept.

## Keep an app that would be disabled

Two apps on the list are ones some people do use: **Live Channels** (`com.google.android.tv`,
for TV tuners like HDHomeRun) and **NVIDIA Share** (`com.nvidia.nvgamecast`, game recording).
To keep them, or anything else on the [list](scripts/debloat-list.txt):

**Mac / Linux:**

```bash
curl -fsSL https://raw.githubusercontent.com/ranguera/shield-tv-debloat/main/install.sh | bash -s -- --keep com.google.android.tv,com.nvidia.nvgamecast
```

**Windows:**

```powershell
$env:SHIELD_KEEP = "com.google.android.tv,com.nvidia.nvgamecast"; irm https://raw.githubusercontent.com/ranguera/shield-tv-debloat/main/install.ps1 | iex
```

---

## What gets disabled

24 packages, all disabled with `pm disable-user` (never uninstalled). Full list with reasons:
[`scripts/debloat-list.txt`](scripts/debloat-list.txt).

| | |
|---|---|
| **NVIDIA telemetry & junk** | stats uploader, feedback, registration, welcome/promos, beta console & app, developer widget, Tegra profiler, input viewer, factory helpers, promo auto-installer, Share/Gamecast |
| **Google extras** | feedback, print spooler, Live Channels, Play Music (dead since 2020), Play Movies, YouTube TV, YouTube Music, Photos & Videos |
| **Stock home screen** *(option 1 only)* | the stock launcher and its recommendation/ad feed, once Zen Home is the home screen |

**Kept on purpose:** AI upscaling and display/audio settings (NvCPLSvc, NvCPLUpdater,
nvaudiosvc, AV sync, Dolby), remote pairing, IR TV control, remote locator, Customize button,
controller mapper, SHIELD remote server, GeForce NOW / NVIDIA Games, SMB file sharing, Play Games,
Google Assistant (the remote's mic button), Chromecast, Ambient-mode screensaver, OTA updates.

**Which SHIELDs?** Tested on a SHIELD TV Pro (2019) on SHIELD Experience 9 (Android 11). Other
SHIELD TV models on the same software should work: packages a model doesn't have are skipped.

## Zen Home

- Nature photos drawn at **native 4K** (the SHIELD renders UI at 1080p; the wallpaper gets its
  own 4K surface), crossfading every 5 minutes with a location and photographer caption.
- **New photos every week.** Zen Home downloads 12 photos it hasn't shown before from Wikimedia
  Commons' [featured natural places](https://commons.wikimedia.org/wiki/Commons:Featured_pictures/Places/Natural)
  (about 2,300 reviewer-picked, freely licensed landscapes), cropped to 3840×2160. Wikimedia is
  the only server it talks to; it sends no data about you. The download runs in a short-lived
  helper process, so Home's memory doesn't change. The 12 photos built into the APK are shown
  until the first download and whenever there's no internet.
- Greeting, large clock and date; Search and Settings as Liquid Glass capsules.
- Dock in an iOS 26-style **Liquid Glass** panel: clear glass whose rim refracts the wallpaper
  with slight chromatic fringing, plus a specular highlight that follows the focused app.
  Android 11 has no runtime shaders, so the lens is baked per pixel off the UI thread.
- Apple TV-style focus: tiles spring larger, lift onto a shadow, tilt toward the direction you
  moved and catch a sheen. No borders.
- No navigation or click sounds. Android's own focus clicks are muted too.
- App tiles are rendered in a short-lived `:tiles` process, so other apps' resource tables
  never sit in the launcher's memory.

| Remote | Does |
|---|---|
| OK | open app |
| **Hold OK** (or ☰) on an app | Move · Add to / Remove from Dock · Hide · App info |
| ◀ ▶ (▲ ▼ in the grid) while moving | reorder, OK to finish |
| ▼ from the dock | all apps |
| Home | back to the dock |
| Mic | Google Assistant |

Hidden apps collect in a "Hidden (n)" tile at the end of the grid.

---

## Troubleshooting

**"No SHIELD found automatically"**
Check that the SHIELD is on (not asleep), that **Network debugging** is on, and that the computer
is on the same network (not a guest Wi-Fi). Then type the IP address shown under the Network
debugging switch.

**The TV never showed the "Allow" prompt / "didn't allow the connection"**
Wake the TV and run the command again; the prompt only appears while the SHIELD is awake. If you
chose *Cancel* earlier, go to **Developer options → Revoke USB debugging authorizations** and retry.

**The stock home screen came back after a system update**
Run the installer again and choose **1**. It's safe to run as often as you like.

**After a factory reset**
Everything is back to stock. Turn on Network debugging again and rerun the installer.

**Windows says scripts are disabled**
The `irm … | iex` line doesn't need any policy change. If you downloaded `install.ps1` and want to
run the file instead, use `powershell -ExecutionPolicy Bypass -File install.ps1`.

**Where are my files?**
The installer works in `~/shield-tv-debloat` (Windows: `%USERPROFILE%\shield-tv-debloat`).
Keep its `backup` folder: it records how your SHIELD was before. Undo still works without it
(it then re-enables everything on the list).

---

## For tinkerers

Clone the repo and run the scripts directly. Running `./install.sh` from a clone uses the files
in that clone, so you can edit [`scripts/debloat-list.txt`](scripts/debloat-list.txt) first.

```bash
./install.sh                        # guided menu (same as the one-liner)
./install.sh --full --yes           # no questions: debloat + Zen Home
./install.sh --debloat-only         # debloat, keep the stock launcher
./install.sh --restore              # undo (add --uninstall-zen to remove Zen Home)
./install.sh --dry-run              # show what would happen, change nothing
./install.sh --address 192.168.1.50 # skip discovery

scripts/backup.sh                   # snapshot package lists, settings, home activity, a screenshot
scripts/debloat.sh phase1           # disable everything except the stock launcher
scripts/apply.sh                    # phase 1, install Zen Home, make it home, phase 2
scripts/restore.sh --dry-run        # preview the undo
scripts/restore.sh --package com.nvidia.nvgamecast   # re-enable one thing
launcher/build.sh                   # build the APK yourself (no Gradle needed)
```

The scripts remember the SHIELD's address in `.shield-address`; `ANDROID_SERIAL=<ip>:5555`
overrides it. `apply.sh` installs `launcher/build/ZenHome.apk` if you built one, otherwise the
APK from the [latest release](https://github.com/ranguera/shield-tv-debloat/releases/latest).

**Building Zen Home** needs the Android SDK (`build-tools/35.0.1`, an `android-30` or
`android-33` platform; set `ANDROID_HOME` if it's not in `~/Library/Android/sdk`) and a JDK
(`javac`, `keytool`). `build.sh` creates a signing key in `launcher/keystore/` if there isn't
one. Android only installs an update over Zen Home if it's signed with the same key, so if you
switch between your own build and the release APK, uninstall Zen Home first.

**Not in this repository:** `backup/` (device snapshots hold the SHIELD's serial, Android ID,
Wi-Fi name and screenshots) and `launcher/keystore/` (the release signing key).

## Wallpaper credits

Built-in photos: see [`launcher/app/assets/wallpapers/captions.txt`](launcher/app/assets/wallpapers/captions.txt).
All are CC0 or public domain, from Wikimedia Commons. Weekly photos are credited on screen with
their photographer and license.

## Disclaimer

Not affiliated with NVIDIA or Google. Provided as is; everything it does can be undone, but use
it at your own risk.
