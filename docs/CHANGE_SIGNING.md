# 更换签名密钥指南（JoyMouse → Ice Padmouse）

> 背景：应用已改名为 **Ice Padmouse**，但当前 release 仍用旧的 `joymouse-release.jks` 开发密钥（别名 `joymouse`、密码 `joymouse2025`、环境变量 `JOYMOUSE_*`）。
> 本文件记录「以后需要时」换成新签名（`ice-padmouse-release.jks` + `ICEPADMOUSE_*`）的完整步骤。

## 一、重要后果（动手前先读）

1. **签名不同 = 必须卸载重装**：新签名包无法覆盖旧签名包，`adb install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。必须先 `adb uninstall`，应用内配置（按键映射 / 悬浮按键 / 灵敏度 / 光标颜色 / 游戏点位等）会清空。
2. **系统授权失效，需重开**：无障碍服务、电池优化白名单、通知使用权。
3. **旧包无法升级**：之前 `joymouse-release.jks` 签的 v2.0 / v2.1 正式包与新签名不兼容，不能再作为「升级」安装。
4. **密钥必须妥善备份**：丢了就再也无法对该 `applicationId`（`com.joymouse.app`）发布升级包。

## 二、生成新密钥

用 JDK 17 的 keytool（一次即可，有效期约 27 年）：

```bash
KEYTOOL=/home/yupd/android-dev/tools/jdk-17.0.20+8/bin/keytool

$KEYTOOL -genkeypair -v \
  -keystore ~/android-dev/ice-padmouse-release.jks \
  -alias ice-padmouse \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '<你的强密码>' -keypass '<你的强密码>' \
  -dname "CN=Ice Padmouse, OU=dev, O=blackice, C=CN"
```

> 把 `<你的强密码>` 换成随机强密码，例如：`openssl rand -base64 24`
> `-storepass` 与 `-keypass` 建议一致，少记一套。

验证：

```bash
$KEYTOOL -list -v -keystore ~/android-dev/ice-padmouse-release.jks
```

## 三、写环境变量

追加到 `~/android-dev/env.sh`（`scripts/pair.sh`、`scripts/install.sh` 都会 source 它）：

```bash
# Ice Padmouse 签名
export ICEPADMOUSE_KEYSTORE=~/android-dev/ice-padmouse-release.jks
export ICEPADMOUSE_KEYSTORE_PASS='<你的强密码>'
export ICEPADMOUSE_KEY_ALIAS=ice-padmouse
export ICEPADMOUSE_KEY_PASS='<你的强密码>'
```

## 四、改 build.gradle.kts

`app/build.gradle.kts` 里 `signingConfigs` 的 `release` 改成下面这段（env 变量名从 `JOYMOUSE_*` 换成 `ICEPADMOUSE_*`，默认路径/别名同步换）：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("ICEPADMOUSE_KEYSTORE") ?: "/home/yupd/android-dev/ice-padmouse-release.jks")
        storePassword = System.getenv("ICEPADMOUSE_KEYSTORE_PASS") ?: "<你的强密码>"
        keyAlias = System.getenv("ICEPADMOUSE_KEY_ALIAS") ?: "ice-padmouse"
        keyPassword = System.getenv("ICEPADMOUSE_KEY_PASS") ?: "<你的强密码>"
    }
}
```

> 注意：不建议把真实密码硬编码进 git 仓库；上面 `?:` 兜底只是本地开发便利，正式场景请走环境变量注入。
> 旧的 `JOYMOUSE_*` 那一段直接替换掉即可。

## 五、构建

```bash
source ~/android-dev/env.sh
cd ~/andorid/JoyMouse              # 若以后目录也改名则相应改
gradle assembleRelease             # 或 ./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

## 六、验证签名

```bash
APKSIGNER=/home/yupd/android-dev/sdk/build-tools/36.0.0/apksigner
$APKSIGNER verify --print-certs app/build/outputs/apk/release/app-release.apk

AAPT=/home/yupd/android-dev/sdk/build-tools/36.0.0/aapt
$AAPT dump badging app/build/outputs/apk/release/app-release.apk | grep -E "package:|application-label:"
```

应看到 `application-label:'Ice Padmouse'`、正确的 `versionCode`/`versionName`，证书 DN 为 `CN=Ice Padmouse`。

## 七、推送到手机（必须卸载旧版）

```bash
adb devices                        # 确认已连上（无线调试）
adb uninstall com.joymouse.app     # 卸载旧签名包（清配置）
adb install releases/joymouse-v2.1-release.apk   # 或 app-release.apk
```

装完手动恢复：
1. 打开 App → 开启**无障碍服务**（设置 → 无障碍 → Ice Padmouse）。
2. 电池优化白名单：设置 → 应用 → 应用启动管理 → Ice Padmouse → 手动管理（允许自启动 / 后台活动）。
3. （可选）通知使用权，若需要播放/暂停映射。

## 八、（可选）想保住旧配置

卸载前先从 debug 版导出配置（debug 版可 `run-as`）：

```bash
adb shell run-as com.joymouse.app sh -c 'cp -a shared_prefs files /sdcard/icpad_backup'
adb pull /sdcard/icpad_backup
```

但 release 默认不可调试，`run-as` 写不回 `/data/data`，恢复只能靠 `adb restore`（荣耀上不稳定）。因此通常「直接重设」更省事。

若确实要无痛迁移，可在迁移期间临时给 release 加 `isDebuggable = true` 装一次、恢复配置后去掉该行再 `adb install -r`（同签名覆盖，不丢数据）。

## 九、完成清单

- [ ] 生成 `ice-padmouse-release.jks` 并妥善备份
- [ ] `~/android-dev/env.sh` 写入 `ICEPADMOUSE_*`
- [ ] `app/build.gradle.kts` 改 signingConfigs
- [ ] `assembleRelease` 成功
- [ ] `apksigner verify` 通过
- [ ] 手机卸载旧包 → 安装新包 → 重开无障碍服务

## 附：当前状态（尚未切换）

- 密钥：`~/android-dev/joymouse-release.jks`（别名 `joymouse`，密码 `joymouse2025`）
- 环境变量：`JOYMOUSE_KEYSTORE` / `JOYMOUSE_KEYSTORE_PASS` / `JOYMOUSE_KEY_ALIAS` / `JOYMOUSE_KEY_PASS`
- 包名：`com.joymouse.app`（**不变**，改了会破坏覆盖安装与无障碍服务绑定）
