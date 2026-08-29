# 元の小麦畑でのfresh gpt-5.6-sol high MCP-only評価 R8（2026-08-29）

- 実験ID: `wheat-original-v1-r8-sol-high`
- artifact: `C:\Users\aod\Documents\Codex\2026-08-26\mine\mcmcp-eval-artifacts\wheat-original-v1-r8-sol-high`
- baseline: `wheat-original-v1-r8-remote`
- repository commit: `97b5c81e45509f5d40b8a48652f95d3980104ccc`
- model: `gpt-5.6-sol`、reasoning effort `high`
- T0: `2026-08-28T22:41:11.7511698Z`
- runner終端: `2026-08-28T22:57:30.9215789Z`
- T0後のoperator介入: なし
- 判定: **未達**。traceと期限終端は正常化したが、3区画の耕作と1区画の播種に留まり、小麦は0個だった

## 目的とprompt

R7後に行った成熟待機、連続回収tick、runner deadline lifecycleの修正を、ユーザー作成済みの元の小麦畑で再評価した。評価モデルへ渡した依頼はproduction想定の次の一文だけである。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

座標、Action例、畑の構造、過去runの文脈は与えていない。公開Toolは次の5個だけである。

1. `agent_get_state`
2. `agent_get_observation`
3. `agent_start_action`
4. `agent_get_action`
5. `agent_cancel_action`

## 実行環境と開始条件

- remote host `aod-mimoid` 上の削除可能なDocker/Prism検証環境
- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25 / Prism Launcher 11.0.3
- 既存profile `MCMCP-Validation`とsave `tester (1)`だけを使用
- 元のoak fence囲いの小麦畑。固定空中arenaは不使用
- Survival、開始inventoryは空、閉じたoak fence gate
- chest `(-10,56,-14)`にnetherite hoe 1個とwheat seeds 64個
- tillable 72区画はdirt、作物なし
- `randomTickSpeed=3000`を22分leaseで一時適用
- remote検証profileだけ全周観測512 rays/tick

fixture apply hashは`66d3382fa788edcb00dd767ab0495d4a16ff6af23b5783ded1171fee1d78e782`、world sessionは`f3d00c77-80d5-4d19-b308-79ec8f74520d`だった。preflightはMCP `ready`、非pause、worldあり、inventory空、512 rays/tick、visible entity 0、Action idleをすべて満たした。

## 非干渉条件

T0前だけoperatorがMinecraft起動、world login、fixture適用、MCP操作ONを行った。T0からrunner terminal record確定までは次を行っていない。

- game画面、log、world状態、途中artifactの閲覧
- computer-use、browser、keyboard、mouse、shellによるgameplay操作
- operatorから評価LLMへの追加入力
- admin bridgeによる途中変更または確認

runner終了後だけartifactとproduction MCPのauthoritative stateを確認した。

## 最終結果

| 項目 | 値 |
|---|---|
| control | `ready`、game unpaused |
| health / hunger / air | 20 / 20 / 300 |
| inventory | netherite hoe 1、wheat seeds 63、wheat 0 |
| 最終Action | `succeeded / COMPLETED` |
| player position | `(-9.545, 56.0, -18.368)`、畑の入口側 |
| visible entity | 0 |
| trace audit | PASS、violation 0 |

モデルはchestを発見し、内容を確認して種64個と鍬を自力で取得した。gateも開け、外側から3区画を耕し、1区画へ播種した。しかしgateを通った安定した経路を確立できず、成熟作物を収穫しなかった。

## Tool・Action集計

dynamic Tool callは151件すべてがbridge lifecycle上validだった。runnerは期限余白で1件を正しく拒否し、transport timeoutや未完了bridge recordを残さずterminalになった。

| Tool | 呼出 | 成功 | domain error |
|---|---:|---:|---:|
| `agent_get_state` | 30 | 30 | 0 |
| `agent_get_observation` | 31 | 29 | 2 |
| `agent_start_action` | 57 | 28 | 29 |
| `agent_get_action` | 33 | 33 | 0 |
| `agent_cancel_action` | 0 | 0 | 0 |
| 合計 | 151 | 120 | 30 |

`agent_start_action`で受理された28件のうち、21件が成功、7件が失敗した。失敗は`PATH_BLOCKED` 6件と`BUDGET_EXCEEDED` 1件だった。受付前の主なdomain errorは次のとおりである。

| error | 件数 |
|---|---:|
| `TARGET_UNKNOWN` | 19 |
| `NO_KNOWN_PATH` | 5 |
| `INVALID_ARGUMENT` | 3 |
| `FRAME_EXPIRED` | 2 |
| `PROGRAM_BUDGET_UNPROVABLE` | 1 |

## 達成したこと

1. productionの短いpromptだけからchestとgateを全周観測で特定した。
2. `inspect_known_container`で内容を確認した。
3. `take_known_container_stack`で種64個と鍬を取得した。
4. gateを破壊せず`open_known_fence_gate` / `open_known_passage`で開いた。
5. 外側から届く3区画を耕し、1区画へ播種した。
6. deadline直前の新規Toolをrunnerが`EVALUATION_DEADLINE_IMMINENT`で拒否し、turn、bridge、auditを欠損なくterminal化した。

R7のrunner transport timeoutと連続回収の内部invariantは再発しなかった。一方、R8では`wait_until(crop_mature)`がadmissionで拒否されたため、新しい認可済み座標のlive maturity確認経路そのものは実ワールド検証できていない。

## 主な失敗原因

### P0: gate mutation後に通過先の局所経路が失われる

navigation nodeは29回あり、成功Actionは11回だった。受付拒否13回、runtime `PATH_BLOCKED` 5回で、成功率は37.9%である。特にgateを開いて同じprogramで通過する3試行はすべて失敗した。

最初の`open gate -> navigate inside`はgate操作自体には成功したが、そのmutationでgate近傍のKnown Traversability Mapが正しく失効した後、通過先が半径4 blockのLocal Observation Volume外となった。JIT replanは`jit_target_unknown`で停止した。安全な失効は正しいが、開いた通路を再観測して横断するための局所範囲・段階的経路が不足している。

また、モデルはvisible dirt/farmlandのsupport座標`y=55`をplayer feet-spaceのnavigation座標と混同した。`navigate_to_known.target`はtraversability recordの`from` / `to`を使う必要があるが、Tool contract上の説明が十分目立たず、推測したblock座標を何度も渡した。

### P0: 観測出力が冗長でactionable evidenceが埋もれる

成功した29 observation pageは合計3,681 records、約1.33M文字だった。1 blockが複数faceの`visible_surface`として重複し、複数kindを同時要求した先頭pageが256件すべてsurfaceになる例もあった。

唯一の作物は播種から約8秒後、さらに約15分後と16分後にも`crop_mature=true`として観測されていた。しかし92/209/222 records中に埋もれ、モデルはその座標を収穫batchへ選択しなかった。代わりに`wait_ticks` 200を16回反復し、成熟後に約165秒を消費した。

### P0: batchがgeneric evidence failureで全拒否された

- `till_known_batch`: 2回、合計5 target、受理0
- `plant_known_wheat_batch`: 1回、2 target、受理0
- `harvest_known_wheat_batch`: 使用0

batchは全targetのfresh surface/reach証拠を要求するが、generic `TARGET_UNKNOWN`だけでは不足targetを特定できない。モデルは3 targetから2 target、singleへ手探りで縮小した。LLMが実際に受け取ったvisible evidenceを一定時間安全に参照できず、Action admissionが常に別の最新frameだけを見ることも失敗を増幅した。

### P1: frame handle寿命がLLM latencyより短い

ObservationFrameStoreの未pin frameは最新2件だけである。512 rays/tickでは1 frameがおよそ4 tickごとに完成するため、保持時間は約0.4秒にすぎない。最初のstateからobservationまで2.4～3.5秒かかり、2回`FRAME_EXPIRED`になった。モデルは後に即時呼出へ適応したため直接の最大要因ではないが、人間より遅いLLMを前提にしたAPI寿命ではない。

## R9前に統合した修正

1. `agent_get_state`で告知したframe handleをidle 60秒、最大16件のLRU上限で保持し、frame生成頻度とLLM latencyを分離した。
2. 実際に`agent_get_observation`で返した静的surfaceだけを最大2,048件、返却から最大60秒保持する。session、dimension、visual / target revision、pose、reach、commit/JIT、ray fenceは従来どおり毎回再検証し、entity、item、hazard等の動的recordは延長しない。
3. 公開surfaceをblock position単位の代表面へ集約し、`crop_mature=true`、`crop_mature=false`、その他の順、各群内は近距離順とした。複数kindはround-robinで公平に並べ、summary countも代表面圧縮後の件数へ合わせた。
4. Local Observation Volumeを半径6 block、最大128 transitionへ拡張し、hidden visual情報を増やさずderived collision / support / hazardだけからgate先のfeet-spaceを再構築できる範囲を広げた。
5. catalog先頭へ「navigation targetはtraversabilityのfeet-spaceをcopy」「visible support block座標をnavigationに使わない」「関連手順を1 programへ接続」「plantと代表waitを同一Actionへ置く」「2〜8対象はbatchを優先」を短く明記した。
6. mutation batchのadmission `TARGET_UNKNOWN`へ最初に不足した提出配列の`target[index]`を含め、座標やhidden stateを追加開示せず再観測対象を絞れるようにした。

これらはR8の事後修正であり、実ワールド効果は次のR9で同じsave、fixture、prompt、5 Tool、非干渉条件を維持して検証する。

R9では同じsave、fixture、prompt、5 Tool、非干渉条件を維持し、まずfresh `gpt-5.6-sol high`で小麦64個と全Action terminalを確認する。
