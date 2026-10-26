# One-line installer for Windows. In PowerShell:
#
#   irm https://raw.githubusercontent.com/ranguera/shield-tv-debloat/main/install.ps1 | iex
#
# Same steps as scripts/setup.sh on macOS/Linux: downloads adb if needed, finds the SHIELD,
# backs it up, then debloats / installs Zen Home / undoes everything. Works in a folder at
# %USERPROFILE%\shield-tv-debloat (or the clone it's run from). Backups are compatible with
# scripts/restore.sh. Optional, set before running:
#   $env:SHIELD_ADDRESS = "192.168.1.50"              skip discovery
#   $env:SHIELD_KEEP = "com.google.android.tv"        leave these packages enabled

function Invoke-ShieldSetup {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with it on
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

    $Repo = if ($env:SHIELD_REPO) { $env:SHIELD_REPO } else { 'ranguera/shield-tv-debloat' }
    $Raw = "https://raw.githubusercontent.com/$Repo/main"
    $ApkUrl = "https://github.com/$Repo/releases/latest/download/ZenHome.apk"
    $StockHome = 'com.google.android.tvlauncher/.MainActivity'
    $ZenHome = 'com.zen.home/.HomeActivity'
    $DryRun = $env:DRY_RUN -eq '1'

    if ($PSScriptRoot -and (Test-Path (Join-Path $PSScriptRoot 'scripts/debloat-list.txt'))) {
        $Root = $PSScriptRoot
    } else {
        $Root = Join-Path $env:USERPROFILE 'shield-tv-debloat'
    }
    New-Item -ItemType Directory -Force -Path $Root, "$Root/backup", "$Root/downloads" | Out-Null

    function Step($msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }
    function Ok($msg) { Write-Host $msg -ForegroundColor Green }
    function Warn($msg) { Write-Host $msg -ForegroundColor Yellow }

    # --- adb -------------------------------------------------------------------------------
    $script:Adb = $null
    $cmd = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue
    if ($cmd) { $script:Adb = $cmd.Source }
    if (-not $script:Adb) {
        foreach ($d in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA/Android/Sdk", "$Root/tools")) {
            if ($d -and (Test-Path "$d/platform-tools/adb.exe")) { $script:Adb = "$d/platform-tools/adb.exe"; break }
        }
    }
    if (-not $script:Adb) {
        Step "Downloading adb (Google's Android platform-tools) into $Root/tools"
        $zip = "$Root/downloads/platform-tools.zip"
        Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
        Expand-Archive -Force -Path $zip -DestinationPath "$Root/tools"
        Remove-Item $zip
        $script:Adb = "$Root/tools/platform-tools/adb.exe"
    }

    # adb writes progress to stderr; Windows PowerShell 5.1 would turn that into errors.
    function Adb {
        $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { & $script:Adb @args 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $old }
    }
    function Sh { Adb shell @args }
    function Run {
        if ($DryRun) { Write-Host "  [dry-run] adb shell $($args -join ' ')" } else { Sh @args | Out-Null }
    }
    function State($serial) {
        foreach ($line in (Adb devices)) {
            $f = $line -split '\s+'
            if ($f.Count -ge 2 -and $f[0] -eq $serial) { return $f[1] }
        }
        return $null
    }
    function TryConnect($serial) {
        Adb connect $serial | Out-Null
        return (State $serial) -in @('device', 'unauthorized')
    }
    function EnableHelp {
        Write-Host ""
        Write-Host "Make sure the SHIELD is on, on the same network as this PC, and has Network debugging on:"
        Write-Host "  1. On the SHIELD: Settings > Device Preferences > About, then click 'Build' 7 times."
        Write-Host "  2. Back in Device Preferences > Developer options, turn on 'Network debugging'."
        Write-Host "  3. The IP address is shown right under the switch (also in About > Status)."
    }

    # --- menu ------------------------------------------------------------------------------
    Write-Host ""
    Write-Host "SHIELD TV debloat + Zen Home" -ForegroundColor White
    Write-Host "Nothing is uninstalled: apps are only disabled, and option 3 undoes everything."
    if ($DryRun) { Warn "Dry run: nothing on the SHIELD will be changed." }
    Write-Host ""
    Write-Host "  1) Debloat + Zen Home launcher   (recommended)"
    Write-Host "     Removes telemetry, promo apps and the ad-filled home screen; Zen Home replaces it."
    Write-Host "  2) Debloat only"
    Write-Host "     Removes telemetry and promo apps, keeps the stock Google TV home screen."
    Write-Host "  3) Undo everything"
    Write-Host "     Puts the SHIELD back the way it was before."
    Write-Host ""
    $mode = $env:SHIELD_MODE
    while ($mode -notin @('1', '2', '3')) {
        $mode = Read-Host "Choose 1, 2 or 3 (q to quit)"
        if ($mode -eq 'q') { return }
    }

    # --- connect ---------------------------------------------------------------------------
    Step "Connecting to the SHIELD"
    Adb start-server | Out-Null
    $addressFile = "$Root/.shield-address"
    $addr = $null
    if ($env:SHIELD_ADDRESS) {
        $addr = $env:SHIELD_ADDRESS
        if ($addr -notmatch ':') { $addr = "${addr}:5555" }
        if (-not (TryConnect $addr)) { EnableHelp; throw "Can't reach a SHIELD at $addr." }
    }
    if (-not $addr -and (Test-Path $addressFile)) {
        $cand = (Get-Content $addressFile -Raw).Trim()
        if ($cand -and (TryConnect $cand)) { $addr = $cand }
    }
    if (-not $addr) {
        $attached = @(Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\s(device|unauthorized)$' })
        if ($attached.Count -eq 1) { $addr = ($attached[0] -split '\s+')[0] }
    }
    if (-not $addr) {
        Write-Host "Looking for your SHIELD on the network..."
        $found = @()
        for ($i = 0; $i -lt 6 -and $found.Count -eq 0; $i++) {
            $found = @(Adb mdns services | Where-Object { $_ -match '\s_adb\._tcp' } |
                ForEach-Object { ($_ -split '\s+')[2] } | Sort-Object -Unique)
            if ($found.Count -eq 0) { Start-Sleep -Seconds 1 }
        }
        foreach ($cand in $found) { if (TryConnect $cand) { $addr = $cand; break } }
        while (-not $addr) {
            if ($found.Count -eq 0) { Write-Host "No SHIELD found automatically." }
            EnableHelp
            $cand = (Read-Host "`nType the SHIELD's IP address (e.g. 192.168.1.50)").Trim()
            if (-not $cand) { continue }
            if ($cand -notmatch ':') { $cand = "${cand}:5555" }
            if (TryConnect $cand) { $addr = $cand } else { Warn "Can't reach $cand."; $found = @('x') }
        }
    }

    if ((State $addr) -eq 'unauthorized') {
        Write-Host ""
        Write-Host "Look at your TV: a prompt asks to allow debugging from this computer." -ForegroundColor White
        Write-Host "Tick 'Always allow from this computer' and choose OK. Waiting..."
        for ($i = 1; $i -le 120 -and (State $addr) -ne 'device'; $i++) {
            Start-Sleep -Seconds 1
            if ($i % 15 -eq 0) { Adb reconnect offline | Out-Null }
        }
    }
    if ((State $addr) -ne 'device') {
        throw "The SHIELD at $addr didn't allow the connection. Run again and accept the prompt on the TV."
    }
    if ($addr -match ':') { Set-Content -Path $addressFile -Value $addr }
    $env:ANDROID_SERIAL = $addr
    $maker = (Sh getprop ro.product.manufacturer) -join ''
    $model = (Sh getprop ro.product.model) -join ''
    Ok "Connected to $model ($addr)."
    if ($maker -ne 'NVIDIA') {
        Warn "This doesn't look like an NVIDIA SHIELD (manufacturer: $maker)."
        if ((Read-Host "Continue anyway? [y/N]") -notmatch '^[Yy]') { return }
    }

    # --- package list ----------------------------------------------------------------------
    $listFile = Join-Path $Root 'scripts/debloat-list.txt'
    if (-not $PSScriptRoot -or $Root -ne $PSScriptRoot) {
        New-Item -ItemType Directory -Force -Path (Split-Path $listFile) | Out-Null
        Invoke-WebRequest -UseBasicParsing -Uri "$Raw/scripts/debloat-list.txt" -OutFile $listFile
    }
    $list = foreach ($line in Get-Content $listFile) {
        if ($line -match '^\s*(#|$)') { continue }
        $parts = $line -split '#', 2
        [pscustomobject]@{
            Package = $parts[0].Trim()
            Reason  = if ($parts.Count -gt 1) { ($parts[1] -replace '\[phase2\]', '').Trim() } else { '' }
            Phase2  = $line -match '\[phase2\]'
        }
    }
    $keep = @("$env:SHIELD_KEEP" -split '[,\s]+' | Where-Object { $_ })
    $list = @($list | Where-Object { $_.Package -notin $keep })
    function Disabled { Sh pm list packages -d | ForEach-Object { $_ -replace '^package:', '' } | Where-Object { $_ } }

    # --- undo ------------------------------------------------------------------------------
    if ($mode -eq '3') {
        $latest = "$Root/backup/LATEST"
        $original = $null
        if (Test-Path $latest) {
            $b = Join-Path $Root (Get-Content $latest -Raw).Trim()
            if (Test-Path "$b/packages_disabled.txt") {
                Write-Host "Using backup: $b"
                $original = @(Get-Content "$b/packages_disabled.txt" | ForEach-Object { $_ -replace '^package:', '' })
            }
        }
        if ($null -eq $original) { Write-Host "No backup: re-enabling every package in debloat-list.txt instead." }
        $ours = $list.Package
        $count = 0
        foreach ($pkg in (Disabled)) {
            if ($null -eq $original) { if ($pkg -notin $ours) { continue } }
            elseif ($pkg -in $original) { continue }
            Write-Host "re-enable: $pkg"; Run pm enable $pkg; $count++
        }
        Write-Host "$count package(s) re-enabled."
        Write-Host "home -> $StockHome"
        Run cmd package set-home-activity --user 0 $StockHome
        if ((Sh pm path com.zen.home) -match 'package:') {
            if ((Read-Host "Also uninstall Zen Home? [y/N]") -match '^[Yy]') {
                if ($DryRun) { Write-Host "  [dry-run] adb uninstall com.zen.home" } else { Adb uninstall com.zen.home | Out-Null }
            }
        }
        Run input keyevent KEYCODE_HOME
        Write-Host ""; Ok "Done. The SHIELD is back to stock."
        return
    }

    # --- debloat (+ Zen Home) --------------------------------------------------------------
    $full = $mode -eq '1'
    Write-Host ""
    Write-Host "This will disable these packages (skipping any your SHIELD doesn't have):"
    foreach ($p in $list) { if ($full -or -not $p.Phase2) { Write-Host ("  {0,-42} {1}" -f $p.Package, $p.Reason) } }
    if ($full) { Write-Host "...then install Zen Home and make it the home screen." }
    Write-Host ""
    if ($env:ASSUME_YES -ne '1' -and (Read-Host "Go ahead? [Y/n]") -match '^[Nn]') { return }

    if (-not (Test-Path "$Root/backup/LATEST")) {
        Step "Backing up the SHIELD's current state"
        if ($DryRun) { Write-Host "  [dry-run] save package lists and settings to $Root/backup" } else {
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $b = "$Root/backup/$stamp"
            New-Item -ItemType Directory -Force -Path $b | Out-Null
            Sh pm list packages -d | Sort-Object | Set-Content "$b/packages_disabled.txt"
            Sh pm list packages -f | Sort-Object | Set-Content "$b/packages_all.txt"
            Sh cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | Set-Content "$b/home_activity.txt"
            foreach ($ns in 'system', 'secure', 'global') { Sh settings list $ns | Set-Content "$b/settings_$ns.txt" }
            Sh getprop | Set-Content "$b/getprop.txt"
            Set-Content -Path "$Root/backup/LATEST" -Value "backup/$stamp"
            Write-Host "Backup saved to $b"
        }
    }

    function Debloat([bool]$phase2) {
        foreach ($p in $list) {
            if ($p.Phase2 -ne $phase2) { continue }
            if (-not ((Sh pm path $p.Package) -match 'package:')) { Write-Host "skip (not installed): $($p.Package)"; continue }
            if ($DryRun) { Write-Host "  [dry-run] disable $($p.Package)"; continue }
            Write-Host "$($p.Package) -> $((Sh pm disable-user --user 0 $p.Package) -join ' ')"
        }
    }

    $apk = $null
    if ($full) {
        $apk = "$Root/launcher/build/ZenHome.apk"
        if (-not (Test-Path $apk)) {
            $apk = "$Root/downloads/ZenHome.apk"
            Step "Downloading Zen Home"
            if ($DryRun) { Write-Host "  [dry-run] download $ApkUrl" } else {
                try { Invoke-WebRequest -UseBasicParsing -Uri $ApkUrl -OutFile "$apk.part"; Move-Item -Force "$apk.part" $apk }
                catch { if (Test-Path $apk) { Warn "Download failed; using the copy downloaded earlier." } else { throw "Couldn't download Zen Home: $_" } }
            }
        }
    }

    Step "Disabling telemetry and bloat"
    Debloat $false

    if ($full) {
        Step "Installing Zen Home"
        if ($DryRun) { Write-Host "  [dry-run] adb install -r $apk" } else {
            $out = Adb install -r $apk
            $out | Write-Host
            if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
                Warn "Replacing the installed Zen Home (its signature differs, so its saved dock layout is reset)."
                Adb uninstall com.zen.home | Write-Host
                $out = Adb install -r $apk
                $out | Write-Host
            }
            if (-not ($out -match 'Success')) { throw "Installing Zen Home failed." }
        }
        Run cmd package set-home-activity --user 0 $ZenHome
        Step "Disabling the stock launcher and its ad feed"
        Debloat $true
        Run cmd package set-home-activity --user 0 $ZenHome
        Run input keyevent KEYCODE_HOME
    }

    Write-Host ""
    Ok "Done!"
    if ($full) { Write-Host "Press Home on the remote. Hold OK on an app to move, dock or hide it." }
    Write-Host "To undo later, run the same command again and choose 3."
    Write-Host "Keep the $Root/backup folder: it records how your SHIELD was before."
}

try { Invoke-ShieldSetup } catch { Write-Host "Error: $_" -ForegroundColor Red }
