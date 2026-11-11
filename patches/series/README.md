# dimina 全量本地适配补丁系列(27 patches)

> 2026-11-11 起本系列随四仓减负自万器仓 `patches/dimina/series/` 迁入本仓 `patches/series/`;对账与再生方式不变。

Dimina 容器 fork 相对上游 didi/dimina 的**全部本地适配**存档,不止通知:微信语义深色模式全套、
菜单/弹层主题化、音频 API、redirectTo 双层修复、升级收归框架、订阅消息 API 面、tabBar WIP。

## 覆盖声明

- **基线** = 上游 didi/dimina `main @ eef595cf6d756701bf0d32b9a90e317f86ba944e`(merge-base,克隆时点 tip)。
- **本地增量 = 30 个提交 = 本系列 27 个 patch + 3 个 merge 提交**(`format-patch` 固有跳过 merge;
  3 个 merge 均为特性分支合入,无未被携带的自含内容)。
- **增量记录**:patch 0026(autoSync 静默同步,task-9)由 wanqi-server 于 2026-11-11 追加,追加后重跑
  git am 干跑 26/26 零冲突,树 SHA 与 fork main 重新对账一致。
- **无损对账(树 SHA 铁证)**:从基线 `git am` 全系列后,最终树 SHA
  `4c58683f2229c89be5074d09dfbe9daca575b0a9` 与 dimina 本地 `main @ 7c8a3af` 的树 SHA **完全一致**——
  应用结果与 fork 工作树逐字节相同(存档时点 1 为 25 patches 时的
  `777918bb…` @ 5121b17,追加 0026 后按上表增量记录更新)。
- 存档时点:2026-11-11;dimina 工作树在存档前已把 TabBar WIP 显式提交(`wip(tabbar)…`,patch 0025),工作树干净。

## 应用方式(git am)

```bash
git clone https://github.com/didi/dimina.git && cd dimina
git checkout eef595cf6d756701bf0d32b9a90e317f86ba944e      # 基线(上游 tip)
git am /path/to/dimina/patches/series/*.patch              # 按 0001→0027 编号顺序
```

干跑实测:26 个 `git am` 全过、零冲突,完成后树与 fork main 逐字节一致(0026 时点)。
- **增量记录**:patch 0027(通知铃铛+前端 Bundle 升级强制自动)由 lead 于 2026-11-11 追加,
  父提交即 `7c8a3af`,单 patch 应用后树 SHA `3144e2097736807edad552d4258245721d29d39c` 与
  dimina 本地 `main @ ed16234` 一致。注:main 上的 `c0d76f6`(误用旧基线覆盖 autoSync)与
  其 revert 未入系列——本 patch 0027 是对 `7c8a3af` 的净增量,已含合流后最终内容。
注意 commit SHA 会变(patch 重放),**树内容不变**;对账用 `git rev-parse HEAD^{tree}`。

## 逐 patch 表格

| # | 文件 | dimina commit | 特性域 | 内容 |
|---|---|---|---|---|
| 0001 | [`0001-extras.patch`](0001-extras.patch) | `4d99942` | 升级框架 | 升级收归框架:容器确认弹窗+字段化 extras+死任务清理+宿主事件(胶囊主题运行时覆写入库) |
| 0002 | [`0002-examples-AppHub-glm-quota-ledger-sys-monitor-weui-Co.patch`](0002-examples-AppHub-glm-quota-ledger-sys-monitor-weui-Co.patch) | `8b092e6` | 杂务 | 已上架四项目迁出至独立仓 ~/Code/dsh-go-miniprograms,上游示例保留 |
| 0003 | [`0003-dmcc-darkmode-app-config.json-app-darkmode-theme-lig.patch`](0003-dmcc-darkmode-app-config.json-app-darkmode-theme-lig.patch) | `27cd112` | dmcc-darkmode | dmcc 支持微信语义 darkmode:app-config 增 darkmode/theme={light,dark},@key 透传,theme.json 进依赖图 |
| 0004 | [`0004-uiMode-darkmode.patch`](0004-uiMode-darkmode.patch) | `13cef9d` | dmcc-darkmode | 壳层配置色变量化(@引用按当前 uiMode 实时取值),darkmode 门禁下与现状逐字节一致 |
| 0005 | [`0005-darkmode-demo-fixture-key-detailNavBg-detailBg-task-.patch`](0005-darkmode-demo-fixture-key-detailNavBg-detailBg-task-.patch) | `f39beac` | dmcc-darkmode | darkmode-demo fixture 第二页面:页面级 @key 覆盖全局,为页面级变量解析备料 |
| 0006 | [`0006-TabBar-theme-JS-wx.onThemeChange-systemInfo.theme-da.patch`](0006-TabBar-theme-JS-wx.onThemeChange-systemInfo.theme-da.patch) | `80cb3da` | dmcc-darkmode | TabBar 五色变量化 + theme→JS 通路(wx.onThemeChange/systemInfo.theme,仅 darkmode 声明包) |
| 0007 | [`0007-darkmode-resolver-8-merge-4-source-pin-7.patch`](0007-darkmode-resolver-8-merge-4-source-pin-7.patch) | `6730ba2` | dmcc-darkmode | darkmode 变量主题契约回归测试(resolver 8 例/merge 4 例/接线 source-pin 7 例) |
| 0008 | [`0008-chore-27cd112-f39beac.patch`](0008-chore-27cd112-f39beac.patch) | `ae63e58` | 杂务 | 清理代提交用的临时补丁文件(内容已入库) |
| 0009 | [`0009-W1-theme-WebView-ForceDark.patch`](0009-W1-theme-WebView-ForceDark.patch) | `1b108b6` | dmcc-darkmode | W1 三修复回炉:theme 推送注入/WebView ForceDark 按声明门禁/五点接线 |
| 0010 | [`0010-mp-capsule-ForceDark-AUTO.patch`](0010-mp-capsule-ForceDark-AUTO.patch) | `d710647` | 菜单主题 | 胶囊微信化+垂直居中同源锚点(mp-capsule)+ForceDark AUTO 语义修正 |
| 0011 | [`0011-fix-insets-ForceDark-AUTO.patch`](0011-fix-insets-ForceDark-AUTO.patch) | `7251ebf` | dmcc-darkmode | 补 insets 扩展导入 + ForceDark 契约测试更新到 AUTO 语义 |
| 0012 | [`0012-fix-dark.patch`](0012-fix-dark.patch) | `5bb040a` | 菜单主题 | 菜单弹层深浅色:dark 取值宿主覆写?:系统深浅,写死浅色全部参数化 |
| 0013 | [`0013-fix-showActionSheet-showModal.patch`](0013-fix-showActionSheet-showModal.patch) | `03e0e45` | 菜单主题 | showActionSheet/showModal 原生弹层深浅色,显式自定义色仍优先 |
| 0014 | [`0014-feat-MiniProgram-iconPng.patch`](0014-feat-MiniProgram-iconPng.patch) | `00bebf4` | 菜单主题 | MiniProgram 新增 iconPng 字段,菜单弹层头部优先渲染宿主图标 |
| 0015 | [`0015-fix-launch-extras-iconPng-intent.patch`](0015-fix-launch-extras-iconPng-intent.patch) | `0f3282b` | 菜单主题 | launch extras 传递 iconPng(字段化 intent 重建链此前丢新字段) |
| 0016 | [`0016-darkmode-demo-fixture-512px-bundle.patch`](0016-darkmode-demo-fixture-512px-bundle.patch) | `97a19a8` | 杂务 | darkmode-demo fixture 补 512px bundle 图标 |
| 0017 | [`0017-feat-api-media-InnerAudioContext-RecorderManager.patch`](0017-feat-api-media-InnerAudioContext-RecorderManager.patch) | `8f15416` | 音频 | 音频原生实现 InnerAudioContext + RecorderManager(Android) |
| 0018 | [`0018-feat-service-InnerAudioContext-RecorderManager.patch`](0018-feat-service-InnerAudioContext-RecorderManager.patch) | `ebcbb50` | 音频 | service 层新增音频接口 InnerAudioContext + RecorderManager |
| 0019 | [`0019-build-jssdk-JSSDK-1.0.75-76-service-InnerAudioContex.patch`](0019-build-jssdk-JSSDK-1.0.75-76-service-InnerAudioContex.patch) | `a6221a0` | 音频 | 重打 JSSDK 1.0.75(76)——service 层纳入音频接口(含二进制产物) |
| 0020 | [`0020-fix-api-media-play-after-stop-prepareAsync-reprepare.patch`](0020-fix-api-media-play-after-stop-prepareAsync-reprepare.patch) | `8a56e2f` | 音频 | play-after-stop 复用实例 prepareAsync 挂死:reprepare 改销毁重建+绝对路径(API35+vivo 实测) |
| 0021 | [`0021-fix-container-redirectTo-Bridge-redirect-bindtap.patch`](0021-fix-container-redirectTo-Bridge-redirect-bindtap.patch) | `54829ef` | redirectTo修复 | redirectTo 换页重建全新 Bridge——redirect 后页面 bindtap 全部失效 |
| 0022 | [`0022-fix-container-service-invoke-redirectTo-wx.-API.patch`](0022-fix-container-service-invoke-redirectTo-wx.-API.patch) | `9fc9649` | redirectTo修复 | service invoke 被静默丢弃——redirectTo 后所有 wx.* API 失效的第二段根因 |
| 0023 | [`0023-test-redirectTo-fe-vitest.patch`](0023-test-redirectTo-fe-vitest.patch) | `bbc30c7` | redirectTo修复 | redirectTo 事件链路回归用例(fe vitest)+ 设备冒烟脚本 |
| 0024 | [`0024-feat-wx.requestSubscribeMessage-API-fe-Android-iOS-g.patch`](0024-feat-wx.requestSubscribeMessage-API-fe-Android-iOS-g.patch) | `191a6fb` | 通知 | wx.requestSubscribeMessage 订阅消息 API 面(fe+Android+iOS)+ getSetting.subscriptionsSetting(通知,详见 ../notify-only/) |
| 0025 | [`0025-wip-tabbar-white-16-black-10-alpha-12sp-3dp-49dp-JVM.patch`](0025-wip-tabbar-white-16-black-10-alpha-12sp-3dp-49dp-JVM.patch) | `5121b17` | TabBar | wip:tabBar 上边框改微信语义发丝线(white 16%/black 10% alpha)+ 标签行盒 12sp/3dp 居中 + JVM 源码钉住测试 |
| 0026 | [`0026-feat-auto-sync-autoSync-autoSync.patch`](0026-feat-auto-sync-autoSync-autoSync.patch) | `7c8a3af` | autoSync | autoSync 静默同步:MiniProgram.autoUpdate 字段(旁路升级确认弹窗)+ RemoteUpdateManager.syncPackage 静默装/升原语 + MiniApp.syncInBackground(里程碑照常广播)+ DiminaActivity 启动前旁路 + Dimina.syncMiniProgram 门面 |

## 与 dimina-notify/ 的关系

[`../notify-only/`](../notify-only/README.md) 是**通知一项的按文件物化精选版**(11 个 per-file 补丁 +
逐文件 diff 摘要 + 宿主对接说明,取自同一提交 191a6fb,即本系列 patch 0024)。两者关系:

- 只关心通知的读者 → 看 `../notify-only/`(有宿主 `registerExtModule("dsh-notify")` 对接说明);
- 要完整复原 fork → 用本系列(含通知)。

## 体积与二进制

- 系列 25 个文件、约 4.4 MB;生成命令 `git format-patch origin/main..HEAD --binary`。
- **`--binary` 必须保留**:系列含真实二进制变更——重打的 JSSDK bundle(patch 0019,音频 service 层
  编译产物)、512px bundle 图标(patch 0016)、fixture 页面资源等;普通 diff 会截断二进制,`git am`
  时将失真。用 `git am`(不是 `git apply`)应用以正确还原二进制。
- 安全扫描:全部 patch `grep` 疑似密钥(publisherKey/secret/password/token 等),唯一命中为示例
  HTML 的 `type="password"` 表单属性(非凭据),**零密钥泄漏**。
| 0027 | [`0027-feat-Header-Bundle-autoSync.patch`](0027-feat-Header-Bundle-autoSync.patch) | `ed16234` | 通知/autoSync 合流 | 容器 Header 通知铃铛(Dimina.setNotifyEntry/setNotifyUnread/setOnNotifyEntryClick,宿主不调用不显示)+ 前端 bundle 升级强制自动:启动前 pending 一律静默激活(「立即升级/暂不升级」弹窗与 declined 记账删除,autoUpdate 旁路全量化)+ 运行中 UPDATE_READY 只广播 onUpdateReady(autoUpdate 包走 autoApplyPendingUpdateFromHost 静默冷重启);autoSync 内容全量保留 |
