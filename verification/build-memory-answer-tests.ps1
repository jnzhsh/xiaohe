param(
  [Parameter(Mandatory=$true)][string]$SdkRoot,
  [Parameter(Mandatory=$true)][string]$AppClasses,
  [string[]]$Dependencies=@(),
  [string]$Device='emulator-5556',
  [ValidateSet('baseline','native','context','ui','dates')][string]$Mode='native',
  [switch]$BuildOnly
)
$ErrorActionPreference='Stop'
if($Device -ne 'emulator-5556'){throw 'Only the isolated memory answer test device is allowed.'}
$taskProject=Split-Path $PSScriptRoot -Parent
$taskBuild=Join-Path $taskProject 'build/memory-answer-tests'
$taskJava='C:/Program Files/OpenJDK/jdk-22.0.2/bin'
$taskPython='C:/Users/sino/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$taskPlatform=Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'platform') -Recurse -Filter android.jar | Select-Object -First 1 -ExpandProperty FullName
$taskAapt=Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Recurse -Filter aapt2.exe | Select-Object -First 1 -ExpandProperty FullName
$taskBin=Split-Path $taskAapt -Parent
foreach($taskDirectory in @($taskBuild,(Join-Path $taskBuild 'classes'),(Join-Path $taskBuild 'dex'))){New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null}
function Invoke-MemoryAnswerTool([string]$Program,[string[]]$ToolArgs){& $Program @ToolArgs;if($LASTEXITCODE -ne 0){throw "Memory-answer tool failed: $Program"}}
Invoke-MemoryAnswerTool $taskAapt @('link','-o',(Join-Path $taskBuild 'resources.apk'),'-I',$taskPlatform,'--manifest',(Join-Path $PSScriptRoot 'memory-answer-test-manifest.xml'),'--min-sdk-version','26')
$taskSources=@('VoiceSupport.java','MemoryAnswerSupport.java','MemoryAnswerTest.java','MemoryAnswerBaselineSmoke.java','MemoryAnswerNativeSmoke.java','MemoryAnswerUiSmoke.java') | ForEach-Object {Join-Path $PSScriptRoot ('app/xiaohe/tests/'+$_)}
$taskSources+=Join-Path $PSScriptRoot 'ScheduleApiSmoke.java'
$taskClasspath=(@($taskPlatform,$AppClasses)+$Dependencies) -join ';'
Invoke-MemoryAnswerTool (Join-Path $taskJava 'javac.exe') (@('--release','8','-encoding','UTF-8','-classpath',$taskClasspath,'-d',(Join-Path $taskBuild 'classes'))+$taskSources)
Invoke-MemoryAnswerTool (Join-Path $taskJava 'jar.exe') @('--create','--file',(Join-Path $taskBuild 'classes.jar'),'-C',(Join-Path $taskBuild 'classes'),'.')
$taskDexArgs=@('-cp',(Join-Path $taskBin 'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','26','--lib',$taskPlatform,'--classpath',$AppClasses)
foreach($taskDependency in $Dependencies){$taskDexArgs+=@('--classpath',$taskDependency)}
Invoke-MemoryAnswerTool (Join-Path $taskJava 'java.exe') ($taskDexArgs+@('--output',(Join-Path $taskBuild 'dex'),(Join-Path $taskBuild 'classes.jar')))
Invoke-MemoryAnswerTool $taskPython @((Join-Path $taskProject 'package-apk.py'),$taskBuild)
Invoke-MemoryAnswerTool (Join-Path $taskBin 'zipalign.exe') @('-f','4',(Join-Path $taskBuild 'unsigned.apk'),(Join-Path $taskBuild 'aligned.apk'))
$env:XIAOHE_MEMORY_ANSWER_TEST_SECRET=[IO.File]::ReadAllText((Join-Path $SdkRoot 'xiaohe-signing.secret')).Trim()
try{Invoke-MemoryAnswerTool (Join-Path $taskJava 'java.exe') @('-jar',(Join-Path $taskBin 'lib/apksigner.jar'),'sign','--ks',(Join-Path $SdkRoot 'xiaohe-signing.jks'),'--ks-key-alias','xiaohe','--ks-pass','env:XIAOHE_MEMORY_ANSWER_TEST_SECRET','--v4-signing-enabled','false','--out',(Join-Path $taskBuild 'test.apk'),(Join-Path $taskBuild 'aligned.apk'))}finally{Remove-Item Env:XIAOHE_MEMORY_ANSWER_TEST_SECRET -ErrorAction SilentlyContinue}
if($BuildOnly){Write-Output (Join-Path $taskBuild 'test.apk');return}
$taskAdb=Join-Path $SdkRoot 'platform-tools/platform-tools/adb.exe'
Invoke-MemoryAnswerTool $taskAdb @('-s',$Device,'install','-r',(Join-Path $taskBuild 'test.apk'))
$taskSha=(& git -C $taskProject rev-parse HEAD).Trim()
$taskResult=@(& $taskAdb -s $Device shell am instrument -w -e mode $Mode -e sourceSha $taskSha app.xiaohe.assistant.memoryanswertests/app.xiaohe.tests.MemoryAnswerTest 2>&1)
$taskExit=$LASTEXITCODE
$taskResult | Set-Content -LiteralPath (Join-Path $taskBuild ($Mode+'.log')) -Encoding utf8
$taskResult | Write-Output
if($taskExit -ne 0 -or ($taskResult -join "`n") -notmatch 'status=passed'){throw 'Memory answer regression failed'}
