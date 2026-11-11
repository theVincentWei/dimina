# Dimina 容器侧通知补丁(wx.requestSubscribeMessage API 面)

> 2026-11-11 起随四仓减负自万器仓迁入本仓 `patches/notify-only/`;万器仓保留契约镜像 `docs/notify-contract.md`。

万器的小程序通知系统需要容器(Dimina)提供微信同形的订阅 API 面。**万器平台不携带容器源码**,
这里存的是容器侧所需的全部改动物化成的补丁——取自本仓库(dimina fork)main 分支真实提交
`191a6fb`(feat: wx.requestSubscribeMessage 订阅消息 API 面(fe+Android+iOS)+ getSetting.subscriptionsSetting,2026-11-11),
11 个文件,+577/−19。

## 为什么需要这些改动

契约(见 万器仓 `docs/notify-contract.md`)锁死三层分工:框架(Dimina)只做
API 名字/签名/参数校验/桥接;弹面板归宿主;存通知与记账归平台(万器 server,万器仓 `server/`)。
若框架不注册 `wx.requestSubscribeMessage`,小程序 JS 连「申请订阅」这一步都发不出去
(`is not a function`),平台无从记账。这套补丁就是「让小程序看见这个 API」的最小改动。

## 补丁清单(文件 → 改动摘要)

| 补丁 | 仓库路径 | 改动摘要 |
|---|---|---|
| [fe-subscribe-message-service.patch](fe-subscribe-message-service.patch) | `fe/packages/service/src/api/core/open-api/subscribe-message/index.js`(新增,+13) | `requestSubscribeMessage(opts)` → `invokeAPI(...)` 三行模式;`import.meta.glob` 自动挂载即注册 |
| [fe-subscribe-message-tests.patch](fe-subscribe-message-tests.patch) | `fe/packages/service/__tests__/subscribe-message.spec.js`(新增,+63) | service 层测试:happy path 以微信形状 resolve;非点击态 fail |
| [fe-compatibility-reference.patch](fe-compatibility-reference.patch) | `fe/packages/compiler/src/common/compatibility-reference.js`(+1) | 编译器兼容清单登记 `subscribe-message`,消除「Unsupported wx API」警告 |
| [android-subscribe-message-api.patch](android-subscribe-message-api.patch) | `android/.../api/openapi/SubscribeMessageApi.kt`(新增,+164) | 参数校验(`tmplIds` 必须 string[] 且 ≤5)→ 经 `invokeExtModule` 回宿主弹面板/查状态 → 结果映射回微信形状;宿主漏项按 `reject` 补位不伪造 `accept`;值域 `accept/reject/ban/filter` 值域外整次 fail;无宿主/宿主失败 → `requestSubscribeMessage:fail` |
| [android-setting-api.patch](android-setting-api.patch) | `android/.../api/openapi/SettingApi.kt`(+88) | `getSetting`/`openSetting` 响应新增 `subscriptionsSetting{mainSwitch,itemSettings}`(读宿主扩展通道);宿主未注册/查询失败回落默认 `{mainSwitch:true, itemSettings:{}}` |
| [android-api-registry.patch](android-api-registry.patch) | `android/.../api/ApiRegistry.kt`(+31) | 新公开薄封装 `invokeExtModule(module, event, args, callback)`:原生 API handler 主动回调宿主扩展模块的一次性调用;不改 `extBridge`/`extOnBridge` 既有行为 |
| [android-mini-app-registration.patch](android-mini-app-registration.patch) | `android/.../core/MiniApp.kt`(+2/−2) | 注册 `SubscribeMessageApi` |
| [ios-subscribe-message-api.patch](ios-subscribe-message-api.patch) | `iOS/.../OpenAPI/SubscribeMessageAPI.swift`(新增,+146) | 行为逐条对齐 Android 版(校验/回宿主/值域/fail 语义) |
| [ios-setting-api.patch](ios-setting-api.patch) | `iOS/.../OpenAPI/SettingAPI.swift`(+82) | `subscriptionsSetting` 同 Android 对齐 |
| [ios-container-api-registration.patch](ios-container-api-registration.patch) | `iOS/.../DMPContainerApi.swift`(+1) | 注册 `SubscribeMessageAPI` |
| [docs-api-reference.patch](docs-api-reference.patch) | `docs/API-Reference.md`(+3) | 容器 API 文档登记新接口与新字段 |
| [dimina-notify-full.patch](dimina-notify-full.patch) | (以上全部合并) | 一把 `git apply` 用 |

## 应用方式

在本仓库根(或你自己的容器 fork)执行:

```bash
git apply dimina-notify-full.patch        # 或逐个 git apply <per-file>.patch
```

补丁基于 dimina `main@191a6fb` 快照;容器若已前移,建议按 per-file 补丁人工对照(改动都是
「照既有 SettingApi 形状新增 + 一处注册」的机械形状,冲突面很小)。

## 宿主侧还要做什么(不在本仓库,列出以备对照)

容器只回调,不弹 UI。宿主(如 dsh-go)需通过 `registerExtModule("dsh-notify", ...)` 注册一个
handler,处理两个事件并回传结果:

| 事件 | 入参 | 返回 |
|---|---|---|
| `showSubscribePanel` | `{appId, tmplIds: [...]}` | `{results: {tplId: 'accept'\|'reject'}}` |
| `getSubscribeState` | `{}` | `{mainSwitch: bool, itemSettings: {tplId: 'accept'\|'reject'}}` |

宿主拿到 accept/reject 后,调平台 `POST /api/v1/notices/consent`(loopback)记账。契约细节见
万器仓 `docs/notify-contract.md` §3.5/§3.4。
