param([Parameter(Mandatory=$true)][string]$SdkRoot,[Parameter(Mandatory=$true)][string]$AppClasses,[string]$Device='emulator-5556',[ValidateSet('backend','ui','private')][string]$Mode='backend',[ValidateSet('before','after')][string]$Phase='after')
$ErrorActionPreference='Stop'
if($Device -ne 'emulator-5556'){throw 'Use the isolated test device.'}
$taskProject=Split-Path $PSScriptRoot -Parent
$taskBuild=Join-Path $taskProject 'build/topics-tests'
$taskJava='C:/Program Files/OpenJDK/jdk-22.0.2/bin'
$taskPython='C:/Users/sino/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$taskPlatform=Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'platform') -Recurse -Filter android.jar | Select-Object -First 1 -ExpandProperty FullName
$taskAapt=Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Recurse -Filter aapt2.exe | Select-Object -First 1 -ExpandProperty FullName
$taskBin=Split-Path $taskAapt -Parent
foreach($taskDirectory in @($taskBuild,(Join-Path $taskBuild 'classes'),(Join-Path $taskBuild 'dex'))){New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null}
function Invoke-TaskTool([string]$Program,[string[]]$ToolArgs){& $Program @ToolArgs;if($LASTEXITCODE -ne 0){throw "Tool failed: $Program"}}
Invoke-TaskTool $taskAapt @('link','-o',(Join-Path $taskBuild 'resources.apk'),'-I',$taskPlatform,'--manifest',(Join-Path $PSScriptRoot 'topics-test-manifest.xml'),'--min-sdk-version','26')
$taskSourceRoot=Join-Path $PSScriptRoot 'app/xiaohe/tests'
$taskSources=@('TopicsTest.java','MemoryTopicsSmoke.java','MemoryTopicsUiSmoke.java','ClipboardSmoke.java') | ForEach-Object {Join-Path $taskSourceRoot $_}
Invoke-TaskTool (Join-Path $taskJava 'javac.exe') (@('--release','8','-encoding','UTF-8','-classpath',($taskPlatform+';'+$AppClasses),'-d',(Join-Path $taskBuild 'classes'))+$taskSources)
Invoke-TaskTool (Join-Path $taskJava 'jar.exe') @('--create','--file',(Join-Path $taskBuild 'classes.jar'),'-C',(Join-Path $taskBuild 'classes'),'.')
Invoke-TaskTool (Join-Path $taskJava 'java.exe') @('-cp',(Join-Path $taskBin 'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','26','--lib',$taskPlatform,'--classpath',$AppClasses,'--output',(Join-Path $taskBuild 'dex'),(Join-Path $taskBuild 'classes.jar'))
Invoke-TaskTool $taskPython @((Join-Path $taskProject 'package-apk.py'),$taskBuild)
Invoke-TaskTool (Join-Path $taskBin 'zipalign.exe') @('-f','4',(Join-Path $taskBuild 'unsigned.apk'),(Join-Path $taskBuild 'aligned.apk'))
$env:XIAOHE_BUILD_SECRET=[IO.File]::ReadAllText((Join-Path $SdkRoot 'xiaohe-signing.secret')).Trim()
try{Invoke-TaskTool (Join-Path $taskJava 'java.exe') @('-jar',(Join-Path $taskBin 'lib/apksigner.jar'),'sign','--ks',(Join-Path $SdkRoot 'xiaohe-signing.jks'),'--ks-key-alias','xiaohe','--ks-pass','env:XIAOHE_BUILD_SECRET','--v4-signing-enabled','false','--out',(Join-Path $taskBuild 'test.apk'),(Join-Path $taskBuild 'aligned.apk'))}finally{Remove-Item Env:XIAOHE_BUILD_SECRET -ErrorAction SilentlyContinue}
$taskAdb=Join-Path $SdkRoot 'platform-tools/platform-tools/adb.exe'
Invoke-TaskTool $taskAdb @('-s',$Device,'install','-r',(Join-Path $taskBuild 'test.apk'))
$taskArguments=@('-s',$Device,'shell','am','instrument','-w','-e','mode',$Mode,'-e','phase',$Phase)
if($Mode -eq 'private'){$taskArguments+=@('-e','backup','/data/user/0/app.xiaohe.assistant/files/xiaohe-private-backup.zip')}
$taskArguments+='app.xiaohe.assistant.topicstests/app.xiaohe.tests.TopicsTest'
$taskResult=@(& $taskAdb @taskArguments 2>&1)
$taskExit=$LASTEXITCODE
$taskResult | Set-Content -LiteralPath (Join-Path $taskBuild ($Phase+'-'+$Mode+'.log')) -Encoding utf8
$taskResult | Write-Output
if($taskExit -ne 0 -or ($taskResult -join "`n") -notmatch 'status=passed'){throw 'Topics regression failed'}
