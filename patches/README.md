# dimina 补丁存档总览

> 2026-11-11 起为本仓(dimina fork)自带档案:原存万器仓 `patches/dimina/`,四仓减负迁入;内容与用法不变。
> 同日新建公开 fork `theVincentWei/dimina`,main 即本地线(基线 v1.8.1)。
> 本目录为**静态档案**,不随提交追加——dimina 跟上游 rebase 升级时现场 format-patch 再生做树 SHA 对账(安全门),对账过才收口。

两个子目录,两种取用方式,内容同源(均出自本 fork `main` 对上游 didi/dimina 的本地增量):

| 子目录 | 是什么 | 给谁用 |
|---|---|---|
| [`series/`](series/README.md) | **全量归档**:本地全部适配提交的编号补丁系列(基线 didi/dimina @eef595c,树 SHA 对账无损),`git am` 一次性重放到最新 | 想完整adopting本 fork 全部适配的读者;也是本 fork 的内容备份 |
| [`notify-only/`](notify-only/README.md) | **单特性精选**:订阅消息(`wx.requestSubscribeMessage`)一个特性按文件物化(源自全量系列中的该提交) | 只想给自家 dimina 增加订阅消息能力、不想整体adopting的读者 |

## 关系说明

- `notify-only/` 的内容 = 全量系列中通知提交的按文件拆分,**可从 series 再生**,保留它是为了单特性采纳的便利。
- 宿主端(dsh-go)对接说明见 `notify-only/README.md` 内的 `dsh-notify` 扩展模块一节;契约见万器仓 `docs/notify-contract.md`(~/Code/wanqi)。
- dsh-Go(手机宿主)的适配目录**在 dsh-Go 仓** `local-series/`(~/Code/dsh-Go;目录+分类,无补丁体,理由见其 README)。
