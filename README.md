# DS Balance

DeepSeek API 余额与用量查询 —— 原生 Android App + 桌面小组件。

纯 Termux 手工编译，无需 Gradle / Android Studio。单 APK 约 42 KB。

![](https://img.shields.io/badge/minSdk-26-blue) ![](https://img.shields.io/badge/targetSdk-35-blue) ![](https://img.shields.io/badge/size-42KB-green)

## 功能

**余额**

`GET https://api.deepseek.com/user/balance`

- 总额、赠送余额、充值余额
- 可用状态（`is_available`）
- 自动识别 CNY / USD 显示 ¥ 或 $

**用量**

`GET https://platform.deepseek.com/api/v0/usage/amount?month=&year=`

- 本月输入 / 输出 token
- 请求次数
- 按天、按模型聚合

**缓存命中**

- 本月命中率、今日命中率
- 今日命中 / 未命中 / 输出明细
- 数据来自 `PROMPT_CACHE_HIT_TOKEN`，DeepSeek 网页后台不显示这个数

**桌面小组件**

```
sk-ZE4********T96            ¥5.45
今日 9.43M 入 / 52.9K 出
命中 86%                        13:45
```

- 2×1 起，可拉伸
- 点击刷新，点击余额打开 App
- 30 分钟自动刷新
- 命中率颜色随数值变化：≥80% 绿 / 50-79% 黄 / <50% 红

**浅色 / 深色**

- 跟随系统 / 强制浅色 / 强制深色

## 安全设计

App 里藏着两个密钥，明文不常驻界面。

| 动作 | 方式 | 要求 |
|---|---|---|
| 查看明文 | 点输入框右侧 52dp 空白区 | 生物识别 / 设备 PIN |
| 更换密钥 | 长按该空白区 | 生物识别 / 设备 PIN |
| 清除全部 | 点「清除全部凭据」 | 生物识别 / 设备 PIN |

- 默认显示掩码 `sk-ZE4********T96`，揭示 20 秒后自动重新遮盖
- 输入框 `focusable=false`，光标不可见，软键盘弹不出
- 存储：AES-256-GCM，密钥由 `ANDROID_ID + 包名` 经 SHA-256 派生
- 身份验证：`BiometricPrompt` + Keystore 密钥 `setUserAuthenticationRequired(true)`，无生物的机型自动退回系统 PIN

## 数据来源说明

| 数据 | 接口 | 凭据 | 官方文档 |
|---|---|---|---|
| 余额 | `/user/balance` | `sk-` key | ✅ 公开 |
| 用量 | `/api/v0/usage/amount` | 平台 userToken | ❌ 私有接口 |
| 缓存命中 | 同上 | 同上 | ❌ |

**重要**：DeepSeek 没有公开的用量查询 API。`platform.deepseek.com/api/v0/*` 是网页后台的私有接口，随时可能变。API Key **无法**查询用量，必须用平台会话 token。

## 获取 userToken

1. 浏览器登录 https://platform.deepseek.com
2. 地址栏执行：

```
javascript:alert(JSON.parse(localStorage.getItem('userToken')).value)
```

3. 复制弹出的字符串（48 字符），粘进 App 第二个输入框

或在桌面浏览器：`F12 → Console → JSON.parse(localStorage.getItem('userToken')).value`

该 token 会过期，失效时用量卡片显示 `token 失效 (401/403)`。

## 编译

环境：Termux（Android 上直接编译，不需要电脑）

```bash
pkg install aapt2 d8 apksigner zip openjdk-17
git clone https://github.com/husky1919/ds-balance-android.git
cd ds-balance-android
./build.sh
```

脚本会自动下载 `android.jar`、生成签名密钥、编译、签名。

产物：`build/dsbalance.apk`

### 手工编译步骤

```bash
mkdir -p build/{res,gen,classes,dex}

aapt2 compile --dir app/res -o build/res/
aapt2 link -o build/base.apk -I fw/android.jar \
    --manifest app/AndroidManifest.xml --java build/gen \
    --min-sdk-version 26 --target-sdk-version 35 build/res/*.flat

javac -source 11 -target 11 -classpath fw/android.jar -d build/classes \
    build/gen/com/dsbalance/R.java src/com/dsbalance/*.java

d8 --lib fw/android.jar --min-api 26 --output build/dex \
    $(find build/classes -name "*.class")

cd build && cp base.apk unsigned.apk && cp dex/classes.dex .
zip -q -X unsigned.apk classes.dex

apksigner sign --ks ds.jks --ks-pass pass:dsbalance \
    --ks-key-alias ds --min-sdk-version 26 \
    --out dsbalance.apk unsigned.apk
```

## 项目结构

```
app/
  AndroidManifest.xml
  res/
    layout/activity_main.xml   主界面
    layout/widget.xml          小组件
    values/colors.xml          浅色配色
    values-night/colors.xml    深色配色
    xml/widget_info.xml        小组件配置
src/com/dsbalance/
  BalanceApi.java      网络请求、加解密、usage 解析
  MainActivity.java    主界面
  WidgetProvider.java  桌面小组件
  SecureField.java     掩码输入框
  Gate.java            生物识别 / PIN 验证
  Theme.java           主题切换
build.sh               一键编译
```

## 已知限制

- `updatePeriodMillis` 最小 30 分钟，Android 系统硬限制
- 平台用量接口是私有的，DeepSeek 改版即失效
- 用量数据有约 5 分钟延迟
- 深色模式：App 内切换生效，小组件跟系统走（RemoteViews 跨进程限制）
- 防的是「手机被人拿走随手点开」，不防 root / ADB

## License

MIT
