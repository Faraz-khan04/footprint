<# Quick health check: tasks, API, firewall, laptop IP, last log lines. #>
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Get-ScheduledTask -TaskName "Footprint Server", "Footprint Agent" -ErrorAction SilentlyContinue |
    Select-Object TaskName, State | Format-Table -AutoSize
try { Invoke-RestMethod http://127.0.0.1:8000/health -TimeoutSec 3 | Out-Null; Write-Host "API: up" -ForegroundColor Green }
catch { Write-Host "API: DOWN (see logs\server.log)" -ForegroundColor Red }
$rule = Get-NetFirewallRule -DisplayName "Footprint API (local network only)" -ErrorAction SilentlyContinue
if ($rule -and $rule.Enabled -eq "True") { Write-Host "Firewall: open to local network only" -ForegroundColor Green }
else { Write-Host "Firewall: rule missing, rerun setup.ps1" -ForegroundColor Red }
$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } | Select-Object -ExpandProperty IPAddress
Write-Host "Laptop IPs: $($ips -join ', ')"
foreach ($f in "agent.log", "server.log") {
    $p = Join-Path $Root "logs\$f"
    if (Test-Path $p) { Write-Host "`n--- $f (last 5 lines)"; Get-Content $p -Tail 5 }
}
