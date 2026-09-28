param(
    [Parameter(Mandatory=$true)][string]$UnsignedApk,
    [Parameter(Mandatory=$true)][string]$OutputApk,
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security
$signingDirectory = Join-Path $env:LOCALAPPDATA 'UsageNotch\AndroidSigning'
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
$store = Join-Path $signingDirectory 'release.jks'
$secret = Join-Path $signingDirectory 'password.dpapi'
if ((Test-Path -LiteralPath $store) -ne (Test-Path -LiteralPath $secret)) { throw 'Signing identity incomplete. Restore its backup; do not replace the release key.' }
$env:JAVA_HOME = $JavaHome
try {
    if (!(Test-Path -LiteralPath $store)) {
        $bytes = New-Object byte[] 48
        $random = [Security.Cryptography.RandomNumberGenerator]::Create(); $random.GetBytes($bytes); $random.Dispose()
        $password = [Convert]::ToBase64String($bytes)
        $encrypted = [Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($password), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [IO.File]::WriteAllBytes($secret, $encrypted)
        $env:USAGENOTCH_SIGN_PASSWORD = $password
        & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $store -storetype JKS -alias usagenotch -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=UsageNotch Android, O=UsageNotch' -storepass:env USAGENOTCH_SIGN_PASSWORD -keypass:env USAGENOTCH_SIGN_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Release signing key generation failed' }
    } else {
        $plain = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($secret), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $env:USAGENOTCH_SIGN_PASSWORD = [Text.Encoding]::UTF8.GetString($plain)
        [Array]::Clear($plain, 0, $plain.Length)
    }
    $signer = Join-Path $AndroidSdk 'build-tools\36.0.0\apksigner.bat'
    & $signer sign --ks $store --ks-key-alias usagenotch --ks-pass env:USAGENOTCH_SIGN_PASSWORD --key-pass env:USAGENOTCH_SIGN_PASSWORD --out $OutputApk $UnsignedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & $signer verify --verbose --print-certs $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
} finally {
    Remove-Item Env:USAGENOTCH_SIGN_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
}
