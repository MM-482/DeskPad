[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'DeskPadQrTests'),
    [string]$DummyImagePath
)
$ErrorActionPreference='Stop'
$csc=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$dll=Join-Path $PSScriptRoot 'vendor\QRCoder-1.4.3\QRCoder.dll'
if ((Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash.ToLowerInvariant() -ne '561acfe4b1a14c837b189fb9fc5c6d3e82440184bbde61912de723d62d6368b3') { throw 'Vendored QR library hash mismatch.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$out=[IO.Path]::GetFullPath($OutputDirectory)
& $csc /nologo /target:exe /platform:x64 /optimize+ /codepage:65001 /r:System.dll /r:System.Core.dll /r:System.Drawing.dll "/r:$dll" "/resource:$dll,DeskPad.QRCoder.dll" "/resource:$PSScriptRoot\vendor\QRCoder-1.4.3\LICENSE.txt,DeskPad.QRCoder.LICENSE.txt" "/out:$out\PairingQrTests.exe" "$PSScriptRoot\PairingQrSupport.cs" "$PSScriptRoot\PairingQrTests.cs"
if ($LASTEXITCODE -ne 0) { throw 'Pairing QR tests did not compile.' }
if (Test-Path -LiteralPath (Join-Path $out 'QRCoder.dll')) { throw 'Test directory must not contain an external QRCoder.dll.' }
if ($DummyImagePath) { & "$out\PairingQrTests.exe" $DummyImagePath }
else { & "$out\PairingQrTests.exe" }
if ($LASTEXITCODE -ne 0) { throw 'Pairing QR renderer tests failed.' }
