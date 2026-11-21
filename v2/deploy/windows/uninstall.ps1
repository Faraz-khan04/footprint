<#
  Stops and removes the Footprint tasks and firewall rules.
  Your data (server\footprint.db), tokens (server\.env) and agent config are left in place.
  Run in an Administrator PowerShell:
      powershell -ExecutionPolicy Bypass -File .\deploy\windows\uninstall.ps1
#>
foreach ($name in "Footprint Agent", "Footprint Server") {
    Stop-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Removed task: $name"
}
Get-NetFirewallRule -DisplayName "Footprint API*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Write-Host "Removed firewall rules"
Write-Host "Data kept in server\footprint.db. Delete the project folder to remove everything."
