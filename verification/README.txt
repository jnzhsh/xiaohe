1.2.1: AbsoluteDateSmoke、ScheduleSmoke、ScheduleApiSmoke、ClipboardSmoke 为日期保存/查询/API及Android输入法回归。只在独立测试库和AVD运行。desktop为本工作区模拟器的电脑剪贴板适配，手机使用原生剪贴板。源代码包不含签名私钥、数据库或API密钥。

Issue #3: build-topics-tests.ps1 -SdkRoot <工具目录> -AppClasses <当前应用classes.jar> -Mode backend|ui|private。
backend 在隔离 SQLite 库检查631条、超过分页/启动窗口的筛选计数、首标签与同名细标签、空白/重复标签、大原件JSON、导入与历史保留。ui 在真实WebView执行原生点击，-Phase before 用1.2.2基线APK复现标签铺满首屏，after 检查主题上限、更多主题、细标签搜索/清除、空结果及详情。
evidence/topics/ 是同一合成18条/95标签数据的前后截图，无私人内容。private 接受已放进隔离测试AVD应用files目录的 xiaohe-private-backup.zip，原输入对照与重复导入仅输出数量和保留成功布尔；没有将任何私人备份打包。测试只允许 emulator-5556，不使用个人体验AVD。
版本、实际执行结果与未覆盖项维护在 https://github.com/jnzhsh/xiaohe/issues/3 。

Issue #4: build-voice-tests.ps1 -SdkRoot <工具目录> -AppClasses <当前应用classes.jar> -Dependencies @('<build/thirdparty/classes.jar>','<build/thirdparty/kotlin-stdlib-1.7.20.jar>') -Mode decoder|controller|microphone|ui 。
decoder 对实际安装APK的模型与JNI执行公开中文0.wav、8k.wav、本机合成“明天下午三点提醒我去买咖啡”与静音，测量冷加载与解码；8k样例不宣称逐字准确。controller 检查实际模型的PCM采集/本机WAV/原时间、取消、短录音与模型失败。microphone 检查真实AudioRecord启动、取消、再次启动和停止，仅授予隔离AVD录音权限，不宣称真人中文准确率。
ui 在真实WebView检查可编辑草稿、重载、取消、按录音时间跨午夜固定明天、重试与时区，使用本机合成HTTP服务观察主AI仅收到编辑后的文字，音频上传次数为0。模型未请求发送前请求数为0。
公开测试音频说明见 voice-assets/SOURCES.txt；证据示例是合成界面。实际版本、结果和真实手机/厂商/现代WebView/未配置CI等未覆盖项维护在 https://github.com/jnzhsh/xiaohe/issues/4 。测试APK与fixture不会进入正式APK。
