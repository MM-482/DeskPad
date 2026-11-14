[CmdletBinding()]
param([string]$OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'DeskPadPinTests'))
$ErrorActionPreference='Stop'
$csc=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$dll=Join-Path $PSScriptRoot 'vendor\BouncyCastle-2.7.0\BouncyCastle.Cryptography.dll'
if((Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash.ToLowerInvariant() -ne '4f96977e9c67334742c683410b3a361258219f0d3084a5e0bc10fba96cf23a0d'){throw 'Vendored PIN library hash mismatch.'}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$out=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath (Join-Path $out 'BouncyCastle.Cryptography.dll')){throw 'Test output must not contain an external Bouncy Castle DLL.'}
& $csc /nologo /target:exe /platform:x64 /optimize+ /codepage:65001 /r:System.dll /r:System.Core.dll /r:System.Web.Extensions.dll "/r:$dll" "/resource:$dll,DeskPad.BouncyCastle.dll" "/resource:$PSScriptRoot\vendor\BouncyCastle-2.7.0\LICENSE.md,DeskPad.BouncyCastle.LICENSE.md" "/out:$out\PinPairingTests.exe" "$PSScriptRoot\PinPairing.cs" "$PSScriptRoot\PinPairingTests.cs"
if($LASTEXITCODE -ne 0){throw 'PIN enrollment tests did not compile.'}
& "$out\PinPairingTests.exe"
if($LASTEXITCODE -ne 0){throw 'PIN enrollment tests failed.'}
