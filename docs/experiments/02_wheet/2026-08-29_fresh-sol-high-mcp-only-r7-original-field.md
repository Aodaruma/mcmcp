# 元の小麦畑でのfresh gpt-5.6-sol high MCP-only評価 R7（2026-08-29）

- 実験ID: `wheat-original-v1-r7-sol-high`
- artifact: `C:\Users\aod\Documents\Codex\2026-08-26\mine\mcmcp-eval-artifacts\wheat-original-v1-r7-sol-high`
- baseline: `wheat-original-v1-r7-remote`
- repository commit: `a9e04ffa6a23259f07db7534d4f2c0a5852d7181`
- model: `gpt-5.6-sol`、reasoning effort `high`
- 評価時間: 約1,020秒
- T0後のoperator介入: なし
- 判定: **未達**。開始条件と耕作・播種・収穫を自律処理して小麦33個を得たが、64個到達前に成熟待機が停滞し、期限直前のTool待機でrunnerがtransport timeoutになった

## 目的とprompt

ユーザーが砂岩flat world内に用意した元の小麦畑で、production想定の短い依頼だけからMCP-onlyで作業を完遂できるか評価した。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

座標、経路、Action例、追加説明は与えていない。評価LLMへ公開したのは次の5 Toolだけである。

1. `agent_get_state`
2. `agent_get_observation`
3. `agent_start_action`
4. `agent_get_action`
5. `agent_cancel_action`

## 実行環境と開始条件

- remote host `aod-mimoid` 上の削除可能なDocker/Prism検証環境
- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25 / Prism Launcher 11.0.3
- save `tester (1)` のユーザー作成済み砂岩flat world
- 元のoak fence囲いの小麦畑。空中arenaは使用していない
- Survival、開始inventoryは空
- 閉じたoak fence gate
- 入口側chestにnetherite hoe 1個とwheat seeds 64個
- 元畑の既存水路・trapdoor・柵・周辺施設は維持
- `randomTickSpeed=3000`を22分leaseで一時適用
- remote検証profileだけ `omnidirectional_rays_per_tick=512`

外部fixture admin bridgeはT0前にcold-startを作った。fixtureは対象畑の土・作物・chest・gate・player開始状態だけを既知範囲内で初期化し、耕作、播種、移動、収穫、item回収を代行していない。

preflightではMCP `ready`、game unpaused、world present、inventory empty、512 rays/tick、visible entity 0、Action idleを確認した。fixture apply hashは`66d3382fa788edcb00dd767ab0495d4a16ff6af23b5783ded1171fee1d78e782`、world sessionは`c7891bc0-1393-4bd1-9988-36fa7a7e8f92`である。

## 非干渉条件

T0前だけoperatorがMinecraft起動、world login、fixture適用、MCP操作ONを行った。T0（`2026-08-28T20:44:56.0155575Z`）からrunner終端（`2026-08-28T21:01:55.7749528Z`）までは次を行っていない。

- game画面、log、world状態、途中artifactの閲覧
- computer-use、browser、keyboard、mouse、shellによるgameplay操作
- operatorから評価LLMへの追加入力
- admin bridgeによる途中変更または確認

runner終了後だけartifactとMCP stateを確認し、残っていたActionをMCPの`agent_cancel_action`でcancelした。このcancelは評価結果に含めない。

## 結果

最終のauthoritative inventoryは次のとおりだった。

| 項目 | 値 |
|---|---|
| control | `ready`、game unpaused |
| health / hunger / air | 20 / 20 / 300 |
| inventory | netherite hoe 1、wheat 33、wheat seeds 107 |
| 最終Action（runner終端時） | running、`wait_mature` |
| rays/tick | 512 |

受理された49 Actionのうち38件がsucceeded、10件がfailed、1件がrunner終端時にrunningだった。terminal failureは`PATH_BLOCKED` 5件、`BUDGET_EXCEEDED` 4件、`EMERGENCY_STOP` 1件である。

batch primitiveは15 Actionで使われ、11件成功、4件失敗だった。trace上のmutation targetは合計72/91件が完了した。

| primitive | 完了/計画target |
|---|---:|
| `till_known_batch` | 11/11 |
| `plant_known_wheat_batch` | 38/44 |
| `harvest_known_wheat_batch` | 23/36 |

受理Actionの最終snapshotを集約すると、interaction 19、block break 36、block place 39、移動17.462 blocks、camera 4,361.439°だった。

## 達成した開始要件

LLMは補助なしで次を実行した。

1. 全周観測から入口側chestを特定した。
2. `inspect_known_container`でnetherite hoeとwheat seedsを確認した。
3. `take_known_container_stack`で両方を取得した。
4. `open_known_fence_gate`で閉じたgateを開けた。
5. `navigate_to_known`をつないでgateを通過し、元の畑へ移動した。
6. 8 targetまでのbatch DSLを自作し、耕作、播種、成熟待機、収穫、item回収を反復した。

したがって、今回の未達原因はoperatorが開始条件を緩めたことではない。

## Tool・Action監査

完了したdynamic Tool callは170件だった。171件目はrunner期限直前に始まり、bridge lifecycleが未完了となった。

| Tool | 完了呼出 | Tool成功 | domain error |
|---|---:|---:|---:|
| `agent_get_state` | 30 | 30 | 0 |
| `agent_get_observation` | 27 | 26 | 1 |
| `agent_start_action` | 60 | 49 | 11 |
| `agent_get_action` | 53 | 53 | 0 |
| `agent_cancel_action` | 0 | 0 | 0 |
| 合計 | 170 | 158 | 12 |

runnerは残り約3秒で`agent_get_action(wait_timeout_ms=25000)`を受け、HTTP timeoutを残り時間まで縮めて転送したため、MCP応答前に`request_timeout`になった。その結果、`turn/completed`と最後のbridge responseが欠け、trace auditもFAILになった。これはgameplayの失敗とは別の評価基盤上の問題である。

## 主な失敗原因

### P0: `wait_until(crop_mature)`のfalse negative

最後のActionは`(-14, 56, -15)`の成熟待機後に2 targetを収穫するprogramだった。直前観測では同座標がすでに`cropMature=true`だったが、Actionは数千tick待機し続けた。

現在の実装は、待機開始時のbarrier revision以降に作られた「最新の観測frame」で、対象のwheat surfaceが再度rayに当たり、かつ成熟と記録されることを要求する。小さいcrop surfaceが更新後のrayに再命中しないと、world上では成熟済みでもfalseのままになる。

修正方針は、開始時にpolicy-visibleなwheat targetであることを認可し、その明示的な既知座標だけを実行中にlive block stateで確認することとする。成熟なら即完了、未成熟なら待機、wheatでなくなった・未load・session変更ならfail-safeに終了する。任意領域走査や未知blockの公開は行わない。

### P0: 連続回収時の誤った`EMERGENCY_STOP`

`collect_wheat_cycle_2`では1件目のpickup成功直後に2件目を開始し、`goal_preempted_for_safety`、`EMERGENCY_STOP/internal_invariant`となった。

recovery governorへ渡すtickをAction progressから合成しており、primitiveが同一game tick内に完了するとstrictly increasing条件を破る場合がある。world/sessionの実際のclient tickを渡し、Action progressと安全時計を分離する必要がある。

### P1: runnerのdeadline admission

期限内に完了できない長時間Tool callをMCPへ転送しない。残り時間と`wait_timeout_ms`から必要headroomを計算し、不足時はsecret非公開の決定的なdeadline errorをmodelへ返し、全bridge lifecycleを監査可能な形で閉じる必要がある。

## 判定

R7は、元のユーザー作成畑に対するfresh `gpt-5.6-sol high` MCP-only受入試験 **FAIL** である。小麦33個まで自律取得できたが64個には届かず、runner終端もtransport timeoutで監査不成立だった。

一方、R6で時間を要した反復操作はbatch DSLにより大幅に進み、開始条件から収穫サイクルまでを短いproduction promptだけで実行できた。次回R8では上記3点を修正し、同じworld、fixture、prompt、非干渉条件で64個到達を再評価する。
