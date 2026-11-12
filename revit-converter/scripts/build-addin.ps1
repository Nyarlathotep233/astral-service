param(
    [string]$RevitInstallDir = 'D:\software\AutoDesk\RVT2026\Revit 2026',
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot '..\addin\H3D.RevitConverter.csproj'
$arguments = @('build', $project, '-c', 'Release', '--nologo', "-p:RevitInstallDir=$RevitInstallDir")
if ($OutputDirectory) { $arguments += @('-o', [System.IO.Path]::GetFullPath($OutputDirectory)) }
& dotnet @arguments
if ($LASTEXITCODE -ne 0) { throw 'Revit add-in build failed.' }
