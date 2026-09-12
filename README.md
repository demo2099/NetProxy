<div align="center">

<img src="assets/android/ic_launcher-web.png" width="110" alt="星际穿越" />

# 星际穿越 · interstellar

**星河漫漫， 穿越光年。**

Android 7.0+ · v0.3.0 · Kotlin + Jetpack Compose

</div>

Android 上的 sing-box 代理客户端。UI 采用「航空航天玻璃 + 任务控制台」设计语言（参考 satelite-proxy）：深空底色、玻璃拟态卡片、环境光晕、马卡龙主题色、状态驱动 Hero、遥测仪表网格与底部玻璃 dock。

<div align="center">

<img src="assets/app.jpg" width="300" alt="应用截图" />

</div>

## 功能

### 内核与代理

- **sing-box 1.14 内核**：本地 gomobile 构建的 `libbox.aar`（四 ABI + universal），随 APK 按 ABI 分包
- **双服务模式**：VPN（TUN）/ 代理模式，支持 allowBypass、autoRedirect
- **出站模式**：规则 / 全局 / 直连，首页快速切换、即时生效
- **快捷磁贴**：系统下拉快捷开关一键启停，状态与内核实时同步
- **电池优化豁免**：状态化引导，已豁免后点击跳转应用详情，返回自动刷新

### 订阅

- **多格式导入**：Clash YAML / 分享链接（ss·vmess·vless·trojan·hysteria2·tuic·anytls…）/ Base64 自动嗅探
- **导入方式**：URL（伪装 clash-verge UA，解析 `subscription-userinfo` 流量头）、文本导入、剪贴板自动识别
- **流量显示**：剩余流量 / 到期信息
- **自动更新**：WorkManager 定时拉取（1/6/12/24h），运行中热重载，更新结果即时反馈
- **Mix 多订阅合池**：多订阅节点合并为单一池并标注来源，流量/到期仍按订阅独立
- **机场兼容**：自动过滤「剩余流量/到期」等信息假节点

### 节点

- **热切换**：libbox `CommandClient` 直连本地 abstract socket，切换/测速/日志/状态无需 HTTP API、不重启内核
- **URL Test 测速**：按钮实时显示总进度（N/M 等宽走字），延迟着色，未连接状态也可列出并测速
- **协议摘要**：节点卡直接显示协议信息（vless·grpc·tls），长按详情展开完整协议参数
- **布局与排序**：网格 / 列表双布局、延迟 / 名称排序，状态持久化
- **分组**：地区分组吸顶折叠、长按详情；可选按国家 urltest 分组（香港/新加坡/…）

### 分流规则

- **三种动作**：域名（精确 / 后缀 / 关键词匹配）→ 命中直连（DIRECT）、走 include / exclude 关键词筛选的独立 urltest 池；总开关独立于节点选择（锁定节点也不影响分流）
- **内置大陆分流**：`geoip-cn.srs` + `geosite-cn.srs` 随 APK 打包（`assets/rules/`），首次启动拷贝到 `filesDir/rules` 以 local rule-set 引用——大陆域名/IP 直连，无需在线下载（规避 raw.githubusercontent 直连不通的死锁）
- **去广告**：`category-ads-all.srs` 内置规则集，一键拦截广告/跟踪域名
- **局域网直连**：RFC1918 / ULA / 链路本地地址直连并排除出 TUN

### DNS 解析

- **手动解析覆写**：域名 → 固定 IP（hosts 语义，精确匹配），覆盖任何上游应答
- 仅接受 IPv4 / IPv6 字面量（字符集守卫 + 字面量解析双重校验），注入生成配置的 hosts DNS server

### 分应用代理

- 白名单 / 黑名单两种模式
- 应用列表（图标 + 搜索 + 批量选择 + 常用应用预置）
- 修改即时生效（内核热重载）

### 界面与交互

- **底部玻璃 dock 导航**：首页 / 节点 / 订阅 / 设置 四 tab 直达；页面 `HorizontalPager` 跟手拖动 + iOS 风格吸附动画，dock 点击与程序化切换直达（无滚动等待）
- **玻璃控制台设计语言**（参考 satelite-proxy）：航空航天深空底色（#11141C / #EEF0F4）、半透明玻璃卡片（左上高光渐变 + 发丝描边）、全屏 accent 环境光晕、磨砂分段控件、跟随系统深浅色
- **马卡龙主题色**：薄荷/天蓝/香芋/蜜桃/奶橙/湖青六色预设，一个 accent 换肤整套 UI（含光晕与 Hero）；语义色不随 accent（下载绿/上传红、警告金、危险橙）
- **状态驱动 Hero**：Face ID 笑脸（眨眼/呼吸/嘴角随连接状态变化）或经典轨道（同心圆环 + 轨道卫星，运行时旋转）双样式可切换
- **任务控制台首页**：状态胶囊（RUN/OFF）独占居中 + 大字节点名（字号自适应；连接中显示实时出口，未连接显示下次连接将使用的节点）+ 路由快速切换（规则/全局/直连）+ 等宽双按钮（启动代理/断开连接 + 切换节点）+ 遥测仪表网格（核心运行时长与连接数、实时流量曲线、出口网络探测、订阅额度）
- **网络探测**：一键竞速多个公共 IP API（经代理时走当前节点出口），显示出口 IP/地区/延迟，连接后自动探测
- **延迟色阶**：绿 <200ms / 黄 <300ms / 红，全局统一
- **通知卡片**：标题「星河漫漫 · 穿越光年」，VPN 系统名「星际穿越」
- **连接监控**：活跃连接实时列表（域名/规则/链路/速率），单条/全部断开
- **日志页**：内核日志实时流 + 一键复制导出
- **性能与细节**：手势滑动零状态写入（消除吸附瞬间整树重组）、常驻动画门控与计时器隔离、按压缩放反馈、下拉刷新、滑动删除、触感反馈；竖屏锁定

## 项目结构

```
app/src/main/java/com/interstellar/proxy/
├── MainActivity.kt         # dock 导航 + HorizontalPager + 子页面栈
├── InterstellarApplication.kt
├── bg/                     # 服务层（移植自 sing-box-for-android 最小集）
│   ├── BoxService.kt       # CommandServer 生命周期粘合
│   ├── VPNService.kt       # VpnService.Builder / openTun / protect
│   ├── ProxyService.kt     # 代理模式服务
│   ├── QuickTileService.kt # 快捷磁贴
│   ├── PlatformInterfaceWrapper.kt / LocalResolver.kt（系统 DNS）
│   ├── DefaultNetworkMonitor.kt / DefaultNetworkListener.kt
│   └── ServiceNotification.kt / ServiceBinder.kt
├── constant/               # Action / Alert / Status
├── data/
│   ├── model/              # ProxyNode（统一节点模型）/ CustomRouteRule / DnsOverrideEntry
│   ├── subscription/       # SubscriptionParser（嗅探）/ ClashParser / UriParser / SingboxOutboundConverter / YamlToJson
│   ├── config/ConfigBuilder.kt  # sing-box JSON 生成（对应桌面端 builder.rs/dns_build.rs）
│   ├── net/SubscriptionFetcher.kt / NetProbe.kt（出口 IP 竞速探测）
│   ├── SubscriptionRepository.kt / UpdateWorker.kt（定时更新）
│   ├── CustomRulesStore.kt / DnsOverridesStore.kt / RulesStore.kt / NodeMatcher.kt / CommonProxyApps.kt
│   └── Settings.kt / ConfigStore.kt
├── ui/
│   ├── theme/              # Theme.kt（航空航天玻璃深浅色）/ Accents.kt（马卡龙 accent）/ Motion.kt
│   ├── AppViewModel.kt     # CommandClient 状态/组/模式订阅 + 业务动作
│   ├── ConnectionsViewModel.kt / LogsViewModel.kt / PerAppProxyViewModel.kt
│   ├── components/         # GlassComponents（玻璃卡/胶囊按钮/GlassDock/流量曲线/轨道 Hero）/ FaceMark（笑脸）/ IosComponents / MotionComponents / AmbientGlow / Common
│   └── pages/              # Dashboard（首页）/ Nodes（节点）/ Subscriptions（订阅）/ Settings / Connections / Logs / PerAppProxy / CustomRules / DnsOverrides / AddSubscriptionDialog
├── ktx/                    # Context / Continuations / Iterators
└── utils/                  # CommandClient / CommandTarget
```

## 构建

1. **libbox.aar**（已含在 `app/libs/`，如需重建）：
   ```bash
   # 需要 Go 1.26+、NDK r28、JDK
   cd /path/to/sing-box && make lib_android
   # 产物 libbox.aar 拷贝到 app/libs/
   ```
   本项目使用 tag `v1.14.0-beta.17`，`-javapkg=io.nekohasekai -libname=box`。

2. **APK**：
   ```bash
   ./gradlew assembleDebug    # 地心游记（.debug 后缀包名 + 淡紫图标，与正式版共存）
   ./gradlew assembleRelease  # 星际穿越（按 ABI 分包，约 40MB/个）
   ```
   - 产物统一命名 `interstellar-<abi>-<buildType>.apk`。
   - 签名：根目录 `signing.properties`（gitignored），缺失时 release 自动回退 debug 签名，fresh checkout 也能构建。
   - 网络受限环境可用 `mirrors.cloud.tencent.com/gradle` 发行镜像（已在 wrapper 配置）。

3. **CI**（`.github/workflows/release-apk.yml`）：推送 `v*` tag 自动编译 release APK 并发布到 GitHub Release。
   - 版本统一在根目录 `version.properties` 维护（versionCode/versionName，设置页"版本"行也读它）；发版改该文件后打 `v<versionName>` tag，CI 会校验两者一致。
   - libbox.aar 在 CI 上从 sing-box 源码重建（按 `SINGBOX_TAG` 缓存）。
   - 正式签名需配置 repo secrets（`INTERSTELLAR_KEYSTORE_B64` = base64(jks)、`INTERSTELLAR_STORE_PASSWORD`、`INTERSTELLAR_KEY_ALIAS`、`INTERSTELLAR_KEY_PASSWORD`），未配置时回退 debug 签名。

## 技术要点

- 内核控制全部走 libbox `CommandClient`（本地 abstract socket），节点热切换/测速/日志/状态无需 HTTP API
- 配置生成遵循 sing-box 1.11+ 规范：rule actions、typed DNS servers、tun `address` 字段
- 生成后 `Libbox.checkConfig` 校验，失败即拒绝写入
- 前台服务 `systemExempted`（VPN）/ `specialUse`（代理），符合 Android 14+ FGS 规范
- 技术栈：Kotlin + Jetpack Compose（Material3）、kotlinx-serialization、kaml（YAML）、OkHttp、WorkManager、DataStore

## Roadmap

- [ ] 内置规则集定期更新（随版本发布更新 .srs）
- [ ] 智能切换（移植 smart_switch.rs）
- [x] 订阅自动更新（WorkManager）
- [x] 分流规则自定义（域名 → 直连 / 节点关键词）
- [x] DNS 手动解析覆写（域名 → 固定 IP）
- [x] 玻璃控制台 UI 重设计（参考 satelite-proxy：玻璃拟态 + 仪表网格 + 马卡龙 accent + 流量曲线 + 网络探测 + 底部 dock 导航）

## 致谢

- [sing-box](https://github.com/SagerNet/sing-box) — 内核
- [sing-box-for-android (SFA)](https://github.com/SagerNet/sing-box-for-android) — 服务层移植参考
