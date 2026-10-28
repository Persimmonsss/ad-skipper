# ad-skipper

Android 免 root 广告跳过工具（无障碍服务实现）。

## 已有模块

### 红果短剧广告跳过（v1.3.0）

- 目标包名：`com.phoenix.read`、`com.phoenix.read.tv`、`cn.xiaochuankeji.zhibo`
- 文字关键词 + 倒计时 + 广告 SDK 控件三重检测
- 命中广告后自动上滑进入下一段内容，并统计成功/失败
- 前台服务常驻通知，实时显示短剧名、集数与统计数据

构建：Android Studio 打开工程，`app` 模块运行到设备，然后在系统设置中开启本应用的无障碍服务。

## Roadmap

- [ ] B 站信息流广告智能跳过
- [ ] 抖音等其它短视频平台
