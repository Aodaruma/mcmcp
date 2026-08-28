# 元の小麦畑でのfresh gpt-5.6-sol high MCP-only評価 R6（2026-08-29）

- 実験ID: `wheat-original-v1-r6c-sol-high`
- artifact: `C:\Users\aod\Documents\Codex\2026-08-26\mine\mcmcp-eval-artifacts\wheat-original-v1-r6c-sol-high`
- baseline: `wheat-original-v1-r6c-remote`
- repository commit: `101c6392aafc191e391b2344a33a9222a696d608`
- model: `gpt-5.6-sol`、reasoning effort `high`
- 評価時間: 1,020.028秒
- T0後のoperator介入: なし
- 判定: **未達**。チェスト取得、gate通過、畑への移動、63マス耕作、9マス播種までは成功したが、小麦64個の収穫前に評価期限へ到達した

## 目的とprompt

ユーザーが砂岩flat world内に用意した元の小麦畑で、production想定の短い依頼だけからMCP-onlyで作業を完遂できるか評価した。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

座標、経路、Action例、補足説明は与えていない。評価LLMへ公開したのは次の5 Toolだけである。

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

`r6`と`r6b`はpreflightで512 rays/tick条件を満たさず、T0、thread、gameplay callを一度も作らず終了した環境準備失敗である。profile再起動後の`r6c`だけをモデル試行として数える。

## 非干渉条件

T0前だけoperatorがMinecraft起動、world login、fixture適用、MCP操作ONを行った。T0からrunner終端記録までは次を行っていない。

- game画面、log、world状態、途中artifactの閲覧
- computer-use、browser、keyboard、mouse、shellによるgameplay操作
- operatorから評価LLMへの追加入力
- admin bridgeによる途中変更または確認

runner終了後だけartifactとMCP read-only stateを確認した。admin leaseはその時点ですでに期限復元済みだった。

## 結果

runnerは `evaluation deadline expired before turn/completed` で終了した。T0から終了まで設定値どおり約1,020秒で、Codex turnの最終応答と`turn/completed`は存在しない。deadline直前約2秒までTool callが続いており、モデル停止やMCP transport停止ではない。

最終のauthoritative `agent_get_state`は次のとおりだった。

| 項目 | 値 |
|---|---|
| control | `ready`、game unpaused |
| player座標 | `(-16.611, 55.938, -18.313)` |
| health / hunger / air | 20 / 20 / 300 |
| inventory | netherite hoe 1、wheat seeds 55 |
| wheat | 0 |
| 最終Action | succeeded、`COMPLETED` |
| rays/tick | 512 |

投入programとterminal Action traceの`NODE_COMPLETED`を照合すると、異なる63座標で`till_known_block`、異なる9座標で`plant_known_wheat`が完了していた。最終観測でも同じ9座標の成熟小麦を確認した。残りの種が55個であることとも一致する。

モデルは途中で「64区画の耕作を確実に完了」と発言したが、traceは63座標であり、最終観測では未耕作のdirt `(-19, 55, -20)`が残った。したがって進捗発言ではなくAction traceとstateを判定根拠にする。

## 達成した開始要件

今回の重要な改善は、従来省略していた開始条件をLLM自身が処理できたことである。

1. 全周観測から入口側chestを特定した。
2. `inspect_known_container`でnetherite hoe 1、wheat seeds 64を確認した。
3. `take_known_container_stack`で両方を取得した。
4. `open_known_fence_gate`で閉じたgateを開けた。
5. `navigate_to_known`をつないでgateを通過し、元の畑へ移動した。

よって「開始条件をoperatorが緩めたため成功した」という問題は、このrunでは解消した。computer-useやshellによるチェスト取得・gate通過は行っていない。

## Tool・Action監査

| Tool | 呼出 | Tool成功 | domain error |
|---|---:|---:|---:|
| `agent_get_state` | 31 | 31 | 0 |
| `agent_get_observation` | 39 | 37 | 2 |
| `agent_start_action` | 80 | 45 | 35 |
| `agent_get_action` | 45 | 45 | 0 |
| `agent_cancel_action` | 0 | 0 | 0 |
| 合計 | 195 | 158 | 37 |

- 195 requestは全て許可Toolでschema-valid
- transport、HTTP、bridge secret、stderrの異常は0
- `agent_get_observation`の2失敗はrecoverableな`FRAME_EXPIRED`
- `agent_start_action`の事前拒否35件は`TARGET_UNKNOWN` 26、`NO_KNOWN_PATH` 6、`INVALID_ARGUMENT` 2、`PROGRAM_BUDGET_UNPROVABLE` 1
- 受理45 Actionはすべてterminalまで回収済み
- 受理Actionの実行結果は34 succeeded、11 failed
- terminal failureは`PATH_BLOCKED` 8、`BUDGET_EXCEEDED` 3

Tool処理時間の合計は約29.6秒、単発最大約2.53秒だった。1,020秒の大半はMCP transport待ちではなく、モデル推論、細粒度の観測、失効したtargetの再取得、program再構成に使われた。

## programmed DSLの実際

LLMは一個ずつraw inputを送ったのではなく、`agent_start_action.program.body`へ最大8個程度のprimitiveを接続したDSLを自作していた。実際に、chest確認、2 stack取得、gate開扉、移動、8マス単位の耕作・播種batchを構成した。

したがって「複数actionをつなぐDSLがない」ことが今回の原因ではない。問題は、各targetが直前の可視surfaceであること、全nodeの到達可能性、camera・motion budgetをbatch開始時と実行直前に満たす必要があり、地形上の移動や視点変化で証拠が失効しやすい点にある。

## 改善点

### P0: 実行層の効率

1. **既知target listをAction内で再取得可能にする。** 現状は8マスbatchでも途中の視点・位置変更後に後半targetが`PATH_BLOCKED`となり、LLMがstate/observation/programを組み直す。各node直前の局所観測更新と、同一座標・同一block predicateのbounded再証明を実行層で行う。
2. **解析的interaction poseをlist全体で計画する。** targetごとの単発aimではなく、畑外周や列端の安全な立ち位置、reach、遮蔽、耕地の1/16高さ差、水路を考慮して順序と立ち位置を決める。
3. **budget不足を開始前に具体化する。** `camera`、`motion`、`wait`のどの上限が何だけ不足するかを返し、LLMの試行錯誤を減らす。安全上許される範囲で、tool schema上限を使う推奨budgetをcatalogへ明示する。
4. **既知patchの構造化観測を返す。** 生のsurface recordをLLMが毎回座標集計する代わりに、観測済み同一平面の連結patch、support、crop stateをtoken効率のよい形で返す。ただし遮蔽されたblockや任意region走査を新たに公開しない。

### P1: 評価基盤

5. deadline時に最終state、observation、active Action、残時間、interrupt/kill理由をrunner artifactへ自動保存する。
6. `turn/completed`欠落とturn ID不一致を別の監査項目にする。今回の既存ID同士は一致しており、後者は複合判定の誤認表示の疑いがある。
7. modelの発言ではなく、最終inventory `wheat>=64`、terminal Action、最新観測を自動oracleにする。

## 判定

R6は、元のユーザー作成畑に対するfresh `gpt-5.6-sol high` MCP-only受入試験 **FAIL** である。小麦64個は得られず、播種9/64の途中で期限終了した。

一方、従来の重大な開始条件であった「チェスト確認・道具取得・閉じたgateの開扉と通過・元畑までの移動」は、追加contextやoperator介入なしで合格した。次の修正対象は開始処理ではなく、同種blockを多数扱う際のAction内再証明、interaction pose列計画、観測とbudgetの往復削減である。
