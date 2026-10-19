# Builds the release zip for the clan-personalities module.
# Excludes .git and other repository machinery — the zip in a release
# should contain ONLY what a server admin unpacks into game\modules\.
#
# Usage:  pwsh ./pack-release.ps1            (version read from module.json)
#         pwsh ./pack-release.ps1 0.2.0     (explicit version, overrides)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Version: from module.json unless given.
$version = $args[0]
if (-not $version)
{
    $manifest = Get-Content -Raw (Join-Path $root "modules\clan-personalities\module.json") | ConvertFrom-Json
    $version = $manifest.version
}

$staging = Join-Path ([System.IO.Path]::GetTempPath()) ("clan-personalities-pack-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
$zipPath = Join-Path $root ("clan-personalities-v" + $version + ".zip")

try
{
    # Copy everything EXCEPT repository machinery.
    robocopy $root $staging /E /XD .git /XF pack-release.ps1 clan-personalities-v*.zip | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed with code $LASTEXITCODE" }

    $delete = Join-Path $staging ".git"
    if (Test-Path $delete) { Remove-Item -Recurse -Force $delete }

    if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
    Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $zipPath
    Write-Host "Built $zipPath"
}
finally
{
    Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
}
