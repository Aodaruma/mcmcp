# 元の小麦畑でのfresh gpt-5.6-sol high MCP-only評価 R9（2026-08-29）

- 実験ID: `wheat-original-v1-r9-sol-high`
- artifact: `C:\Users\aod\Documents\Codex\2026-08-26\mine\mcmcp-eval-artifacts\wheat-original-v1-r9-sol-high`
- baseline: `wheat-original-v1-r9-remote`
- repository commit: `ce0db2ff682700b5a749b56ec1c76e952d557fa6`
- model: `gpt-5.6-sol`、reasoning effort `high`
- T0: `2026-08-29T00:25:41.6599221Z`
- runner終端: `2026-08-29T00:30:44.3768187Z`
- T0後のoperator介入: なし
- 判定: **未達**。chest内容の確認には成功したが、container画面の終了処理に不整合があり、種と鍬を取得できなかった

## 目的と開始条件

R8後の観測evidence保持、surface圧縮、公平paging、半径6のLocal Observation Volume、batch診断を、同じ元の小麦畑で再評価した。production想定promptは次の一文だけである。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

座標、Action例、過去runの文脈は与えていない。公開Toolは`agent_get_state`、`agent_get_observation`、`agent_start_action`、`agent_get_action`、`agent_cancel_action`の5個だけである。

- remote host `aod-mimoid`上の削除可能なDocker/Prism検証環境
- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25
- 既存profile `MCMCP-Validation`とsave `tester (1)`だけを使用
- 元のoak fence囲いの小麦畑。固定空中arenaは不使用
- Survival、開始inventoryは空、閉じたoak fence gate
- chest `(-10,56,-14)`にnetherite hoe 1個とwheat seeds 64個
- tillable 72区画はdirt、作物なし
- `randomTickSpeed=3000`を22分leaseで一時適用
- remote検証profileだけ全周観測512 rays/tick

fixture apply hashは`66d3382fa788edcb00dd767ab0495d4a16ff6af23b5783ded1171fee1d78e782`、world sessionは`d5769425-41f7-4e12-bf83-c8c001017a26`だった。preflightはMCP ready、非pause、world/observationあり、inventory空、512 rays/tick、visible entity 0、Action idleを満たした。

## 非干渉と監査

T0前だけoperatorがMinecraft起動、world login、fixture適用、MCP操作ONを行った。T0からrunner terminal record確定までは画面観測、computer-use、keyboard/mouse、shellによるgameplay補助、追加prompt、admin bridge変更を行っていない。

trace auditはPASSだった。trace message 264件、bridge record 184件、dynamic Tool call 57件はすべて正規lifecycleで、違反0、secret保存0、git worktree cleanだった。したがって本runは非干渉評価として有効だが、gameplay goalは失敗である。

## 最終結果

| 項目 | 値 |
|---|---|
| control | `ready`、game unpaused |
| health / hunger / air | 20 / 20 / 300 |
| inventory | 空。wheat 0、wheat seeds 0、hoe 0 |
| 最終Action | `failed / SERVER_DENIED_OR_DESYNC` |
| player position | `(-9.589, 56.0, -14.568)`、chest付近 |
| trace audit | PASS、violation 0 |

runnerの最終メッセージはEscによる画面closeをユーザーへ求めた。しかし完成条件はT0後のoperator介入なしなので、この要求は受け入れず実装不具合として扱った。

## Tool・Action集計

| Tool | 呼出 | transport成功 | domain error |
|---|---:|---:|---:|
| `agent_get_state` | 10 | 10 | 0 |
| `agent_get_observation` | 11 | 11 | 0 |
| `agent_start_action` | 20 | 16 | 4 |
| `agent_get_action` | 16 | 16 | 0 |
| `agent_cancel_action` | 0 | 0 | 0 |
| 合計 | 57 | 53 | 4 |

受理されたAction 16件は成功6、失敗10だった。失敗evidenceは`inventory_screen_not_clear` 7件、`mutation_precondition_changed` 2件、`primitive_budget` 1件である。受付前domain errorは`TARGET_UNKNOWN` 2件、`INVALID_ARGUMENT` 1件、`NO_KNOWN_PATH` 1件だった。

## 根本原因

1. `inspect_known_container`はserver由来full-contentを得て正常成功し、chest内のnetherite hoe 1個とwheat seeds 64個を確認した。
2. 成功時の`KnownContainerAttempt.close()`はPhase 5 portのreleaseとretireを呼んだ。
3. 旧`MinecraftPhaseFiveInventoryPort.cleanupOwnedScreen()`は`LocalPlayer.closeContainer()`だけを呼び、clientの`AbstractContainerScreen`をcanonicalな画面終了経路で閉じなかった。
4. screen ownershipは`CLOSING`、client GUIはcontainer表示のまま残り、次の`take_known_container_stack`が`INVENTORY_SCREEN_NOT_CLEAR`でfail-closedになった。
5. さらに同一attemptのrelease後retireが二度目のcancelを行うと、旧`ScreenOwnershipSignals.Core.cancelRoutine()`は実画面のclose acknowledgementなしで`IDLE`へ戻り得た。screenとownership ledgerの不一致を隠す別のlifecycle欠陥だった。

移動や待機を挟んでもcontainer GUIは閉じないため、モデルによる7回の再試行はすべて正しく失敗した。数値調整やprompt追加では解消しない実装問題である。

## R10前の修正

1. exact ownership decisionのcontainer IDとmenu typeをcurrent `AbstractContainerScreen`へ再照合し、canonical `onClose()`でmenuとclient screenを一体として閉じる。
2. `closeForReadback`とterminal cleanupの両方へ同じclose経路を適用する。
3. `CLOSING`中の同一authorityによる二重cancelはidempotentなpending応答にし、exact `ScreenEvent.Closing`だけが`IDLE`へ戻せるようにする。
4. bytecode contract testとCore lifecycle testで、bare `LocalPlayer.closeContainer()`への退行と、close未確認の`CLOSING -> IDLE`を禁止する。

R10は修正版JARでfixtureを再適用し、同じsave、元の畑、prompt、5 Tool、fresh `gpt-5.6-sol high`、T0後非干渉を維持して再実行する。
