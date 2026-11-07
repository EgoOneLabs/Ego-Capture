# 同事接手：采集 App 与双目标定

版本日期：2026-11-07。接手目标为 Android 2UQ2 双目 Ego-Capture 与 Ego-Calib-Tools/ego_stereo；四鱼眼为另一路线，不能混用参数。

## 先认清收到的是哪种包

`Ego-colleague-handoff-prep-20261107.zip` 是工作站打包准备包，内含完整源码Git bundle、导入脚本、此指南、阶段记录、来源与校验文件，**尚不含手机APK和Kalibr镜像**。

原维护者运行 collect_local_delivery.py 后生成的 `Ego-colleague-delivery_*.zip` 才包含已验证 code16 APK。脚本可额外加入已有Kalibr镜像归档；是否实际包含镜像见 DELIVERY_STATUS.json，不能只看包名判断。这里采用联网安装Android工具链的交接方式，不包含离线SDK/NDK、Gradle/Maven缓存。

## 一、原维护者需要交给同事的东西

| 内容 | 是否必须 | 放在哪里 / 当前情况 |
|---|---|---|
| 最新采集App源码 | 修改/编译必须 | Ego-Capture.bundle，包含code16及后续文档/完整性检查，非仅README |
| 最新标定源码和脚本 | 标定开发必须 | Ego-Calib-Tools.bundle，含原四鱼眼目录与独立ego_stereo |
| 已验证APK | 手机直接安装必须 | 工作站原code16 APK，由打包器加入 app/Ego-Capture-code16.apk |
| 历史DS YAML、匹配PDF/TXT、SHA256、板配置示例 | 阅读历史结果与复现对应检查需要 | 标定仓库 ego_stereo/reference/、configs/ 已跟踪 |
| Kalibr运行环境 | 在新工作站运行标定必须 | 交付已有镜像归档或从获授权Release下载；准备包不含镜像 |
| 阶段交接、已通过/未通过项、源码版本、文件摘要 | 接手必须 | docs/CURRENT_STAGE.zh-CN.md、updates.json、CHECKS.json、校验文件 |
| 指定原始BAG/视频/IMU数据 | 复现该次实测需要 | 另行传给同事并附摘要、原路径与设备/录制说明；运行新输入不需要历史全部数据 |
| debug/release签名私钥 | 公开包不包含 | 换电脑构建不能保证与原安装同签名；确需延续原签名，由负责人单独安全交接 |

Git仓库提交源码、脚本、可分发原生组件、资源、配置示例与数值报告；APK放Release或内部交付包。大型容器镜像放可访问的镜像/Release或单独文件。真实工作场景数据由负责人按选定范围内部传递，不默认公开到GitHub。

## 二、原维护者在原 Linux 工作站打包

下载准备包到 ~/Downloads 后运行：

```bash
unzip -n "$HOME/Downloads/Ego-colleague-handoff-prep-20261107.zip" -d "$HOME/Downloads" &&
python3 "$HOME/Downloads/Ego-colleague-handoff-prep-20261107/collect_local_delivery.py"
```

打包器仅搜索已知code16构建目录，并严格要求APK SHA256：
`3d266b8edaa72bc5461b2061b5666ae494103a7477ef80eb5fab92a8b3947a4c`。
版本为 16 / 0.3.5-capture-ui，包名 com.egouvc.phone。该APK曾在原工作站完整构建并通过40项历史测试，用户确认预览和精简界面；不等于全部长时采集/同步已验收。源码1210个code16基线文件摘要与该历史来源记录一致，本轮没有重建APK。

若APK搬过位置，显式指定；若同时有原部署镜像，可一并加入（下面两条路径【每次修改】）：

```bash
python3 "$HOME/Downloads/Ego-colleague-handoff-prep-20261107/collect_local_delivery.py" \
  --apk /实际/原code16.apk \
  --kalibr-image /实际/environment/kalibr-environment.tar.gz
```

若同事还需复现实测，可额外重复指定 `--data /实际/原始BAG`、`--data /实际/正式会话目录`。仅复制显式选中的文件/目录并逐文件核对摘要；拒绝符号链接。大数据会使ZIP显著增大，可选择单独内部传递，不能默认公开。

不要用不同APK冒充这份已验证版本。找不到时原样发送报错，或在已知构建目录找回；脚本不会覆盖安装/卸载手机、删除原始数据或自行重新构建。

程序打印新的交接目录及ZIP。把该ZIP传给同事；镜像过大可另外传镜像文件和对应SHA256，不必再次上传聊天。包内 DELIVERY_STATUS.json 会明确APK、镜像和SDK是否包含。交接ZIP在本地生成不代表已发布GitHub。

## 三、同事需安装/下载什么

| 接手任务 | 手机端 | Linux工作站 |
|---|---|---|
| 只用App采集 | Android 12/API31或更高、arm64-v8a、USB主机能力、已验证APK、相机/录音及USB授权 | ADB用于安装/拉取；无需Android Studio、JDK或Kalibr即可运行已安装App |
| 修改并构建App | 同上，实机为主 | JDK17、Android SDK Platform35、NDK27.3.13750724；Gradle8.7 Wrapper、AGP8.6.1、Kotlin1.9.24由项目解析 |
| 媒体读回/源码测试 | 不要求连接手机（已有导出时） | Git、Python3、FFmpeg/ffprobe、unzip、poppler-utils；venv安装标定requirements-test.txt |
| 相机标定/固定参数核查 | 已有录制时不要求手机连接 | Docker及带ROS Noetic/Kalibr的镜像；绑定数据目录到/data，/catkin_ws已编译 |
| 推送仓库/下载受限Release | 无额外要求 | GitHub账户获得仓库权限，Git或gh配置身份验证 |

本项目构建版本以源码为准，不因Android Studio更新而自动升级AGP、Kotlin、SDK或NDK。

### 新 Ubuntu 工作站基础软件

```bash
sudo apt-get update
sudo apt-get install -y git gh python3 python3-venv unzip ffmpeg poppler-utils openjdk-17-jdk adb
```

Docker若已安装，保持现有安装并确认 docker info 可用；没有时按官方Ubuntu安装说明配置，不将docker.io和Docker CE混装：
https://docs.docker.com/engine/install/ubuntu/

Android Studio/SDK工具从官方获取：
https://developer.android.com/studio
https://developer.android.com/tools/sdkmanager
ADB说明：https://developer.android.com/tools/adb

在SDK管理器选择 Platform35、Build Tools34.0.0/35.0.0、NDK27.3.13750724、Platform-Tools。也可使用已有Command-Line Tools里的sdkmanager：

```bash
"$HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-35" \
  "build-tools;34.0.0" "build-tools;35.0.0" "ndk;27.3.13750724"
```

SDK路径按本机实际位置调整并按工具提示处理许可。Android Studio本身不是命令行构建的硬性依赖。Gradle/Maven首次构建需可用网络；本包不保证完全离线构建。

## 四、同事恢复源码并安装App

进入解压后的交接包根目录，运行以下命令；它导入到新工作目录，不推送GitHub：

```bash
python3 sync_runtime_files.py --root "$HOME/ego-work"
cd "$HOME/ego-work/Ego-Capture"
python3 scripts/verify_checkout.py
```

直接安装本包APK（下列命令从交接包根目录执行；多设备时加 -s 实际序列号）：

```bash
adb devices -l
adb install --no-streaming -r app/Ego-Capture-code16.apk
adb shell am start -n com.egouvc.phone/.MainActivity
```

手机先开启USB调试并授权工作站，安装后接回2UQ2头环并授予USB、相机、录音权限。覆盖安装必须同签名；发生签名冲突时保留数据并找负责人处理，不通过卸载绕过。

如需从源码构建，在项目根目录执行：

```bash
cd "$HOME/ego-work/Ego-Capture"
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=4 --console=plain
```

新产物为 app/build/outputs/apk/debug/app-debug.apk，摘要/签名可能不同，不能覆盖称为原已验证APK。记录新版本、摘要、构建与测试结果。

App使用：填写任务名/设备标签，选择保存FPS，开始采集，停止并保存，等导出完成。实际输出在Android/data/com.egouvc.phone/files/data；原始记录在episodes。左右各1920×1080、音频和名义SI IMU按README说明核对。

## 五、恢复标定环境并检查入口

### 镜像来源

优先接收原部署包 environment/kalibr-environment.tar.gz 及SHA256。根README记录的历史Release为：
https://github.com/BAAI-Ego/headband-calibration-tools/releases/tag/v1.10.4

该旧Release的当前可访问性未在本轮确认；需要对应仓库权限。不能把链接存在等同于同事一定能下载。原维护者应同时交付可用镜像或确认访问权限，不能只留下不可访问链接。README所记原镜像为kalibr:noetic、linux/amd64、ID sha256:ddbe45d2bd03e8a415688355dc23c4ceedf6eb576e1c6d7eeafa51567fa10b29；这不是本轮重新下载核验的结果。

已有镜像时核对交付摘要再导入；新机器首次创建容器时执行：

```bash
docker info
docker load -i /实际/environment/kalibr-environment.tar.gz
docker image inspect kalibr:noetic --format '{{.Id}} {{.Os}}/{{.Architecture}}'
mkdir -p "$HOME/ego-data"
# 仅用于没有同名容器的新机器；已有kalibr容器时检查挂载后沿用。
docker run -d --name kalibr -v "$HOME/ego-data:/data" kalibr:noetic sleep infinity
docker exec kalibr bash -c '
set -e
source /opt/ros/noetic/setup.bash
source /catkin_ws/devel/setup.bash
python3 -c "import cv2,numpy,yaml,rosbag,kalibr_common,aslam_cv_backend; from sensor_msgs.msg import Image; print(\"KALIBR_IMPORTS_OK\")"
rospack find kalibr
'
```

若镜像不含所需编译工作空间或导入失败，停止并保留日志，不能宣称环境恢复。docker save只保存镜像，不包含容器可写层和挂载数据；不要用它冒充已经完整复现当前运行容器。

### 源码与宿主测试依赖

```bash
cd "$HOME/ego-work/Ego-Calib-Tools"
python3 ego_stereo/verify_checkout.py
python3 -m venv .venv-ego
. .venv-ego/bin/activate
python3 -m pip install -r ego_stereo/requirements-test.txt
```

宿主机不必另装ROS；不要用宿主pip去替换容器内Kalibr编译依赖。离线媒体/标定使用显式 --session、--media-report、--bag、--output-root，输出目录必须在容器可写挂载内。

完整操作顺序见仓库ego_stereo/README.zh-CN.md：媒体读回→左右同索引抽帧/BAG全读回→确需新标定时调用官方Kalibr→结果管理/固定参数复核。历史230对数据与已知DS结果已经验证过，不因接手而重跑或重录。

## 六、接手后从哪里继续

已完成：code16界面与预览确认、原1210文件源码完整性、历史40项App测试证据、真实短录制媒体/BAG通路、DS三文件关联和230对固定参数观测核查。本轮14项工作站工具测试与干净检出检查通过，不新增真机验收结论。

未完成：IMU计数连续性、逐样本物理时间和曝光同步、长时/重插/前后台恢复、独立数据边缘泛化和设备参数绑定。camera_imu重复性未建立且专项暂停，不应阻塞全部App维护；不要重新开始盲目扫参和补录。以docs/CURRENT_STAGE.zh-CN.md最新条目为优先。

先核对收到的代码提交、APK摘要/签名、容器和输入文件，再为一个实际问题制定单一改动和一次验收。维护者若需GitHub同步，在原工作站运行sync_runtime_files.py --push；该操作只更新现有两条功能分支，不动main。
