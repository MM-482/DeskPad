[CmdletBinding()]
param([string]$OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'DeskPadFirewallTests'))
$ErrorActionPreference = 'Stop'
$DeskPadLibraryOnly = $true
. ([scriptblock]::Create([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'FirewallRules.ps1'))))
function Assert-DeskPad([bool]$Value, [string]$Message) { if (!$Value) { throw $Message } }
$receiver = 'C:\Users\test\AppData\Local\Programs\DeskPad PC\DeskPadPC.exe'
$portable = 'C:\Downloads\DeskPadPC.exe'
$rules = @(
    [pscustomobject]@{Name='legacy-udp';DisplayName='DeskPad PC';Group='';Program='Any';Direction='Inbound';Action='Allow';Protocol='UDP';LocalPort='48910-48911'},
    [pscustomobject]@{Name='legacy-tcp';DisplayName='DeskPad PC';Group='';Program='Any';Direction='Inbound';Action='Allow';Protocol='TCP';LocalPort='48912'},
    [pscustomobject]@{Name='auto-allow';DisplayName='DeskPadPC';Group='';Program=$receiver;Direction='Inbound';Action='Allow';Protocol='Any';LocalPort='Any'},
    [pscustomobject]@{Name='auto-deny';DisplayName='DeskPadPC';Group='';Program=$receiver;Direction='Inbound';Action='Block';Protocol='Any';LocalPort='Any'},
    [pscustomobject]@{Name='unrelated';DisplayName='DeskPad PC';Group='';Program='C:\Other\Other.exe';Direction='Inbound';Action='Allow';Protocol='TCP';LocalPort='48912'},
    [pscustomobject]@{Name='admin-block';DisplayName='My deliberate block';Group='';Program=$receiver;Direction='Inbound';Action='Block';Protocol='Any';LocalPort='Any'},
    [pscustomobject]@{Name='other-port';DisplayName='Other product';Group='';Program='Any';Direction='Inbound';Action='Allow';Protocol='UDP';LocalPort='48910-48911'}
)
$plan = Get-DeskPadPlan $rules $receiver @($receiver,$portable)
Assert-DeskPad ($plan.Remove.Count -eq 4) 'Expected 2 legacy rules plus Windows app allow/block rules.'
Assert-DeskPad (@($plan.Remove | Where-Object { $_ -in @('unrelated','admin-block','other-port') }).Count -eq 0) 'Unrelated or custom block selected.'
Assert-DeskPad ($plan.Add.Count -eq 4) 'Expected 2 scoped allows and 2 protective blocks.'
$allow = @($plan.Add | Where-Object Action -eq Allow)
$block = @($plan.Add | Where-Object Action -eq Block)
Assert-DeskPad ($allow.Count -eq 2 -and $block.Count -eq 2) 'Allow/block count incorrect.'
Assert-DeskPad (@($allow | Where-Object { $_.Profile -ne 'Private' -or $_.RemoteAddress -ne 'LocalSubnet' }).Count -eq 0) 'Allows must be Private/subnet-only.'
Assert-DeskPad (@($block | Where-Object { $_.Profile -ne 'Domain,Public' -or $_.RemoteAddress -ne 'Any' }).Count -eq 0) 'Blocks must cover Public/Domain.'
Assert-DeskPad (($allow | Where-Object Protocol -eq UDP).LocalPort -eq '48910') 'UDP 48911 must never be allowed.'
Assert-DeskPad (($allow | Where-Object Protocol -eq TCP).LocalPort -eq '48912') 'TLS TCP port incorrect.'
Assert-DeskPad (($block | Where-Object Protocol -eq UDP).LocalPort -eq '48910-48911') 'Legacy UDP port must be blocked on Public/Domain.'
Assert-DeskPad (@($plan.Add | Where-Object { $_.Program -ne $receiver.ToLowerInvariant() -or $_.EdgeTraversalPolicy -ne 'Block' }).Count -eq 0) 'Every rule must bind the primary receiver and block edge traversal.'
$again = Get-DeskPadPlan @() $receiver.ToUpperInvariant() @($receiver)
Assert-DeskPad ($again.Add[0].Name -eq $plan.Add[0].Name) 'Rule identifiers must be stable across path casing.'
$different = Get-DeskPadPlan @() $portable @($portable)
Assert-DeskPad ($different.Add[0].Name -ne $plan.Add[0].Name) 'Different executable paths must not share identifiers.'
$remove = Get-DeskPadPlan $rules $receiver @($receiver) -Uninstall
Assert-DeskPad ($remove.Add.Count -eq 0 -and $remove.Remove.Count -eq 4) 'Uninstall must not create new rules.'
$gpo = [pscustomobject]@{Name='gpo-broad';DisplayName='Company';Group='GPO';Program=$receiver;Direction='Inbound';Action='Allow';Enabled='True';Profile='Any';Protocol='TCP';LocalPort='Any';RemoteAddress='Any'}
Assert-DeskPad (Test-DeskPadBroadAllow $gpo $plan.Paths) 'Managed broad allow must be detected.'
$safe = $allow[0].PSObject.Copy()
Assert-DeskPad (!(Test-DeskPadBroadAllow $safe $plan.Paths)) 'Exact scoped allow falsely considered broad.'
Assert-DeskPad (Test-DeskPadExactRule $safe $allow[0]) 'Exact rule verification failed.'
$safe.Profile='Any'
Assert-DeskPad (!(Test-DeskPadExactRule $safe $allow[0])) 'Widened profile passed exact verification.'
$invalid=$false
try { Get-DeskPadPlan @() 'C:\Other\powershell.exe' @() | Out-Null } catch { $invalid=$true }
Assert-DeskPad $invalid 'Arbitrary executable target accepted.'
$profiles = @('Domain','Private','Public' | ForEach-Object { [pscustomobject]@{Name=$_;Enabled='True';AllowLocalFirewallRules='NotConfigured'} })
$active = @($plan.Add | ForEach-Object { $_.PSObject.Copy() })
Assert-DeskPad ((Get-DeskPadState $active $plan $profiles).Valid) 'Exact effective rules should pass read-only verification.'
Assert-DeskPad (!(Get-DeskPadState @() $plan $profiles).Valid) 'Missing rules must fail closed.'
$profiles[1].Enabled='False'
Assert-DeskPad (!(Get-DeskPadState $active $plan $profiles).Valid) 'Disabled firewall profile passed verification.'
$profiles[1].Enabled='True';$profiles[1].AllowLocalFirewallRules='False'
Assert-DeskPad ((Get-DeskPadState $active $plan $profiles).Code -eq 41) 'Managed rule restrictions must be reported.'
$profiles[1].AllowLocalFirewallRules='NotConfigured'
Assert-DeskPad (!(Get-DeskPadState @($active + @($gpo)) $plan $profiles).Valid) 'Residual managed broad rule passed state verification.'
Assert-DeskPad ((Get-DeskPadState @() $remove @() -Uninstall).Valid) 'Completed uninstall should pass verification.'
Write-Output 'PASS: 24 synthetic firewall policy checks; no NetSecurity commands invoked.'

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (!(Test-Path -LiteralPath $csc)) { throw '.NET Framework C# compiler not found.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$testExe = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) 'FirewallRulesTests.exe'
& $csc /nologo /target:exe /optimize+ /r:System.dll /r:System.Core.dll "/out:$testExe" "/resource:$PSScriptRoot\FirewallRules.ps1,DeskPadFirewall.ps1" "$PSScriptRoot\FirewallRules.cs" "$PSScriptRoot\FirewallRulesTests.cs"
if ($LASTEXITCODE -ne 0) { throw 'Firewall helper tests did not compile.' }
& $testExe
if ($LASTEXITCODE -ne 0) { throw 'Firewall helper construction tests failed.' }
