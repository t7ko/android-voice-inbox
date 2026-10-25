<#
.SYNOPSIS
Publishes the version in app/build.gradle.kts as a GitHub release with its APK.

.DESCRIPTION
Run it on main with everything committed. It builds dist/voice-inbox-<version>.apk
(build.ps1), tags the last commit v<version>, pushes main and the tag, creates the
GitHub release and uploads the APK. Obtainium on the phone finds new releases at
https://github.com/t7ko/android-voice-inbox. Each step is skipped when already done,
so the script can be run again after a failure.

The token (fine-grained, Contents read and write on this repository only) is read
from the file that github.tokenFile in local.properties names. It is never printed.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$repo = 't7ko/android-voice-inbox'

function Get-LocalProperty([string]$name) {
    foreach ($line in Get-Content (Join-Path $root 'local.properties')) {
        if ($line -match "^$([regex]::Escape($name))=(.*)$") { return $Matches[1].Replace('\:', ':') }
    }
    throw "local.properties has no $name."
}

# git writes progress to stderr, which Windows PowerShell would turn into errors.
function Invoke-Git {
    $ErrorActionPreference = 'Continue'
    & git -C $root @args
    if ($LASTEXITCODE) { throw "git $($args -join ' ') failed." }
}

$gradleFile = Get-Content (Join-Path $root 'app/build.gradle.kts') -Raw
if ($gradleFile -notmatch 'versionName = "([^"]+)"') { throw 'versionName not found in app/build.gradle.kts.' }
$version = $Matches[1]
$tag = "v$version"
$assetName = "voice-inbox-$version.apk"

if ((Invoke-Git rev-parse --abbrev-ref HEAD) -ne 'main') { throw 'Release from main.' }
if (Invoke-Git status --porcelain) { throw 'Commit your changes first: a release is built from a commit.' }

$head = Invoke-Git rev-parse HEAD
if (Invoke-Git tag --list $tag) {
    if ((Invoke-Git rev-parse "$tag^{commit}") -ne $head) { throw "$tag already tags another commit; raise versionName." }
} else {
    Invoke-Git tag -a $tag -m "Voice Inbox $version"
}

& (Join-Path $PSScriptRoot 'build.ps1')
$apk = Join-Path $root "dist/$assetName"

$token = (Get-Content (Get-LocalProperty 'github.tokenFile') -TotalCount 1).Trim()

# The token reaches git as an HTTP header through GIT_CONFIG_* variables, so it never
# appears on a command line. (An askpass shell script fails: git started from Windows
# PowerShell finds no sh to run it.)
$env:GIT_CONFIG_COUNT = '1'
$env:GIT_CONFIG_KEY_0 = 'http.https://github.com/.extraHeader'
$env:GIT_CONFIG_VALUE_0 = 'Authorization: Basic ' +
    [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$token"))
try {
    Invoke-Git -c credential.helper= push origin main $tag
} finally {
    Remove-Item Env:GIT_CONFIG_COUNT, Env:GIT_CONFIG_KEY_0, Env:GIT_CONFIG_VALUE_0
}

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$headers = @{
    Authorization          = "Bearer $token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}
try {
    $release = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/tags/$tag" -Headers $headers
} catch {
    if ($_.Exception.Response.StatusCode -ne 'NotFound') { throw }
    $previous = Invoke-Git tag --list 'v*' --sort=-version:refname | Where-Object { $_ -ne $tag } | Select-Object -First 1
    $range = if ($previous) { "$previous..$tag" } else { $tag }
    $notes = (Invoke-Git log --format='- %s' $range) -join "`n"
    $body = @{ tag_name = $tag; name = "Voice Inbox $version"; body = $notes } | ConvertTo-Json
    $release = Invoke-RestMethod -Method Post "https://api.github.com/repos/$repo/releases" -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
}

if ($release.assets | Where-Object { $_.name -eq $assetName }) {
    Write-Host "$assetName is already attached."
} else {
    $uploadUrl = $release.upload_url -replace '\{.*\}$', ''
    Invoke-RestMethod -Method Post "$($uploadUrl)?name=$assetName" -Headers $headers `
        -ContentType 'application/vnd.android.package-archive' -InFile $apk | Out-Null
}
Write-Host "Released $tag with $assetName at $($release.html_url)"
