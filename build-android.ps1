param([switch]$Debug,[string]$ToolsRoot='', [string]$OutputRoot='', [string]$AsrCache='')
$ErrorActionPreference = 'Stop'
$taskProject = $PSScriptRoot
$taskWorkspace = Split-Path $taskProject -Parent
$taskTools = if($ToolsRoot){[IO.Path]::GetFullPath($ToolsRoot)}else{Join-Path $taskWorkspace 'android-tools'}
$taskBuild = Join-Path $taskProject 'build'
$taskSource = Join-Path $taskProject 'app/src/main'
$taskOutput = if($OutputRoot){[IO.Path]::GetFullPath($OutputRoot)}else{[IO.Path]::GetFullPath((Join-Path $taskWorkspace '../outputs'))}
$taskManifestXml = [xml](Get-Content -LiteralPath (Join-Path $taskSource 'AndroidManifest.xml') -Raw)
$taskVersion = $taskManifestXml.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
if($taskVersion -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid Android version name'}
$taskJavaDir = 'C:/Program Files/OpenJDK/jdk-22.0.2/bin'
$taskPython = 'C:/Users/sino/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$taskPlatform = Get-ChildItem -LiteralPath (Join-Path $taskTools 'platform') -Recurse -Filter 'android.jar' | Select-Object -First 1 -ExpandProperty FullName
$taskAapt = Get-ChildItem -LiteralPath (Join-Path $taskTools 'build-tools') -Recurse -Filter 'aapt2.exe' | Select-Object -First 1 -ExpandProperty FullName
$taskToolBin = Split-Path $taskAapt -Parent
$taskD8 = Join-Path $taskToolBin 'lib/d8.jar'
$taskSigner = Join-Path $taskToolBin 'lib/apksigner.jar'
if (-not $taskPlatform -or -not $taskAapt) { throw 'Android build tools unavailable' }
foreach ($taskDir in @($taskBuild,(Join-Path $taskBuild 'gen'),(Join-Path $taskBuild 'classes'),(Join-Path $taskBuild 'dex'),$taskOutput)) { New-Item -ItemType Directory -Path $taskDir -Force | Out-Null }
function Invoke-TaskTool([string]$Program,[string[]]$ToolArgs) { & $Program @ToolArgs; if ($LASTEXITCODE -ne 0) { throw "Tool failed: $Program ($LASTEXITCODE)" } }
$taskPrepareArgs = @((Join-Path $taskProject 'prepare-asr.py'))
if($AsrCache){$taskPrepareArgs += @('--cache',[IO.Path]::GetFullPath($AsrCache))}
Invoke-TaskTool $taskPython $taskPrepareArgs
$taskDependencyJars = @((Join-Path $taskBuild 'thirdparty/classes.jar'),(Join-Path $taskBuild 'thirdparty/kotlin-stdlib-1.7.20.jar'))
$taskClasspath = (@($taskPlatform)+$taskDependencyJars) -join ';'
Invoke-TaskTool $taskAapt @('compile','--dir',(Join-Path $taskSource 'res'),'-o',(Join-Path $taskBuild 'resources.zip'))
$taskManifest = Join-Path $taskSource 'AndroidManifest.xml'
if ($Debug) { $taskDebugManifest = Join-Path $taskBuild 'AndroidManifest-debug.xml'; [IO.File]::WriteAllText($taskDebugManifest,[IO.File]::ReadAllText($taskManifest).Replace('<application ','<application android:debuggable="true" '),[Text.UTF8Encoding]::new($false));$taskManifest = $taskDebugManifest }
Invoke-TaskTool $taskAapt @('link','-o',(Join-Path $taskBuild 'resources.apk'),'-I',$taskPlatform,'--manifest',$taskManifest,'--java',(Join-Path $taskBuild 'gen'),'--min-sdk-version','26','--target-sdk-version','35','-A',(Join-Path $taskSource 'assets'),'-A',(Join-Path $taskBuild 'generated/asr-assets'),'--auto-add-overlay',(Join-Path $taskBuild 'resources.zip'))
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskSource 'java') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName) + @(Get-ChildItem -LiteralPath (Join-Path $taskBuild 'gen') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
$taskSourceList = Join-Path $taskBuild 'sources.txt'
[IO.File]::WriteAllLines($taskSourceList, @($taskSources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }), [Text.UTF8Encoding]::new($false))
Invoke-TaskTool (Join-Path $taskJavaDir 'javac.exe') @('-encoding','UTF-8','--release','8','-classpath',$taskClasspath,'-d',(Join-Path $taskBuild 'classes'),('@'+$taskSourceList))
Invoke-TaskTool (Join-Path $taskJavaDir 'jar.exe') @('--create','--file',(Join-Path $taskBuild 'classes.jar'),'-C',(Join-Path $taskBuild 'classes'),'.')
Invoke-TaskTool (Join-Path $taskJavaDir 'java.exe') (@('-cp',$taskD8,'com.android.tools.r8.D8','--min-api','26','--lib',$taskPlatform,'--output',(Join-Path $taskBuild 'dex'),(Join-Path $taskBuild 'classes.jar'))+$taskDependencyJars)
Invoke-TaskTool $taskPython @((Join-Path $taskProject 'package-apk.py'),$taskBuild)
Invoke-TaskTool (Join-Path $taskToolBin 'zipalign.exe') @('-f','-P','16','4',(Join-Path $taskBuild 'unsigned.apk'),(Join-Path $taskBuild 'aligned.apk'))
$taskKeyStore = Join-Path $taskTools 'xiaohe-signing.jks'
$taskSecretPath = Join-Path $taskTools 'xiaohe-signing.secret'
if (-not (Test-Path -LiteralPath $taskSecretPath)) { $taskBytes = [byte[]]::new(24);[Security.Cryptography.RandomNumberGenerator]::Fill($taskBytes);[IO.File]::WriteAllText($taskSecretPath,[Convert]::ToBase64String($taskBytes),[Text.UTF8Encoding]::new($false)) }
$env:XIAOHE_BUILD_SECRET = [IO.File]::ReadAllText($taskSecretPath).Trim()
try {
  if (-not (Test-Path -LiteralPath $taskKeyStore)) { Invoke-TaskTool (Join-Path $taskJavaDir 'keytool.exe') @('-genkeypair','-keystore',$taskKeyStore,'-storepass:env','XIAOHE_BUILD_SECRET','-keypass:env','XIAOHE_BUILD_SECRET','-alias','xiaohe','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Xiaohe','-noprompt') }
  $taskApk = if ($Debug) { Join-Path $taskBuild 'Xiaohe-test.apk' } else { Join-Path $taskOutput ('Xiaohe-'+$taskVersion+'.apk') }
  Invoke-TaskTool (Join-Path $taskJavaDir 'java.exe') @('-jar',$taskSigner,'sign','--ks',$taskKeyStore,'--ks-key-alias','xiaohe','--ks-pass','env:XIAOHE_BUILD_SECRET','--key-pass','env:XIAOHE_BUILD_SECRET','--v4-signing-enabled','false','--out',$taskApk,(Join-Path $taskBuild 'aligned.apk'))
  Invoke-TaskTool (Join-Path $taskJavaDir 'java.exe') @('-jar',$taskSigner,'verify','--verbose','--print-certs',$taskApk)
  Invoke-TaskTool $taskAapt @('dump','badging',$taskApk)
  Write-Output "APK_READY $taskApk"
} finally { Remove-Item Env:XIAOHE_BUILD_SECRET -ErrorAction SilentlyContinue }
