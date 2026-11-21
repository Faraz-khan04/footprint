<#
  Footprint setup for Windows (tested layout: Lenovo IdeaPad Slim 5 on Windows 11).

  What it does:
    1. Creates a Python virtual environment and installs the server + agent.
    2. Generates ADMIN_TOKEN / READ_TOKEN into server\.env (readable only by you).
    3. Registers two Task Scheduler tasks that start at login, hidden:
         Footprint Server  -> API on 127.0.0.1:8000
         Footprint Agent   -> laptop tracker
    4. Registers the laptop and the phone as devices, writes agent\config.json.
    5. Opens port 8000 in the Windows firewall for your local network only (the phone's hotspot).
    6. Removes the old Tailscale setup if it's still there.

  Run from the project folder in an Administrator PowerShell:
      powershell -ExecutionPolicy Bypass -File .\deploy\windows\setup.ps1

  Safe to run again. To issue a fresh phone secret (lost it / new phone):
      powershell -ExecutionPolicy Bypass -File .\deploy\windows\setup.ps1 -NewPhoneSecret
#>
param([switch]$NewPhoneSecret)

$ErrorActionPreference = "Stop"
$Root   = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Server = Join-Path $Root "server"
$Agent  = Join-Path $Root "agent"
$Logs   = Join-Path $Root "logs"
$Api    = "http://127.0.0.1:8000"
$User   = "$env:USERDOMAIN\$env:USERNAME"
New-Item -ItemType Directory -Force $Logs | Out-Null

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Done($msg) { Write-Host "    $msg" -ForegroundColor Green }

# ---------------------------------------------------------------- prerequisites
Step "Checking Python"
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python -or $python.Source -like "*WindowsApps*") {
    throw "Python isn't installed. Run: winget install Python.Python.3.12   then open a NEW terminal and rerun this script."
}
$ver = & python -c "import sys; print('%d.%d' % sys.version_info[:2])"
if ([version]$ver -lt [version]"3.10") { throw "Python $ver found; 3.10 or newer is needed." }
Done "Python $ver"


# ---------------------------------------------------------------- python env
Step "Installing server and agent into .venv"
$Venv = Join-Path $Root ".venv"
if (-not (Test-Path $Venv)) { & python -m venv $Venv }
$Py  = Join-Path $Venv "Scripts\python.exe"
$Pyw = Join-Path $Venv "Scripts\pythonw.exe"
& $Py -m pip install --quiet --upgrade pip
& $Py -m pip install --quiet -r (Join-Path $Server "requirements.txt") -r (Join-Path $Agent "requirements.txt")
if ($LASTEXITCODE -ne 0) { throw "pip install failed; see the output above." }
Done "Dependencies installed"

# ---------------------------------------------------------------- secrets
Step "Preparing server\.env"
$EnvFile = Join-Path $Server ".env"
if (-not (Test-Path $EnvFile)) {
    $admin = & $Py -c "import secrets; print(secrets.token_urlsafe(32))"
    $read  = & $Py -c "import secrets; print(secrets.token_urlsafe(32))"
    $db    = (Join-Path $Server "footprint.db") -replace "\\", "/"
    @(
        "ADMIN_TOKEN=$admin",
        "READ_TOKEN=$read",
        "FOOTPRINT_TZ=Asia/Kolkata",
        "DATABASE_URL=sqlite:///$db",
        "TITLE_RETENTION_DAYS=30"
    ) | Set-Content -Encoding ascii $EnvFile
    # only your account can read the tokens
    icacls $EnvFile /inheritance:r /grant:r "${User}:(R,W)" | Out-Null
    Done "Created with fresh tokens"
} else {
    Done "Already exists, keeping your tokens"
}
$cfg = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object { $cfg[$Matches[1]] = $Matches[2].Trim() }
$AdminToken = $cfg["ADMIN_TOKEN"]
$Auth = @{ Authorization = "Bearer $AdminToken" }

# ---------------------------------------------------------------- tasks
function Register-FootprintTask($Name, $Script) {
    $action    = New-ScheduledTaskAction -Execute $Pyw -Argument "`"$Script`"" -WorkingDirectory $Root
    $trigger   = New-ScheduledTaskTrigger -AtLogOn -User $User
    $settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                   -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) `
                   -StartWhenAvailable -MultipleInstances IgnoreNew
    # Interactive + Limited: runs in your session (needed to see the active window), no admin rights
    $principal = New-ScheduledTaskPrincipal -UserId $User -LogonType Interactive -RunLevel Limited
    Stop-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
    Register-ScheduledTask -TaskName $Name -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
    Start-ScheduledTask -TaskName $Name
}

Step "Opening port 8000 for your hotspot only"
$basePy = & $Py -c "import sys; print(sys._base_executable)"
$baseDir = Split-Path $basePy
$programs = @((Join-Path $baseDir "python.exe"), (Join-Path $baseDir "pythonw.exe"), $Py, $Pyw) | Select-Object -Unique
# If Windows ever showed an "allow access?" popup for Python and it was dismissed, it left BLOCK rules,
# which override any allow rule. Remove those for our interpreters only.
foreach ($prog in $programs) {
    Get-NetFirewallApplicationFilter -Program $prog -ErrorAction SilentlyContinue |
        Get-NetFirewallRule -ErrorAction SilentlyContinue |
        Where-Object { $_.Action -eq "Block" -and $_.Direction -eq "Inbound" } |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
}
Get-NetFirewallRule -DisplayName "Footprint API*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
# Port rule plus per-program rules (the per-program ones also stop Windows from showing the popup).
New-NetFirewallRule -DisplayName "Footprint API (local network only)" -Direction Inbound -Action Allow `
    -Protocol TCP -LocalPort 8000 -RemoteAddress LocalSubnet -Profile Any | Out-Null
foreach ($prog in $programs) {
    New-NetFirewallRule -DisplayName "Footprint API ($(Split-Path $prog -Leaf))" -Direction Inbound -Action Allow `
        -Program $prog -Protocol TCP -LocalPort 8000 -RemoteAddress LocalSubnet -Profile Any | Out-Null
}
Done "Only devices on the same network (your hotspot) can connect, and only to port 8000"

Step "Starting the API (task: Footprint Server)"
Register-FootprintTask "Footprint Server" (Join-Path $PSScriptRoot "start_server.pyw")
$up = $false
for ($i = 0; $i -lt 40; $i++) {
    try { Invoke-RestMethod "$Api/health" -TimeoutSec 2 | Out-Null; $up = $true; break } catch { Start-Sleep -Milliseconds 750 }
}
if (-not $up) { throw "The API didn't start. Open $Logs\server.log to see why." }
Done "API running on $Api"

# ---------------------------------------------------------------- devices
function New-Device($Id, $Name, $Kind) {
    $body = @{ id = $Id; name = $Name; kind = $Kind } | ConvertTo-Json
    try {
        return Invoke-RestMethod -Method Post "$Api/v1/devices" -Headers $Auth -ContentType "application/json" -Body $body
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 409) { return $null }
        throw
    }
}
function Revoke-Device($Id) {
    try { Invoke-RestMethod -Method Post "$Api/v1/devices/$Id/revoke" -Headers $Auth | Out-Null } catch { }
}
$stamp = Get-Date -Format "yyyyMMddHHmm"

Step "Registering this laptop"
$AgentConfig = Join-Path $Agent "config.json"
if (Test-Path $AgentConfig) {
    Done "agent\config.json exists, keeping it"
} else {
    $lap = New-Device "laptop-main" "$env:COMPUTERNAME" "laptop"
    if (-not $lap) {
        # registered before but the config was lost: retire the old id, issue a new one
        Revoke-Device "laptop-main"
        $lap = New-Device "laptop-$stamp" "$env:COMPUTERNAME" "laptop"
    }
    [ordered]@{
        api_url       = $Api
        device_id     = $lap.id
        device_secret = $lap.secret
        poll_seconds  = 5
        idle_seconds  = 180
        sync_seconds  = 60
        send_titles   = $true
        redact        = @("keepass", "bitwarden", "1password", "incognito", "inprivate", "private browsing", "bank")
        ignore        = @("lockapp", "screensaver")
    } | ConvertTo-Json | Set-Content -Encoding utf8 $AgentConfig
    icacls $AgentConfig /inheritance:r /grant:r "${User}:(R,W)" | Out-Null
    Done "Registered as $($lap.id)"
}

Step "Starting the laptop agent (task: Footprint Agent)"
Register-FootprintTask "Footprint Agent" (Join-Path $PSScriptRoot "start_agent.pyw")
Done "Agent running; it syncs every 60 seconds"

Step "Registering the phone"
$phone = $null
if ($NewPhoneSecret) {
    Revoke-Device "phone"
    $phone = New-Device "phone-$stamp" "Nothing Phone (2a)" "phone"
} else {
    $phone = New-Device "phone" "Nothing Phone (2a)" "phone"
}
if ($phone) { Done "Registered as $($phone.id)" } else { Done "Phone was registered on an earlier run (rerun with -NewPhoneSecret for a new secret)" }

# ---------------------------------------------------------------- old tailscale setup
$ts = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
if (-not $ts -and (Test-Path "C:\Program Files\Tailscale\tailscale.exe")) { $ts = "C:\Program Files\Tailscale\tailscale.exe" }
if ($ts) {
    Step "Removing the old Tailscale publishing"
    & $ts serve --https=443 off 2>$null | Out-Null
    Done "Stopped. You can now uninstall Tailscale from Settings > Apps if you like."
}

$lanIps = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.IPAddress -notlike "100.*" } |
    Select-Object -ExpandProperty IPAddress

# ---------------------------------------------------------------- summary
Write-Host ""
Write-Host "==================== ENTER THIS IN THE PHONE APP ====================" -ForegroundColor Magenta
Write-Host "  Laptop address : leave empty, the app finds the laptop on your hotspot"
if ($phone) {
    Write-Host "  Device ID      : $($phone.id)"
    Write-Host "  Device secret  : $($phone.secret)"
    Write-Host "  (Shown once. Not saved anywhere on this laptop.)" -ForegroundColor Yellow
} else {
    Write-Host "  Device ID      : phone  (secret was shown on the first run)"
}
Write-Host "=====================================================================" -ForegroundColor Magenta
Write-Host ""
Write-Host "This laptop    : $($lanIps -join ', ')  (the phone finds this by itself)"
Write-Host "API docs       : $Api/docs"
Write-Host "Logs           : $Logs"
Write-Host "Read token     : in server\.env (READ_TOKEN), for wiring the dashboard"
