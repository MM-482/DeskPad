[CmdletBinding()]
param([string]$OutputDirectory = (Join-Path $PSScriptRoot '..\build\security-tests'))
$ErrorActionPreference='Stop'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$out=[IO.Path]::GetFullPath($OutputDirectory)
$testSources=@('DeskPadPC.cs','LanSecurity.cs','SecureServer.cs','FirewallRules.cs','PairingQrSupport.cs','PinPairing.cs','SecurityRegressionTests.cs' | ForEach-Object { Join-Path $PSScriptRoot $_ })
$qrLibrary=Join-Path $PSScriptRoot 'vendor\QRCoder-1.4.3\QRCoder.dll'
$pinLibrary=Join-Path $PSScriptRoot 'vendor\BouncyCastle-2.7.0\BouncyCastle.Cryptography.dll'
$testArgs=@('/nologo','/target:exe','/platform:x64','/main:DeskPadPC.SecurityRegressionTests',
    "/out:$out\SecurityRegressionTests.exe", '/r:System.dll','/r:System.Core.dll','/r:System.Security.dll',
    '/r:System.Windows.Forms.dll','/r:System.Drawing.dll','/r:System.Web.Extensions.dll',
    "/resource:$PSScriptRoot\FirewallRules.ps1,DeskPadFirewall.ps1", "/r:$qrLibrary", "/resource:$qrLibrary,DeskPad.QRCoder.dll",
    "/r:$pinLibrary", "/resource:$pinLibrary,DeskPad.BouncyCastle.dll")
& $csc @testArgs @testSources
if ($LASTEXITCODE -ne 0) { throw 'Security test compilation failed.' }
& "$out\SecurityRegressionTests.exe"
if ($LASTEXITCODE -ne 0) { throw 'Security regression tests failed.' }
