param(
  [Parameter(Mandatory=$true)][string]$SdkRoot,
  [Parameter(Mandatory=$true)][string]$AppClasses,
  [string[]]$Dependencies=@(),
  [string]$Device='emulator-5556',
  [ValidateSet('decoder','controller','microphone','ui')][string]$Mode='decoder',
  [switch]$BuildOnly
)
$ErrorActionPreference='Stop'
if($Device -ne 'emulator-5556'){throw 'Only the isolated voice test device is allowed.'}
$taskProject=Split-Path $PSScriptRoot -Parent
$taskBuild=Join-Path $taskProject 'build/voice-tests'
$taskJava='C:/Program Files/OpenJDK/jdk-22.0.2/bin'
$taskPython='C:/Users/sino/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$taskPlatform=Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'platform') -Recurse -Filter android.jar | Select-Object -First 1 -ExpandProperty FullName
$taskAapt=Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Recurse -Filter aapt2.exe | Select-Object -First 1 -ExpandProperty FullName
$taskBin=Split-Path $taskAapt -Parent
foreach($taskDirectory in @($taskBuild,(Join-Path $taskBuild 'classes'),(Join-Path $taskBuild 'dex'))){New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null}
function Invoke-VoiceTool([string]$Program,[string[]]$ToolArgs){& $Program @ToolArgs;if($LASTEXITCODE -ne 0){throw "Voice tool failed: $Program"}}
Invoke-VoiceTool $taskAapt @('link','-o',(Join-Path $taskBuild 'resources.apk'),'-I',$taskPlatform,'--manifest',(Join-Path $PSScriptRoot 'voice-test-manifest.xml'),'-A',(Join-Path $PSScriptRoot 'voice-assets'),'--min-sdk-version','26')
$taskSources=@('VoiceTest.java','VoiceSupport.java','OfflineDecoderSmoke.java','OfflineControllerSmoke.java','OfflineVoiceUiSmoke.java') | ForEach-Object {Join-Path $PSScriptRoot ('app/xiaohe/tests/'+$_)}
$taskClasspath=(@($taskPlatform,$AppClasses)+$Dependencies) -join ';'
Invoke-VoiceTool (Join-Path $taskJava 'javac.exe') (@('--release','8','-encoding','UTF-8','-classpath',$taskClasspath,'-d',(Join-Path $taskBuild 'classes'))+$taskSources)
Invoke-VoiceTool (Join-Path $taskJava 'jar.exe') @('--create','--file',(Join-Path $taskBuild 'classes.jar'),'-C',(Join-Path $taskBuild 'classes'),'.')
$taskDexArgs=@('-cp',(Join-Path $taskBin 'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','26','--lib',$taskPlatform,'--classpath',$AppClasses)
foreach($taskDependency in $Dependencies){$taskDexArgs+=@('--classpath',$taskDependency)}
Invoke-VoiceTool (Join-Path $taskJava 'java.exe') ($taskDexArgs+@('--output',(Join-Path $taskBuild 'dex'),(Join-Path $taskBuild 'classes.jar')))
Invoke-VoiceTool $taskPython @((Join-Path $taskProject 'package-apk.py'),$taskBuild)
Invoke-VoiceTool (Join-Path $taskBin 'zipalign.exe') @('-f','4',(Join-Path $taskBuild 'unsigned.apk'),(Join-Path $taskBuild 'aligned.apk'))
$env:XIAOHE_VOICE_TEST_SECRET=[IO.File]::ReadAllText((Join-Path $SdkRoot 'xiaohe-signing.secret')).Trim()
try{Invoke-VoiceTool (Join-Path $taskJava 'java.exe') @('-jar',(Join-Path $taskBin 'lib/apksigner.jar'),'sign','--ks',(Join-Path $SdkRoot 'xiaohe-signing.jks'),'--ks-key-alias','xiaohe','--ks-pass','env:XIAOHE_VOICE_TEST_SECRET','--v4-signing-enabled','false','--out',(Join-Path $taskBuild 'test.apk'),(Join-Path $taskBuild 'aligned.apk'))}finally{Remove-Item Env:XIAOHE_VOICE_TEST_SECRET -ErrorAction SilentlyContinue}
if($BuildOnly){Write-Output (Join-Path $taskBuild 'test.apk');return}
$taskAdb=Join-Path $SdkRoot 'platform-tools/platform-tools/adb.exe'
Invoke-VoiceTool $taskAdb @('-s',$Device,'install','-r',(Join-Path $taskBuild 'test.apk'))
if($Mode -eq 'microphone'){Invoke-VoiceTool $taskAdb @('-s',$Device,'shell','pm','grant','app.xiaohe.assistant','android.permission.RECORD_AUDIO')}
$taskSha=(& git -C $taskProject rev-parse HEAD).Trim()
$taskResult=@(& $taskAdb -s $Device shell am instrument -w -e mode $Mode -e sourceSha $taskSha app.xiaohe.assistant.voicetests/app.xiaohe.tests.VoiceTest 2>&1)
$taskExit=$LASTEXITCODE
$taskResult | Set-Content -LiteralPath (Join-Path $taskBuild ($Mode+'.log')) -Encoding utf8
$taskResult | Write-Output
if($taskExit -ne 0 -or ($taskResult -join "`n") -notmatch 'status=passed'){throw 'Voice regression failed'}
