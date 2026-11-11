# 扫码插件模块（ScanPlugin）功能清单文档

> 版本：v1.0　　日期：2026-11-10　　状态：设计定稿，进入开发
> 目标：替换现有散落在 `JsBridge` 中的扫码逻辑，提供**可独立替换、可升级**的插件模块，解决「JS 调用慢」「横屏旋转」「DM 码（DPM 刻印）识别率低」三大问题。

---

## 一、现有机制分析（问题定位）

### 1.1 现有扫码链路（三条，互相独立）

| 链路 | 触发方式 | 回调方式 |
|---|---|---|
| Zebra 斑马（DataWedge） | JS 调 `JsBridge.softScan()` → 广播 `com.symbol.datawedge.api.SOFT_SCAN_TRIGGER` | 广播 `default` → `onReceive` → `loadUrl("javascript:onScaned(flag,result)")` |
| iData | `softScan()` 内同时 new `ScannerInterface().scan_start()` | 广播 `android.intent.action.SCANRESULT` → 同上 |
| 摄像头（zxing 封装库） | JS 调 `JsBridge.sanQR()` → `startActivityForResult(MyScan)` | `onActivityResult` → `evaluateJavascript("javascript:qrResult('...')")` |

另有 `ScannerGunManager`（扫码枪键盘模拟）已实现但**未接入** MainActivity。

### 1.2 问题根因

| 编号 | 问题 | 根因 | 影响 |
|---|---|---|---|
| P-01 | JS 调用慢 | `onScaned()` 使用 `loadUrl("javascript:...")` 回调，走 WebView 页面加载通道，排队慢、有阻塞感 | 扫码结果回传延迟明显 |
| P-02 | 回调易丢失 | 扫码内容手工字符串拼接，**无 JSON 转义**；内容含引号/换行/反斜杠时 JS 语法错误，回调静默失败 | 特殊字符码值无法回传 |
| P-03 | 横屏出现旋转过程 | `MainActivity` 强制 `SENSOR_LANDSCAPE`（MainActivity.java:188），`MyScan` 强制 `SENSOR_PORTRAIT`（MyScan.java:51,77），跳转时 Activity 旋转 90° | 用户可见的旋转动画、卡顿 |
| P-04 | 方向配置无效 | Manifest 中 `MyScan` 未声明 `screenOrientation`，`fullSensor` 错配在 `<provider>` 上（无效属性） | 不同设备行为不一致 |
| P-05 | DM 码识别率低 | `ZxingConfig` 未设置 `setDecodeFormats`，默认解码格式不含 DATA_MATRIX | DM 码扫不出 |
| P-06 | DPM 刻印码无法识别 | zxing 默认全局阈值二值化，对金属刻印（低对比度、反光、点阵、圆点网格）无识别能力 | 工厂零件码识别率极低 |
| P-07 | 架构耦合 | 扫码逻辑与打印/通知混在 47KB 的 `JsBridge` 中，回调两套规范（`onScaned`/`qrResult`） | 无法独立替换升级 |
| P-08 | 干扰体验 | 每次扫码成功都弹 Toast | 高频扫码时干扰操作 |

---

## 二、新插件功能清单（编号）

### A. 核心能力

| 编号 | 功能 | 说明 | 优先级 |
|---|---|---|---|
| F-01 | 独立插件包 | 全部代码收敛至 `com.weifu.scanplugin` 包，单一入口 `ScanPlugin`，与 `JsBridge` 零耦合，可整体替换/升级 | P0 |
| F-02 | 统一 JS 注入 | `webView.addJavascriptInterface(scanPlugin, "ScanPlugin")`，H5 通过 `window.ScanPlugin` 调用 | P0 |
| F-03 | 统一回调分发 | 全部回调走 `evaluateJavascript`（替代 `loadUrl`），Gson 做 JSON 转义，杜绝特殊字符回调丢失 | P0 |
| F-04 | 回调双风格 | 支持「全局函数监听」（兼容旧 `onScaned`）与「callbackId 一次性回调」（Promise 风格）两种模式 | P0 |
| F-05 | 设备扫码统一接口 | `ScanPlugin.softScan()` 内部自动适配 Zebra DataWedge / iData，H5 无需关心设备型号 | P0 |
| F-06 | 相机扫码（无旋转） | 新 `ScanCameraActivity` 继承 MainActivity 当前方向，跳转零旋转；CameraX `PreviewView` 自动处理预览旋转 | P0 |
| F-07 | 任意方向扫码框 | 扫码取景框随横/竖屏动态计算尺寸，横屏使用宽扁框，竖屏使用方形框 | P1 |
| F-08 | DM/DPM 增强解码 | 解码格式显式开启 `DATA_MATRIX` + 全格式；DPM 模式启用增强管线（见 C 组） | P0 |
| F-09 | 连续扫码模式 | `continuous: true` 时扫码成功不退出，持续回调（间隔防抖），适配产线连续盘点 | P1 |
| F-10 | 前置/后置切换 | `camera: 'front'/'back'` 指定摄像头 | P2 |
| F-11 | 补光灯控制 | `torch: true` 开启闪光灯持续补光，金属反光场景可开关 | P1 |
| F-12 | 相册选图识别 | `album: true` 支持从相册选图解码（DM 码拍照后识别） | P1 |
| F-13 | 扫码枪接入 | `ScannerGunManager` 接入插件，扫码枪（键盘模拟）结果统一走同一回调 | P1 |
| F-14 | 超时与取消 | `timeout` 秒超时自动关闭并回调 `cancel`；H5 可随时调 `ScanPlugin.stop()` 中止 | P0 |
| F-15 | 提示音/振动 | 扫码成功提示音（可关），替代 Toast 干扰 | P1 |
| F-16 | 设备探测 | `ScanPlugin.getScannerInfo()` 返回 JSON：设备类型、是否有激光头、是否支持 DataWedge | P1 |

### B. 性能优化项（对应 P-01/P-02/P-03）

| 编号 | 功能 | 说明 | 优先级 |
|---|---|---|---|
| F-17 | evaluateJavascript 回调 | 替换所有 `loadUrl("javascript:...")`，回调延迟从「页面加载队列级」降至「脚本执行级」 | P0 |
| F-18 | JSON 安全转义 | 回调参数统一 Gson 序列化，含引号/换行/中文的码值安全回传 | P0 |
| F-19 | 方向继承无旋转 | 三层锁定：①扫码页 Manifest `unspecified`（启动瞬间继承宿主方向，无跳变）；②扫码页 auto 一律 `SCREEN_ORIENTATION_LOCKED`（锁死打开瞬间方向，忽略传感器后续变化——`SENSOR_LANDSCAPE`/`SENSOR_PORTRAIT` 会响应传感器族内 180° 翻转，模拟器虚拟传感器延迟初始化/抖动时扫码页会在几秒后突然旋转）；③宿主 MainActivity 在 `onWindowFocusChanged` 首次触发时 `LOCKED` 锁死方向（否则取消扫码返回时，主页 `SENSOR_LANDSCAPE` 会响应扫码期间已变化的传感器而旋转） | P0 |
| F-20 | 预初始化 | 设备适配器在插件 `attach()` 时一次性创建并注册广播，`softScan()` 调用时零初始化开销 | P0 |
| F-21 | 解码线程池 | 相机帧解码在独立 `HandlerThread`（`scan-decode-thread`）执行，不阻塞 UI 与相机线程 | P0 |
| F-22 | 帧节流 | 图像分析按间隔取帧（默认 ~200ms），避免解码线程过载导致预览卡顿 | P1 |

### C. DM 码（DPM）识别率增强项（对应 P-05/P-06）

| 编号 | 功能 | 说明 | 优先级 |
|---|---|---|---|
| F-23 | 显式解码格式 | `DecodeHintType.POSSIBLE_FORMATS` 含 `DATA_MATRIX / QR_CODE / CODE_128 / CODE_39 / EAN_13 / AZTEC / PDF_417` 全集 | P0 |
| F-24 | TRY_HARDER | 开启 `DecodeHintType.TRY_HARDER`，启用 zxing 深度检测 | P0 |
| F-25 | 自适应二值化 | 局部均值自适应阈值（Integral Image 实现，O(1) 每像素），解决金属刻印明暗不均 | P0 |
| F-26 | 反色重试 | 亮底暗码 / 暗底亮码（激光刻印白字）双向尝试 | P0 |
| F-27 | 旋转重试 | 0/90/180/270 四方向旋转重试，解决任意角度摆放的零件 | P0 |
| F-28 | ROI 裁剪放大 | 取中心 ROI 区域 2 倍最近邻放大后重试，提升小尺寸 DM 码模块分辨率 | P1 |
| F-29 | 多帧融合 | 连续 N 帧解码尝试，任一帧成功即返回（低质量码靠概率累积提升成功率） | P0 |
| F-30 | 相机参数优化 | 连续自动对焦（AF）、点测光（AEL）、曝光补偿上调，DPM 模式建议开启补光灯 | P1 |
| F-31 | DPM 模式开关 | `dpm: true` 时启用完整增强管线（F-25~F-29），普通二维码走快速路径（仅 F-23/F-24），兼顾速度与识别率 | P0 |

### D. 兼容与工程化

| 编号 | 功能 | 说明 | 优先级 |
|---|---|---|---|
| F-32 | 旧接口兼容 | 保留 `JsBridge.softScan()/sanQR()` 与 `onScaned()/qrResult()` 回调不变，存量 H5 零改造可用 | P0 |
| F-33 | 新旧并存 | 新插件独立注入 `window.ScanPlugin`，与旧 `JsBridge` 并行运行，验证稳定后可整体下线旧链路 | P0 |
| F-34 | 演示页 | `assets/test.html` 增加新插件调用演示（含 DPM 模式、连续扫码、超时取消） | P1 |
| F-35 | 版本标识 | `ScanPlugin.getVersion()` 返回插件版本号，便于 H5 端做能力探测与灰度 | P1 |

---

## 三、JS API 规范（v1）

```js
// 1. 设备扫码（斑马/iData 自动适配），结果回调 window.onScaned(flag, result) —— 兼容旧版
ScanPlugin.softScan()

// 2. 相机扫码（Promise 风格，一次性回调）
ScanPlugin.scan(JSON.stringify({
  dpm: true,            // DPM 增强模式（默认 true，针对刻印 DM 码）
  continuous: false,    // 连续扫码
  timeout: 30,          // 超时秒数
  torch: false,         // 补光灯
  album: true,          // 允许相册选图
  formats: ["DATA_MATRIX","QR_CODE"], // 可选，默认全格式
  orientation: "auto"   // auto: 继承当前页面方向（无旋转）
}), "myCallbackId")

// 3. 全局监听（事件风格）
ScanPlugin.setCallback("onMyScan")   // window.onMyScan(jsonString)

// 4. 控制
ScanPlugin.stop()
ScanPlugin.getVersion()
ScanPlugin.getScannerInfo()          // 返回 JSON 字符串
```

**回调数据结构**（JSON 字符串，已转义）：

```json
{ "callbackId":"myCallbackId", "event":"result",
  "data":{ "text":"...码值...", "format":"DATA_MATRIX",
           "timestamp":1762000000000, "source":"camera|zebra|idata|gun" } }
```

事件类型：`result`（成功）/ `cancel`（取消/超时）/ `error`（异常）。

---

## 四、模块结构

```
com.weifu.scanplugin
├── ScanPlugin              // 门面：JS API 入口（@JavascriptInterface）
├── ScanPluginConfig        // 配置（Builder 模式）
├── ScanResultDispatcher    // 统一回调分发（evaluateJavascript + JSON 转义 + callbackId）
├── ScanCallback            // 回调数据模型
├── device
│   ├── DeviceScanner       // 设备扫码统一接口（start/stop/setListener/release）
│   ├── ZebraDataWedgeScanner   // 斑马 DataWedge 适配
│   ├── IDataScanner            // iData 广播适配
│   └── ScannerGunScanner       // 扫码枪（KeyEvent）适配
└── camera
    ├── ScanCameraActivity      // CameraX 相机扫码页（方向继承，无旋转）
    ├── ScanOverlayView         // 取景框/遮罩绘制（方向自适应）
    ├── DpmImageAnalyzer       // ImageAnalysis 回调 → 解码线程（帧节流）
    ├── DecodeEngine            // 多帧解码引擎（快速路径 + DPM 增强管线）
    └── ImagePreprocessor       // 灰度/自适应二值化/反色/旋转/放大
```

依赖新增：`androidx.camera:camera-*`（CameraX 1.2.3）。zxing 核心库复用旧封装库（`com.github.yuzhiqiang1993:zxing:2.2.9`）内嵌的 `libs/core-3.4.1.jar`（显式依赖 `core:3.5.x` 会与其产生重复类冲突，`checkDebugDuplicateClasses` 失败）；3.4.1 已包含新引擎所需的全部 API（`MultiFormatReader`/`DecodeHintType.ALSO_INVERTED`/`DataMatrixReader` 等）。

> 注：`ResolutionSelector`/`ResolutionStrategy` 为 CameraX 1.3+ API，1.2.x 须使用 `setTargetResolution()` 设置分析流分辨率。

## 无感替换（旧插件下线）

旧 `JsBridge` 扫码接口签名保留、内部全部转发新 `ScanPlugin`，存量 H5 零改造：

| 旧接口 | 转发目标 | 回调（保持不变） |
|---|---|---|
| `JsBridge.softScan()` | `ScanPlugin.softScan()` | `window.onScaned(flag, result)` |
| `JsBridge.sanQR()` | `ScanPlugin.scan()`（DPM 默认开启） | `window.qrResult(content)` |

已删除的旧实现：
- `JsBridge.onScaned()/softTriggerScan()/createProfile()/sendDataWedgeIntentWithExtra()` 及 DataWedge 常量
- `JsBridge.onReceive()` 中的 iData/斑马扫码广播分支（由插件设备适配器内部接收）
- `MainActivity.onActivityResult()` 扫码分支、`jsBridge.createProfile()` 调用、扫码 action 注册
- 旧文件：`ui/custom/MyScan.java`、`scan/ScannerInterface.java`（`scan/ScannerGunManager.java` 保留，被新插件扫码枪适配器复用）
- Manifest 中 `MyScan` 注册

DataWedge Profile 配置（原 `jsBridge.createProfile()`）已移植至 `ZebraDataWedgeScanner.ensureProfileConfigured()`，插件 attach 时幂等自动完成。

测试页开关：`MainActivity.LOAD_TEST_PAGE = true` 时加载 `assets/test.html`，`false` 时加载生产 `LOADRL`。

---

## 五、验收标准

| 编号 | 验收项 | 标准 |
|---|---|---|
| V-01 | 回调延迟 | 扫码结果回传 JS ≤ 50ms（设备广播 → evaluateJavascript） |
| V-02 | 特殊字符 | 码值含 `"` `\` 换行 中文 时回调不丢失 |
| V-03 | 无旋转 | 横屏主界面 → 相机扫码页跳转全程无旋转动画 |
| V-04 | DM 码 | 标准 DM 码（纸面打印）识别率 ≥ 99%，1s 内出结果 |
| V-05 | DPM 码 | 金属刻印 DM 码（激光打标样件）识别率相对旧版显著提升（多帧 + 增强管线） |
| V-06 | 兼容性 | 旧 H5 页面（`softScan/sanQR/onScaned/qrResult`）行为不变 |
| V-07 | 可替换性 | 删除 `scanplugin` 包 + 3 行接入代码即可完全回退 |

---

## 六、实施顺序

1. F-01~F-04：插件骨架 + 回调分发（解决 P-01/P-02）
2. F-05/F-20：设备适配层（Zebra/iData/扫码枪）
3. F-06/F-07/F-19：CameraX 扫码页（解决 P-03/P-04）
4. F-08/F-23~F-31：DPM 增强解码引擎（解决 P-05/P-06）
5. F-32~F-35：兼容层、演示页、文档
