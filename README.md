# Daybyday · 今日打卡

Android 和 iOS 每日打卡应用。事项保存在设备本地。

1. 在「今日打卡」点右上角的「+」，选择「新建打卡」，输入事项名称、每天的提醒时间、重复间隔（分钟或小时）和通知声音。
2. 到点后可从通知选择「确认打卡」或「稍等一下」。稍等会从操作时重新计算一次提醒间隔。确认后，当天停止提醒，下一天恢复。未完成的提醒到当天结束为止。
3. 在「日历」中查看每日打卡数量。向右滑查看上月，向左滑返回较新的月份；点击日期可查看已打卡事项。
4. 在「我的」编辑本机昵称。
5. 在「我的 → 桌面小部件」添加或查看添加步骤。桌面小部件显示今日进度，可逐项确认打卡。Android 小部件默认横向摆放，每页最多显示 3 个事项。两个平台都可用箭头翻页。

## 构建

- **Android**（Android 7.0 及以上）使用 JDK 21 和 Android SDK 37，执行 `./gradlew :androidApp:assembleDebug`。APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。
- **iOS**（iOS 17 及以上）在 Xcode 中打开 `iosApp/iosApp.xcodeproj`，运行 `iosApp`。真机运行时，为主应用和小组件选择同一个 Apple 开发团队，并为两个标识符启用同一个 App Group。项目使用 `group.com.zfz.dbd.Daybyday`。若要改用自己的标识符，同步修改 `iosApp/Configuration/AppGroup.entitlements` 和 `iosApp/iosApp/CheckIn.swift`。

## 提醒权限与排程

- Android 需要允许通知。Android 12 及以上可在应用中进入系统设置授权精确定时。系统省电模式也可能延迟短间隔提醒。
- iOS 使用本地通知，最多预排 64 条、覆盖接下来最多七个日历日，优先给每日首次提醒留出队列空间。短间隔或事项较多时，后续重复提醒可能只覆盖部分时段。打开应用或操作通知时会补排，页面显示当前队列中最晚的提醒时间。
