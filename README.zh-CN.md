# Ego Capture 安卓头环采集 App

[English](README.md) · [双目标定工具](https://github.com/EgoOneLabs/Ego-Calib-Tools/tree/feat/ego-stereo-calibration/ego_stereo)

本分支将此前独立交付、工作站构建并真机确认的 **code16 / 0.3.5-capture-ui** 整理进仓库。适配华为 STG-AL00 与2UQ2双目头环（USB `1bcf:0b15`）；包名 `com.egouvc.phone`。底层基于UVCAndroid/libuvc。

## 已实现

- 连续双目抽样预览（目标5–10 FPS），显示实际绘制帧率；保留code15单可见图层消除叠影的修复。
- 原始3840×1080 MJPEG采集，停止后无损拆分左右1920×1080 MKV，保存目标帧率可选、实际值独立统计。
- 头环USB UVC XU IMU读取、原始计数/二进制包、读取请求诊断、名义SI单位导出；设备诊断中保留独立IMU检查，不使用手机自身IMU。
- USB头环声音采集和WAV导出；已核对的一条记录为48kHz双声道PCM16。
- 本地保存/导出任务状态独立于USB连接，停止后拔线不取消原有本地任务。
- 中英文卡片界面、固定录制按钮、展开保存详情；已移除历史标定卡片、弹窗和APK内置报告。

**已有验证**：code16工作站完整构建成功，14套件40项JUnit测试通过；用户确认预览与精简界面正常。已完成一次停止后拔线导出、实际MKV/WAV读回、IMU导出一致性核对。详细证据和边界见[阶段总结](docs/STATUS-20261106.md)。不能据此认定全部稳定性和同步均通过。

## Linux工作站构建

需要JDK17、Gradle8.7、SDK35、NDK27.3.13750724，工程使用AGP8.6.1、Kotlin1.9.24；安卓最低API31。

```bash
git clone --branch feat/android-capture-code16 https://github.com/EgoOneLabs/Ego-Capture.git
cd Ego-Capture
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=4 --console=plain
```

APK在 `app/build/outputs/apk/debug/app-debug.apk`。手机连接电脑、解锁并允许USB调试后：

```bash
adb devices -l
adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.egouvc.phone/.MainActivity
```

保留原工作站debug keystore，确认同签名后覆盖安装；仓库不包含签名私钥。换机器重建的debug签名可能不同，不要为绕过签名问题卸载应用。

## 手机操作和数据

安装完成后将手机接回头环，允许相机/USB/录音权限；打开相机，填写任务与设备编号，选择目标保存帧率，开始采集。停止后保持App前台，等待本地保存导出完成。

原始数据在应用专属 `files/episodes/episode_*`；正式导出在 `files/data/任务/设备会话/`。主要输出：

| 文件 | 用途 |
|---|---|
| `videos/left.mkv`、`videos/right.mkv` | 左右视频 |
| `timestamps/left_timestamps.txt`、`right_timestamps.txt` | 视频时间戳 |
| `imu/imu0.csv` | 11列IMU名义SI数据 |
| `imu/imu_raw_counts.csv` | 原始计数 |
| `audio/audio.wav` | 成功采集时的头环声音 |
| `meta.json`、索引和诊断 | 数据来源与质量状态 |

IMU名义系数4096 count/g、16.4 count/(°/s)，导出加速度m/s²、角速度rad/s；不是单台精密标定。时间为包级映射，零四元数表示未提供姿态。

## 尚未收口

IMU存在已观测的计数间隙；逐样本时间、曝光/IMU硬件同步、相机—IMU联合标定重复性仍未验收。30FPS是目标，已核对记录最大帧间隔约67.39ms，不能当作全程恒定30FPS。长时、导出期间重插及前后台恢复仍待专项验收。

相机标定在工作站完成，历史DS候选未激活到App。原始录像、私钥和APK不入库；本分支[源码摘要清单](docs/code16-source-manifest.json)记录合并来源及文件身份。
