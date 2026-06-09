param(
    [Parameter(Mandatory = $true)]
    [string]$Keystore,

    [Parameter(Mandatory = $true)]
    [string]$Alias,

    [Parameter(Mandatory = $true)]
    [string]$HeadUnitInput,

    [Parameter(Mandatory = $true)]
    [string]$PhoneInput,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = "Stop"
$expectedCertificateSha256 =
    "42C2DA4009D416008CA1687EE8888B92F4A35217CA08E13A4A2922B42DDBAA62"

if ([string]::IsNullOrWhiteSpace($env:CARLYRICS_STORE_PASS)) {
    throw "CARLYRICS_STORE_PASS is required"
}
if ([string]::IsNullOrWhiteSpace($env:CARLYRICS_KEY_PASS)) {
    $env:CARLYRICS_KEY_PASS = $env:CARLYRICS_STORE_PASS
}

$sdkRoot = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    "D:\Android"
}
$buildToolsDirectory = Get-ChildItem (Join-Path $sdkRoot "build-tools") -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
if ($null -eq $buildToolsDirectory) {
    throw "Android build-tools not found under $sdkRoot"
}
$apksigner = Join-Path $buildToolsDirectory.FullName "apksigner.bat"

$certificateOutput = & keytool -list -v `
    -keystore $Keystore `
    -storepass $env:CARLYRICS_STORE_PASS `
    -alias $Alias 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect signing certificate"
}
$certificateText = $certificateOutput -join "`n"
$certificateMatch = [regex]::Match(
    $certificateText,
    "SHA256:\s*([0-9A-Fa-f:]+)"
)
if (-not $certificateMatch.Success) {
    throw "Signing certificate SHA-256 was not found"
}
$actualCertificateSha256 = $certificateMatch.Groups[1].Value.Replace(":", "").ToUpperInvariant()
if ($actualCertificateSha256 -ne $expectedCertificateSha256) {
    throw "Unexpected signing certificate: $actualCertificateSha256"
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$headUnitOutput = Join-Path $OutputDirectory "carlyrics-headunit-v$Version-release.apk"
$phoneOutput = Join-Path $OutputDirectory "carlyrics-phone-companion-v$Version-release.apk"
Copy-Item -LiteralPath $HeadUnitInput -Destination $headUnitOutput -Force
Copy-Item -LiteralPath $PhoneInput -Destination $phoneOutput -Force

foreach ($apk in @($headUnitOutput, $phoneOutput)) {
    & $apksigner sign `
        --ks $Keystore `
        --ks-key-alias $Alias `
        --ks-pass env:CARLYRICS_STORE_PASS `
        --key-pass env:CARLYRICS_KEY_PASS `
        --v4-signing-enabled false `
        $apk
    if ($LASTEXITCODE -ne 0) {
        throw "Signing failed for $apk"
    }
    & $apksigner verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) {
        throw "Signature verification failed for $apk"
    }
}

Get-Item $headUnitOutput, $phoneOutput | ForEach-Object {
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName
    [PSCustomObject]@{
        Name = $_.Name
        Size = $_.Length
        SHA256 = $hash.Hash
    }
}
