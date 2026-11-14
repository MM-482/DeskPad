[CmdletBinding()]
param([string]$OutputDirectory = (Join-Path $PSScriptRoot '..\build\windows'))
$ErrorActionPreference='Stop'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (!(Test-Path -LiteralPath $csc)) { throw '.NET Framework 4.x compiler not found.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$out=[IO.Path]::GetFullPath($OutputDirectory)
$qrLibrary=Join-Path $PSScriptRoot 'vendor\QRCoder-1.4.3\QRCoder.dll'
$pinLibrary=Join-Path $PSScriptRoot 'vendor\BouncyCastle-2.7.0\BouncyCastle.Cryptography.dll'
if ((Get-FileHash -LiteralPath $pinLibrary -Algorithm SHA256).Hash.ToLowerInvariant() -ne '4f96977e9c67334742c683410b3a361258219f0d3084a5e0bc10fba96cf23a0d') { throw 'Vendored Bouncy Castle hash mismatch.' }
if ((Get-FileHash -LiteralPath $qrLibrary -Algorithm SHA256).Hash.ToLowerInvariant() -ne '561acfe4b1a14c837b189fb9fc5c6d3e82440184bbde61912de723d62d6368b3') { throw 'Vendored QRCoder hash mismatch.' }
$common=@('/nologo','/target:winexe','/platform:x64','/optimize+','/codepage:65001',
    '/r:System.dll','/r:System.Core.dll','/r:System.Drawing.dll','/r:System.Windows.Forms.dll',
    '/r:System.Security.dll','/r:System.Web.Extensions.dll',
    "/win32icon:$PSScriptRoot\DeskPad.ico", "/resource:$PSScriptRoot\DeskPad.ico,DeskPad.ico",
    "/resource:$PSScriptRoot\FirewallRules.ps1,DeskPadFirewall.ps1")
$receiverSources=@('DeskPadPC.cs','LanSecurity.cs','SecureServer.cs','FirewallRules.cs','PairingQrSupport.cs','PinPairing.cs' | ForEach-Object { Join-Path $PSScriptRoot $_ })
& $csc @common "/r:$qrLibrary" "/resource:$qrLibrary,DeskPad.QRCoder.dll" "/resource:$PSScriptRoot\vendor\QRCoder-1.4.3\LICENSE.txt,DeskPad.QRCoder.LICENSE.txt" "/r:$pinLibrary" "/resource:$pinLibrary,DeskPad.BouncyCastle.dll" "/resource:$PSScriptRoot\vendor\BouncyCastle-2.7.0\LICENSE.md,DeskPad.BouncyCastle.LICENSE.md" "/out:$out\DeskPadPC.exe" @receiverSources
if ($LASTEXITCODE -ne 0) { throw 'Receiver build failed.' }
& $csc @common "/out:$out\DeskPadPCSetup.exe" "/resource:$out\DeskPadPC.exe,DeskPadPC.exe" "$PSScriptRoot\DeskPadSetup.cs" "$PSScriptRoot\FirewallRules.cs"
if ($LASTEXITCODE -ne 0) { throw 'Installer build failed.' }
Get-Item -LiteralPath "$out\DeskPadPC.exe","$out\DeskPadPCSetup.exe" | Select-Object Name,Length,@{Name='Version';Expression={$_.VersionInfo.FileVersion}}
