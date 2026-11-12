param([string]$AssemblyPath = (Join-Path $PSScriptRoot '..\addin\bin\Release\net8.0-windows\H3D.RevitConverter.dll'))
$ErrorActionPreference = 'Stop'
$assembly = (Resolve-Path -LiteralPath $AssemblyPath).Path
$directory = Join-Path $env:APPDATA 'Autodesk\Revit\Addins\2026'
$manifest = Join-Path $directory 'H3D.EverseConverter.addin'
if (Test-Path -LiteralPath $manifest) { throw "Registration already exists; review it before replacing: $manifest" }
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$escaped = [System.Security.SecurityElement]::Escape($assembly)
$xml = @"
<?xml version="1.0" encoding="utf-8"?>
<RevitAddIns>
  <AddIn Type="Application">
    <Name>H3D Everse Converter</Name>
    <Assembly>$escaped</Assembly>
    <AddInId>4F0B0BCD-5514-40F8-A768-AC19B01D81D4</AddInId>
    <FullClassName>H3D.RevitConverter.ConverterApplication</FullClassName>
    <VendorId>H3D</VendorId>
    <VendorDescription>H3D local Everse conversion bridge</VendorDescription>
  </AddIn>
</RevitAddIns>
"@
[System.IO.File]::WriteAllText($manifest, $xml, [System.Text.UTF8Encoding]::new($false))
Write-Output $manifest
Write-Output 'Restart only the supervised Revit instance. Handle any add-in trust prompt manually; this script does not change trust settings.'
