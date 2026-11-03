# 吃药打卡 (MedReminder)

Kotlin + Jetpack Compose + Material 3 的本地服药打卡 App。minSdk 26 / targetSdk 35。

## 功能
- **打卡**：今日各剂次卡片、大号「打卡」按钮（记录当前时间）、跳过、撤销（需确认）、计划/实际时间、准时/迟到/漏服状态、今日进度环（如 2/3）。
- **统计**：日视图（近 60 天日期条 + 日历选择任意日期）；药物总览（记录天数、已服、漏服、跳过、准时率、连续全勤天数、近 7/30 天完成率柱状图，Compose Canvas 绘制）。
- **设置**：多药物，每种药可设置名称、剂量备注、每天多次服药时间（时间选择器，时段标签 早/中/晚 等）；迟到阈值；导出 CSV（分享）；通知/精确闹钟权限入口。
- **提醒**：AlarmManager（可用时精确闹钟，否则非精确）+ BroadcastReceiver，通知带「已吃」按钮直接打卡；开机/升级/改时区后自动重排；Android 13+ 运行时申请通知权限。
- 数据保存在应用私有目录 `files/data.json`，完全本地。

## 构建
需要 JDK 17 和 Android SDK（platform 35, build-tools 35）。
```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # Android Studio 会自动生成
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```
Android Studio：File → Open 选择本目录，等待 Gradle 同步后 Run 即可。

## v2.0 更新
- 数据：Room 数据库（med.db），首次启动自动迁移旧 data.json（迁移后改名为 data.json.migrated）；每日计划快照，修改方案不影响历史统计。
- 设置：支付宝式分组设置页、4 步新增药品向导、疗程（起止日期）与频率（每天/每 N 天/每周几）、库存管理（打卡自动扣减、撤销恢复、低库存提醒）。
- 提醒：稍后 10/30 分钟、未打卡重复提醒、声音/振动渠道可选。
- 打卡：补卡（自定义时间+备注）、桌面小组件（Glance）。
- 统计：日视图 / 月历热力图 / 药物总览，平均延迟与各时段漏服率；PDF 与长图报告导出。
- 备份：JSON 备份/恢复（系统文件选择器，恢复前确认）。
- 深色模式跟随系统。

## 构建
- 调试版：`./gradlew assembleDebug`
- 单元测试：`./gradlew testDebugUnitTest`
- 发布版：在 `keystore/` 放 `release.jks` 与 `keystore.properties`（storeFile/storePassword/keyAlias/keyPassword），然后 `./gradlew assembleRelease`（R8 混淆 + 资源压缩）。源码包不含 keystore。
- 注意：发布版与调试版签名不同，不能互相覆盖安装（需先卸载，数据会丢失，可先用 JSON 备份）。
