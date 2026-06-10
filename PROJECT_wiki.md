# PixelPlayer 架构 Wiki

本文档用于记录项目中的公共文件、通用工具函数以及核心数据结构的变更。

## DLNA 投屏架构 (2026-06-10)

为了打破 Google Cast 的设备限制，项目引入了通用的 DLNA/UPnP 投屏支持。

### 核心变更模块

1. **`com.theveloper.pixelplay.dlna.DlnaMediaRouteProvider`**
   - **类型**: 公共提供者类 (继承自 `androidx.mediarouter.media.MediaRouteProvider`)
   - **职责**: 负责在局域网内通过 UDP/SSDP (底层使用 `UPnPCast` 库) 扫描非 Google 的智能电视和音箱。
   - **设计亮点**: 将扫描到的设备伪装成带有 `MediaControlIntent.CATEGORY_REMOTE_PLAYBACK` 的标准 Android Route，使得现有的 `CastBottomSheet` 无需任何代码修改就能直接显示这些设备。

2. **`com.theveloper.pixelplay.dlna.DlnaRouteController`**
   - **类型**: 媒体路由控制器
   - **职责**: 拦截系统中针对 DLNA 设备下发的“播放、暂停、调音量、快进”等操作，将它们翻译成 UPnP 标准的 SOAP XML 请求发送给电视。
   - **本地服务器联动**: 在收到播放指令时，会利用项目中已有的 `MediaFileHttpServerService` 获取本地音乐的 HTTP 串流地址，喂给电视进行播放。

3. **`CastStateHolder` (修改)**
   - **核心改动**: 在 `init` 阶段，主动将 `DlnaMediaRouteProvider` 挂载到全局的 `MediaRouter.getInstance(context)` 中。这是实现 DLNA 架构与现有 Cast UI 无缝整合的枢纽点。
