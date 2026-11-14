# Embedded into both Windows executables. Parameters are supplied by FirewallRules.cs
# as base64-decoded values, never as executable path text. Dot-source with
# $DeskPadLibraryOnly=$true for synthetic planner tests; that mode performs no I/O.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:DeskPadGroup = 'DeskPad PC Secure LAN'

function Normalize-DeskPadPath([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -in @('*','Any')) { return '' }
    return [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($Value.Trim('"'))).ToLowerInvariant()
}
function Test-DeskPadInbound($Rule) { return [string]$Rule.Direction -in @('Inbound','1') }
function Test-DeskPadAllow($Rule) { return [string]$Rule.Action -in @('Allow','2') }
function Test-DeskPadLegacy($Rule) {
    if (!(Test-DeskPadInbound $Rule) -or !(Test-DeskPadAllow $Rule) -or $Rule.DisplayName -cne 'DeskPad PC') { return $false }
    if ((Normalize-DeskPadPath $Rule.Program) -ne '') { return $false }
    $ports = (@($Rule.LocalPort) -join ',').Replace(' ', '')
    return (([string]$Rule.Protocol -in @('UDP','17')) -and $ports -eq '48910-48911') -or
           (([string]$Rule.Protocol -in @('TCP','6')) -and $ports -eq '48912')
}

function Get-DeskPadPlan([object[]]$Rules, [string]$Receiver, [string[]]$CleanupPaths, [switch]$Uninstall) {
    $primary = Normalize-DeskPadPath $Receiver
    $paths = @(@($CleanupPaths) + @($Receiver) | ForEach-Object { Normalize-DeskPadPath $_ } | Select-Object -Unique)
    foreach ($path in $paths) {
        if (!$path -or $path.StartsWith('\\') -or [IO.Path]::GetFileName($path) -ine 'DeskPadPC.exe') { throw 'Invalid local receiver path.' }
    }
    $remove = @(@(foreach ($r in $Rules) {
        $bound = $paths -contains (Normalize-DeskPadPath $r.Program)
        $owned = $r.Group -ceq $script:DeskPadGroup -and $r.Name -match '^DeskPadPC\.Secure\.[a-f0-9]{16}\.(Discovery|TLS|BlockDiscovery|BlockTLS)$'
        $autoBlock = $bound -and [string]$r.Action -in @('Block','0') -and
            $r.DisplayName -in @('DeskPadPC','DeskPadPC.exe','DeskPad PC')
        if ((Test-DeskPadLegacy $r) -or ($bound -and (Test-DeskPadInbound $r) -and
            ((Test-DeskPadAllow $r) -or $owned -or $autoBlock))) { $r.Name }
    }) | Select-Object -Unique)
    $add = @()
    if (!$Uninstall) {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $id = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($primary)))).Replace('-','').Substring(0,16).ToLowerInvariant() }
        finally { $sha.Dispose() }
        $add = @(foreach ($kind in @('Discovery','TLS','BlockDiscovery','BlockTLS')) {
            $block = $kind.StartsWith('Block')
            $udp = $kind -in @('Discovery','BlockDiscovery')
            [pscustomobject]@{
                Name="DeskPadPC.Secure.$id.$kind"; DisplayName="DeskPad PC $kind ($id)"; Group=$script:DeskPadGroup
                Program=$primary; Direction='Inbound'; Enabled='True'; EdgeTraversalPolicy='Block'
                Action=$(if ($block) {'Block'} else {'Allow'})
                Profile=$(if ($block) {'Domain,Public'} else {'Private'})
                RemoteAddress=$(if ($block) {'Any'} else {'LocalSubnet'})
                Protocol=$(if ($udp) {'UDP'} else {'TCP'})
                LocalPort=$(if ($kind -eq 'BlockDiscovery') {'48910-48911'} elseif ($udp) {'48910'} else {'48912'})
            }
        })
    }
    [pscustomobject]@{ Remove=$remove; Add=$add; Paths=$paths }
}

function Get-DeskPadInventory([string]$Store, [string[]]$Paths) {
    # Bulk association query, then fetch detailed filters only for relevant rules.
    $all = @(Get-NetFirewallRule -PolicyStore $Store -ErrorAction Stop)
    $filters = @(Get-NetFirewallApplicationFilter -PolicyStore $Store -ErrorAction Stop |
        Where-Object { $Paths -contains (Normalize-DeskPadPath $_.Program) })
    $boundNames = @()
    if ($filters.Count) { $boundNames = @($filters | Get-NetFirewallRule -ErrorAction Stop | ForEach-Object { $_.Name }) }
    @(foreach ($r in $all) {
        if ($r.DisplayName -cne 'DeskPad PC' -and $r.Group -cne $script:DeskPadGroup -and $r.Name -notin $boundNames) { continue }
        $app = @($r | Get-NetFirewallApplicationFilter -ErrorAction Stop)[0]
        $port = @($r | Get-NetFirewallPortFilter -ErrorAction Stop)[0]
        $address = @($r | Get-NetFirewallAddressFilter -ErrorAction Stop)[0]
        [pscustomobject]@{
            Name=$r.Name; DisplayName=$r.DisplayName; Group=$r.Group; Program=$app.Program
            Direction=$r.Direction; Action=$r.Action; Enabled=$r.Enabled; Profile=$r.Profile
            Protocol=$port.Protocol; LocalPort=$port.LocalPort; RemoteAddress=$address.RemoteAddress
            EdgeTraversalPolicy=$r.EdgeTraversalPolicy; SourceType=$r.PolicyStoreSourceType
        }
    })
}

function Test-DeskPadExactRule($Rule, $Spec) {
    $profile = ([string]$Rule.Profile).Replace(' ','')
    $remote = @($Rule.RemoteAddress) -join ','
    if ($remote -eq '*') { $remote = 'Any' }
    return $Rule.Name -ceq $Spec.Name -and (Normalize-DeskPadPath $Rule.Program) -ceq $Spec.Program -and
        [string]$Rule.Enabled -in @('True','1') -and $profile -eq $Spec.Profile -and
        (Test-DeskPadInbound $Rule) -and [string]$Rule.Action -eq $Spec.Action -and
        $remote -eq $Spec.RemoteAddress -and [string]$Rule.EdgeTraversalPolicy -eq 'Block' -and
        ([string]$Rule.Protocol -eq $Spec.Protocol -or [string]$Rule.Protocol -eq $(if ($Spec.Protocol -eq 'TCP') {'6'} else {'17'})) -and
        (@($Rule.LocalPort) -join ',') -eq $Spec.LocalPort
}

function Test-DeskPadBroadAllow($Rule, [string[]]$Paths) {
    if ([string]$Rule.Enabled -notin @('True','1') -or !(Test-DeskPadInbound $Rule) -or !(Test-DeskPadAllow $Rule)) { return $false }
    if (Test-DeskPadLegacy $Rule) { return $true }
    if ($Paths -notcontains (Normalize-DeskPadPath $Rule.Program)) { return $false }
    return [string]$Rule.Profile -ne 'Private' -or (@($Rule.RemoteAddress) -join ',') -ne 'LocalSubnet' -or
        !((([string]$Rule.Protocol -in @('UDP','17')) -and (@($Rule.LocalPort) -join ',') -eq '48910') -or
          (([string]$Rule.Protocol -in @('TCP','6')) -and (@($Rule.LocalPort) -join ',') -eq '48912'))
}

function Get-DeskPadState([object[]]$Active, $Plan, [object[]]$Profiles, [switch]$Uninstall) {
    if (!$Uninstall) {
        foreach ($name in @('Domain','Private','Public')) {
            $profile = @($Profiles | Where-Object { [string]$_.Name -eq $name })
            if ($profile.Count -ne 1 -or [string]$profile[0].Enabled -ne 'True') {
                return [pscustomobject]@{Valid=$false;Code=42;Reason='Windows Firewall profile is disabled or unavailable.'}
            }
            if ([string]$profile[0].AllowLocalFirewallRules -eq 'False') {
                return [pscustomobject]@{Valid=$false;Code=41;Reason='Administrator policy prevents local firewall rules.'}
            }
        }
        foreach ($spec in $Plan.Add) {
            if (@($Active | Where-Object { Test-DeskPadExactRule $_ $spec }).Count -ne 1) {
                return [pscustomobject]@{Valid=$false;Code=42;Reason='Expected effective firewall rule is missing or differs.'}
            }
        }
    }
    if (@($Active | Where-Object { Test-DeskPadBroadAllow $_ $Plan.Paths }).Count) {
        return [pscustomobject]@{Valid=$false;Code=41;Reason='Other effective broad DeskPad firewall rules remain.'}
    }
    if ($Uninstall -and @($Active | Where-Object { $_.Group -ceq $script:DeskPadGroup -and $Plan.Paths -contains (Normalize-DeskPadPath $_.Program) }).Count) {
        return [pscustomobject]@{Valid=$false;Code=41;Reason='DeskPad managed firewall rules remain.'}
    }
    return [pscustomobject]@{Valid=$true;Code=0;Reason='Verified.'}
}

if ((Get-Variable DeskPadLibraryOnly -ErrorAction SilentlyContinue) -and $DeskPadLibraryOnly) { return }
$created = @()
try {
    if ($DeskPadAction -notin @('Apply','Remove','Check')) { throw 'Invalid firewall action.' }
    if ($DeskPadAction -ne 'Check') {
        $admin = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
        if (!$admin.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Elevation required.' }
    }
    $paths = @($DeskPadCleanup | ForEach-Object { Normalize-DeskPadPath $_ })
    if ($DeskPadAction -in @('Apply','Check') -and !(Test-Path -LiteralPath $DeskPadReceiver -PathType Leaf)) { throw 'Receiver file missing.' }
    if ($DeskPadAction -eq 'Check') {
        # Read-only path exits before reaching any firewall mutation, without UAC.
        $plan = Get-DeskPadPlan @() $DeskPadReceiver $DeskPadCleanup
        $state = Get-DeskPadState @(Get-DeskPadInventory ActiveStore $paths) $plan @(Get-NetFirewallProfile -PolicyStore ActiveStore -ErrorAction Stop)
        exit $state.Code
    }
    $plan = Get-DeskPadPlan @(Get-DeskPadInventory PersistentStore $paths) $DeskPadReceiver $DeskPadCleanup -Uninstall:($DeskPadAction -eq 'Remove')
    # Remove legacy broad rules first. Never restore a broad rule after failure.
    foreach ($name in $plan.Remove) {
        Get-NetFirewallRule -PolicyStore PersistentStore -ErrorAction Stop | Where-Object { $_.Name -ceq $name } |
            Remove-NetFirewallRule -ErrorAction Stop
    }
    # Add Public/Domain blocks before Private allows. No global profile changes.
    foreach ($spec in @($plan.Add | Sort-Object @{Expression={ if ($_.Action -eq 'Block') {0} else {1} }})) {
        $arguments = @{}
        foreach ($property in $spec.PSObject.Properties) { $arguments[$property.Name]=$property.Value }
        New-NetFirewallRule -PolicyStore PersistentStore @arguments -ErrorAction Stop | Out-Null
        $created += $spec.Name
    }
    $active = @(Get-DeskPadInventory ActiveStore $plan.Paths)
    $profiles = @()
    if ($DeskPadAction -ne 'Remove') { $profiles = @(Get-NetFirewallProfile -PolicyStore ActiveStore -ErrorAction Stop) }
    $state = Get-DeskPadState $active $plan $profiles -Uninstall:($DeskPadAction -eq 'Remove')
    if (!$state.Valid) { exit $state.Code }
    exit 0
}
catch {
    # Remove partial Private allows, retain any protective Public/Domain blocks.
    foreach ($name in $created | Where-Object { $_ -notmatch '\.Block' }) {
        try { Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.Name -ceq $name } | Remove-NetFirewallRule -ErrorAction Stop } catch { }
    }
    exit 40
}
