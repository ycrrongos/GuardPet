# 归档：视频内容判断

> 状态：**已恢复到一刀切之前**（2026-09-12）  
> 现行：`VIDEO → SEARCH_ONLY`，源码在 `app/.../VideoGuard.kt` + `HabitGuardian.evaluateVideo`。  
> 本目录保留对照片段。

若再次改为整包禁：移回 `VideoGuard`，`VIDEO.toMode()` 改 `BLOCK`，工作时段对 VIDEO 走整包禁分支。
