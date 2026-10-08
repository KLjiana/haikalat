[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ArchiveZip,
    [string]$ExpectedSha256='b654ee6083fe6702417396a03ac4a5c63915049efdee3ade450931dfd26b4e7a',
    [ValidateSet('balanced','high','fog-off')][string]$Mode='balanced',
    [string]$OutputDirectory,
    [switch]$ExtractOnly
)
$ErrorActionPreference='Stop'
$referenceRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$referenceArchive=(Resolve-Path -LiteralPath $ArchiveZip).Path
$referenceHash=(Get-FileHash -LiteralPath $referenceArchive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($referenceHash -ne $ExpectedSha256.ToLowerInvariant()) {throw 'Outdoor reference archive SHA-256 mismatch'}
if (!$OutputDirectory) {
    $OutputDirectory=Join-Path $referenceRoot ('build/references/outdoor-v0243-'+[Guid]::NewGuid().ToString())
}
$referenceOutput=[System.IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $referenceOutput) {throw 'Choose an absent output directory; existing files are preserved'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($referenceArchive,$referenceOutput)
foreach ($referenceRequired in @('gradlew.bat','settings.gradle','build.gradle','gradle.lockfile',
    'config/visual-profiles/forest_morning.properties','src/main/resources/shaders/postprocess/outdoor-volumetric-sun.frag')) {
    if (!(Test-Path -LiteralPath (Join-Path $referenceOutput $referenceRequired))) {throw ('Missing reference input '+$referenceRequired)}
}
if ($ExtractOnly) {
    [PSCustomObject]@{referenceDirectory=$referenceOutput;archiveSha256=$referenceHash;status='extracted'}
    return
}
$referenceCaptures=Join-Path $referenceOutput 'build/reports/isolated-outdoor-reference'
New-Item -ItemType Directory -Path $referenceCaptures | Out-Null
$referenceProfile=Join-Path $referenceCaptures 'profile.properties'
$referenceOriginal=Get-Content -LiteralPath (Join-Path $referenceOutput 'config/visual-profiles/forest_morning.properties') -Raw
$referenceOriginal.Replace('bloom.enabled=true','bloom.enabled=false') | Set-Content -LiteralPath $referenceProfile -Encoding utf8
$referenceArguments=@('--hidden','--no-vsync','--frames=421','--size=1920x1080','--route',
    '--aa=TAA','--bloom=off','--environment-quality=default',('--load-config='+$referenceProfile))
if ($Mode -eq 'high') {$referenceArguments+='--volume-quality=reference'}
if ($Mode -eq 'fog-off') {$referenceArguments+='--fog=off'}
foreach ($referenceFrame in @(60,240,420)) {
    $referenceStamp=$referenceFrame.ToString('0000')
    $referenceArguments+=('--capture-at='+$referenceFrame+':'+(Join-Path $referenceCaptures ('forest_morning-'+$referenceStamp+'.png')))
    $referenceArguments+=('--capture-hdr-at='+$referenceFrame+':'+(Join-Path $referenceCaptures ('forest_morning-'+$referenceStamp+'.h4f.gz')))
}
$referenceEncoding=[System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText((Join-Path $referenceCaptures 'arguments.json'),
    ($referenceArguments | ConvertTo-Json),$referenceEncoding)
$referenceInit=@'
gradle.projectsEvaluated {
    rootProject.tasks.named('runRender3dForestMorningBaseline').configure {
        setArgs(new groovy.json.JsonSlurper().parse(rootProject.file(
            'build/reports/isolated-outdoor-reference/arguments.json')))
    }
}
'@
[System.IO.File]::WriteAllText((Join-Path $referenceCaptures 'reference-arguments.gradle'),
    $referenceInit,$referenceEncoding)
$referenceLog=Join-Path $referenceCaptures 'execution.log'
Push-Location $referenceOutput
try {
    & ./gradlew.bat runRender3dForestMorningBaseline -I build/reports/isolated-outdoor-reference/reference-arguments.gradle --console=plain *> $referenceLog
    $referenceExit=$LASTEXITCODE
} finally {Pop-Location}
Get-Content -LiteralPath $referenceLog -Tail 6
if ($referenceExit -ne 0) {throw ('Outdoor reference execution failed; inspect '+$referenceLog)}
[PSCustomObject]@{referenceDirectory=$referenceOutput;captureDirectory=$referenceCaptures;archiveSha256=$referenceHash;mode=$Mode;status='captured'}
