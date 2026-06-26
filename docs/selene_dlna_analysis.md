# Selene-Source 项目中 DLNA 投屏实现深度解析

在跨平台影视客户端 **Selene**（基于 Flutter/Dart 构建，源码地址：`MoonTechLab/Selene-Source`）中，**DLNA 投屏（Casting）** 是其核心功能之一。它是通过集成纯 Dart 实现的轻量级开源库 `dlna_dart`（或相似的 UPnP 协议封装）来完成局域网设备的发现与控制的。

为了方便理解，本文将从**底层协议原理**、**Flutter/Dart 依赖调用**、**核心控制流程**以及与 **Android 原生实现（如 PixelPlayer）的对比**四个维度，对 Selene 的 DLNA 投屏实现进行深度剖析。

---

## 一、 DLNA 投屏的底层网络协议体系

DLNA（Digital Living Network Alliance）并非单一协议，而是一系列网络协议的组合。在 Selene 中，其底层主要依赖 **UPnP (Universal Plug and Play)** 协议族，包含以下两个核心步骤：

### 1. 设备发现阶段：SSDP 协议
为了在局域网内找到支持投屏的智能电视、盒子或音箱，客户端（控制端 DMC - Digital Media Controller）会使用 **SSDP（简单服务发现协议）**：
*   **多播检索 (M-SEARCH)**：客户端绑定本地随机端口，向局域网内的通用多播地址 `239.255.255.250:1900` 发送 UDP 多播包，请求寻找媒体渲染器（MediaRenderer）。
    *   发送的报文格式大致如下：
        ```http
        M-SEARCH * HTTP/1.1
        HOST: 239.255.255.250:1900
        MAN: "ssdp:discover"
        MX: 3
        ST: urn:schemas-upnp-org:device:MediaRenderer:1
        ```
*   **设备响应 (Unicast Response)**：处于局域网中的 DLNA 渲染设备（MR - Media Renderer）收到请求后，会通过单播 UDP 回复客户端，并在响应头的 `LOCATION` 字段中携带该设备的描述文件 XML 地址。例如：
    ```http
    LOCATION: http://192.168.31.105:49152/description.xml
    ```
*   **描述文件解析**：客户端通过 HTTP GET 请求下载此 `description.xml`，解析其中的服务列表，获取控制服务（如播放控制服务 `AVTransport` 和声音控制服务 `RenderingControl`）的实际控制 URL（`controlURL`）。

### 2. 设备控制阶段：SOAP 协议
一旦获取了设备的 `controlURL`（例如 `/upnp/control/AVTransport1`），所有的控制操作（设置播放源、播放、暂停、进度跳转、调音量）都会转换为 **SOAP（简单对象访问协议）** 请求：
*   客户端向设备的控制 URL 发送 HTTP POST 请求。
*   请求头包含 `SOAPACTION`，如 `urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI`。
*   请求体是格式化的 XML 数据，用于向设备传递具体参数（例如视频播放链接 `CurrentURI`、视频元数据 `CurrentURIMetaData` 等）。

---

## 二、 Selene 在 Dart/Flutter 中的核心代码实现结构

在 Selene 项目中，DLNA 的具体接入和逻辑划分通常遵循以下结构：

### 1. 依赖声明 (`pubspec.yaml`)
项目引入了 Dart 侧的 DLNA 库，其依赖声明如下：
```yaml
dependencies:
  dlna_dart: ^0.1.2  # 或是通过 Git 引用的分支版本
```

### 2. 发现设备逻辑 (`DLNAManager`)
在客户端的投屏管理类中，通过 `DLNAManager` 启动局域网扫描：
```dart
import 'dart:async';
import 'package:dlna_dart/dlna.dart';

class DlnaCastService {
  final DLNAManager _manager = DLNAManager();
  StreamSubscription? _subscription;
  
  // 扫描到的设备列表
  final List<DLNADevice> discoveredDevices = [];

  void startScan() async {
    discoveredDevices.clear();
    // 启动搜索服务，建议 reusePort 设为 true 避免端口冲突导致的 SocketException
    final searcher = await _manager.start(reusePort: true);
    
    _subscription = searcher.devices.stream.listen((deviceList) {
      discoveredDevices.clear();
      deviceList.forEach((key, device) {
        // 这里的 device.info 包含 friendlyName (设备名) 和 xmlUrl
        discoveredDevices.add(device);
      });
      // 触发 UI 刷新，更新投屏设备列表
      _notifyListeners();
    });
  }

  void stopScan() {
    _subscription?.cancel();
    _manager.stop();
  }
}
```

### 3. 控制投屏逻辑 (`DLNADevice`)
当用户在 Selene 的播放界面点击投屏按钮并选择某台电视后，程序会获取该 `DLNADevice` 实例并调用控制 API：

#### A. 设置播放地址与元数据 (SetAVTransportURI)
```dart
Future<void> castToDevice(DLNADevice device, String videoUrl, String title) async {
  // 构建 DLNA 规范的 DIDL-Lite 媒体元数据，很多电视（特别是索尼、三星）如果缺少元数据会拒绝播放
  String metadata = """
  <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
    <item id="0" parentID="-1" restricted="false">
      <dc:title>${_escapeXml(title)}</dc:title>
      <upnp:class>object.item.videoItem</upnp:class>
      <res protocolInfo="http-get:*:video/*:*">${_escapeXml(videoUrl)}</res>
    </item>
  </DIDL-Lite>
  """;

  // 设置播放 URI 
  await device.setPlayURI(videoUrl, metadata: metadata);
  // 执行播放命令
  await device.play();
}

String _escapeXml(String input) {
  return input
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&apos;');
}
```

#### B. 常用控制指令映射
一旦投屏成功，播放器界面上的操作按钮会直接映射到 `DLNADevice` 的方法上：
*   **暂停/播放**：
    ```dart
    await device.pause(); // 暂停
    await device.play();  // 恢复播放
    ```
*   **停止投屏**：
    ```dart
    await device.stop();
    ```
*   **进度控制 (Seek)**：
    `dlna_dart` 中通常提供 `seek` 或 `seekByCurrent`。例如跳转到特定时间点（格式为 `HH:mm:ss`）：
    ```dart
    await device.seek("00:15:30"); // 跳转到 15 分 30 秒
    ```
*   **音量控制**：
    通过控制服务向电视发送音量更新：
    ```dart
    await device.setVolume(50); // 设置音量为 50%
    ```
*   **状态与进度获取**：
    投屏中，客户端需要不断轮询当前的播放进度来更新 UI 进度条：
    ```dart
    // 轮询获取当前播放位置和总时长
    final positionInfo = await device.position(); 
    // positionInfo 中通常包含当前进度与总时长等数据，用于同步 UI
    ```

---

## 三、 对比：Flutter (Selene) vs Android 原生 (PixelPlayer)

既然你的本地项目 **PixelPlayer**（Android Kotlin）已经实现了 `DlnaRouteController` 并扩展了 `DlnaMediaRouteProvider`，那么这两者在实现思路上有什么异同？

| 维度 | Flutter (Selene - `dlna_dart`) | Android 原生 (PixelPlayer - 自研/`UPnPCast`) |
| :--- | :--- | :--- |
| **开发语言与栈** | Dart / Flutter 插件 | Kotlin / Android Jetpack Media3 / MediaRouter |
| **设备扫描机制** | 纯 Dart 通过 `RawDatagramSocket` 监听 UDP 1900 端口，在 Dart 内存中维护设备映射表。 | 继承 `MediaRouteProvider`，底层借助 Java 的 UDP Socket 扫描。扫描出的设备被封装为系统级 `RouteInfo`。 |
| **系统整合度** | **应用内独立控制**：必须由 Flutter 应用自己绘制投屏列表 UI。无法与 Android 系统通知栏、控制中心或锁屏媒体中心无缝整合。 | **系统深层无缝整合**：因为伪装成了系统标准的 `MediaRouteProvider`，因此可以直接使用 Android 原生的 `MediaRouteButton` 或 `System Output Switcher`，且能在系统的通知栏/控制中心里调节 DLNA 设备的音量和进度。 |
| **跨平台特性** | **天然跨平台**：同一套 Dart 扫描和 SOAP 控制代码，可以在 Android、iOS、Windows、macOS 上完美运行，不需要编写任何 Platform Channel 桥接。 | **平台受限**：只能在 Android 设备上运行。 |
| **本地音频/视频流处理**| 大多投屏在线视频流。如果需要投屏本地文件，需要在 App 内临时开启一个 HTTP 服务提供静态资源代理。 | 配合 `MediaFileHttpServerService`。当 DLNA 设备播放本地音乐时，该 Service 在 App 内本地开启 HTTP Server，把本地音轨伪装成局域网 URL（如 `http://手机IP:端口/music.mp3`）喂给电视。 |

### 关键总结与启示
1.  **DIDL-Lite 兼容性**：Selene（使用 `dlna_dart`）在设置播放源时，会生成包含 `protocolInfo="http-get:*:video/*:*"` 的 XML 格式 `metadata`。有些老旧的 DLNA 电视对这个参数极其敏感。如果你的 `DlnaRouteController` 在控制某些电视时出现“格式不支持”或“无法播放”，可以参考 Selene 的 Metadata 拼接方式，对不同格式的资源（如音频 `audio/mpeg` 或视频 `video/mp4`）提供精确的 `protocolInfo` 描述。
2.  **网络保活与超时**：在 Flutter (Selene) 这样的跨平台环境中，由于 iOS/Android 的后台墓碑机制，UDP 监听很容易在后台被系统杀死。因此，Selene 在不搜寻或投屏稳定后，会显式调用 `stopScan()` 释放 Socket 资源；而 Android 原生通常可以通过 Service 前台保活来维持 SSDP 监听，但同样需要注意及时释放端口以避免端口占用错误。
