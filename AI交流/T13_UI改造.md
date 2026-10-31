# T13 · UI / 图标 / 音效改造 v2.2.0（豆包QA 完成，待验收）

> 用户需求（原话要点）：「自己调用模型来生成图片，优化整个软件的 UI，所有门面（icon/UI），不要偷懒用 emoji，考虑适配/美观/风格统一，加少量按键音效，不要太花里胡哨」。
> 过程约束（用户后补充）：**尽可能不生图**，最终只生成了「小狐狸挥手」1 张主图 + 1 张组合标，其余全部用代码资源实现。

## 一、交付物

| 项 | 内容 | 位置 |
|---|---|---|
| 安装包 | ad-skipper-v2.2.0.apk（5.39 MB，versionCode=6 / versionName=2.2.0，Debug 签名与旧包一致可覆盖） | `C:\Users\Persimmon\Desktop\跳广告工具\ad-skipper-v2.2.0.apk` |
| 图标源图 | 小狐狸主图 2048px | `design_assets\fox_icon_master.png` |
| 提交 | `258cdff`（29 files，+230/-54，本地未 push） | git main |
| 构建环境 | 复用 `C:\adskipper-build` 离线打包（gradle 8.7 + `cctools\tools\jdk17` + SDK `D:\Work\AndroidStudioSDK`） | — |

## 二、改了什么（对比 v2.1.2）

### 1. App 图标（系统默认 → 品牌图标）
- 生成 1 张「小狐狸挥手」卡通主图（用户从 8 个候选里选定，编号③），风格：浅米底 + 橘色狐狸，亲民治愈。
- 落地为全套 launcher 资源：
  - `mipmap-mdpi~xxxhdpi/ic_launcher.png`（48–192px，传统图标）
  - `mipmap-*/ic_launcher_round.png`（圆形裁切，老设备圆形桌面）
  - `mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml`（adaptive icon：`icon_bg`=取自主图角部的 `#FEF7F2` 背景 + 108dp 前景图）
  - `AndroidManifest.xml`：`icon`/`roundIcon` 从 `@android:drawable/sym_def_app_icon` 改为 `@mipmap/ic_launcher` / `ic_launcher_round`。
- 适配性：前景按 adaptive 安全区（108dp 画布全图居中），MIUI/各家 launcher 圆形/圆角遮罩下狐狸主体不会被裁掉。

### 2. 仪表盘 UI（紫色浅色 → 亲民浅色柔和风）
- `colors.xml` 重做调色板：狐狸橘主色 `#F97316` + 柔和青绿点缀 `#22A98F`，暖奶油背景 `#FBF6EF`，深暖棕文字 `#3A322A`，彻底移除原紫色 `#6200EE/#3700B3`（用户偏好：不要大量紫/蓝）。
- `themes.xml`：`colorPrimary/colorSecondary` 全部替换 + 浅色状态栏（`windowLightStatusBar=true`）。
- `activity_main.xml` 重排：
  - 顶部新增 **Hero 品牌区**（奶油→浅橘渐变底 + 狐狸图标 + 名称 + 副标题），App 第一眼有门面；
  - 权限卡 4 行各加 **状态点**（`dot_a11y/nls/overlay/battery`，绿=已开启 / 橙=未开启），一眼看出缺哪个权限；
  - 卡片圆角 18→22dp、描边与文字色全部换成暖色系，开关与说明文字层级统一；
  - **所有原控件 id 保留**（`tv_a11y_status`…`tv_version`），MainActivity 逻辑零破坏。

### 3. 状态点联动（MainActivity）
- `render()` 每秒刷新时同步设置 4 个状态点颜色（`setDot()`：`dot_green` / `dot_orange`），与文本状态严格一致。

### 4. 按键音效（轻量，零资源）
- `playClick()`：`AudioManager.playSoundEffect(SoundEffectConstants.CLICK)`——系统自带点击音，**不新增任何音频资源、不花哨**。
- 触发点：全部按钮（去开启×3、去设置、清空、打开B站、打开红果）+ 4 个功能开关切换。
- 注意：无障碍服务执行跳过/滑动时**不**播任何音（那是后台操作不是用户按键）。

### 5. 构建工程修正（必要，否则离线打包失败）
- `build.gradle.kts`：`lint { checkReleaseBuilds = false }`——离线构建环境没有 lint-gradle 组件缓存，`lintVitalAnalyzeRelease` 必失败（主 agent 之前构建没跑 lint 应是同样原因，这次显式声明）。

## 三、真机验证状态（K40，serial <内网adb地址>）

| 项 | 结果 |
|---|---|
| 覆盖安装 v2.1.2 → v2.2.0 | Success（817ms 增量安装） |
| versionCode / versionName | `6` / `2.2.0`（`dumpsys package` 确认） |
| 启动崩溃 | 无（`dumpsys activity` 任务可见，logcat 无 FATAL/AndroidRuntime） |
| 视觉验收 | **受阻**：设备处于 MIUI 锁屏/AOD（PIN 锁），`screencap` 黑屏无法自动截图，需用户解锁后肉眼验收 |

## 四、待用户/主 agent 处理（协作项）

1. **悬浮窗权限被覆盖安装重置**（`SYSTEM_ALERT_WINDOW granted=false`，v2.1.2 交付声明里就提过这个坑）：用户需重新开一次「悬浮窗」权限，否则气泡提示功能不可用。
2. **视觉验收**：用户解锁 K40 打开 App 看一眼图标/UI；若哪处不协调（颜色、圆角、间距）我再微调——都改的是资源 XML，成本低。
3. **README 同步**（主 agent 地盘，未动）：README 里版本号/截图/CHANGELOG 需补 v2.2.0（版本号现已单一来源 `build.gradle.kts`，其他地方不会再漏）。
4. git 未 push（沿用老规矩，等用户点头）。

## 五、未做（刻意控制范围）

- SkipBubble（悬浮气泡）样式未改：那是动态 GradientDrawable，本轮聚焦仪表盘门面，气泡配色若用户觉得不搭可下一轮统一。
- 没有新增任何音频/字体资源；没有改任何功能逻辑（仅布局+颜色+状态点+音效）。
