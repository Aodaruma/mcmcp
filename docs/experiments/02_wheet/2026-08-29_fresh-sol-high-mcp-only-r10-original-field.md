# 元の小麦畑でのfresh gpt-5.6-sol high MCP-only評価 R10（2026-08-29）

- 実験ID: `wheat-original-v1-r10-sol-high`
- artifact: `C:\Users\aod\Documents\Codex\2026-08-26\mine\mcmcp-eval-artifacts\wheat-original-v1-r10-sol-high`
- baseline: `wheat-original-v1-r10-remote`
- repository commit: `f208c20495ffa38b980bf36a79003918f0eec504`
- model: `gpt-5.6-sol`、reasoning effort `high`
- T0: `2026-08-29T00:51:50.3980103Z`
- runner終端: `2026-08-29T01:08:20.1557180Z`
- T0後のoperator介入: なし
- 判定: **未達（39 / 64）**。ただしchest取得、gate通過、耕作、植付け、成熟、収穫、drop回収まで初めてfresh end-to-endで自律実行した

## 条件

R9で判明したcontainer画面終了処理を修正したJARを使い、同じ削除可能なremote Docker/Prism環境、profile `MCMCP-Validation`、save `tester (1)`、元のoak fence囲いの畑を使用した。固定空中arenaは使用していない。fixture `wheat-original-v1`のapply hashは`66d3382fa788edcb00dd767ab0495d4a16ff6af23b5783ded1171fee1d78e782`、world sessionは`4ff4ad7a-4400-459b-91d1-cb7f092b9ce5`だった。

promptは次の一文だけで、座標、Action例、過去runの文脈は与えていない。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

公開Toolは`agent_get_state`、`agent_get_observation`、`agent_start_action`、`agent_get_action`、`agent_cancel_action`の5個だけである。T0前だけMinecraft起動、world login、fixture適用、MCP操作ONを行った。T0からrunner terminal record確定までは画面観測、computer-use、keyboard/mouse、shellによるgameplay補助、追加prompt、admin bridge変更を行っていない。

## 結果

| 項目 | 値 |
|---|---|
| chest | inspect成功。netherite hoe 1、wheat seeds 64を確認 |
| container取得 | seeds、hoeとも成功。`INVENTORY_SCREEN_NOT_CLEAR`再発なし |
| gate / 移動 | gate openと畑内移動に成功 |
| 耕作 | 14区画 |
| 収穫 | 41 block break |
| 最終inventory | netherite hoe 1、wheat 39、wheat seeds 115（terminal後のread-only state） |
| health / hunger / air | 20 / 20 / 300 |
| trace audit | PASS、violation 0 |
| accepted Action | 38件。成功36、失敗2、未終了0、cancel 0 |

runnerの最終メッセージ時点はwheat 39、seeds 109だった。その後の最終`collect_visible_item`もserver-confirmed inventory増加で成功し、terminal後のread-only stateはwheat 39、seeds 115を示した。目標との差は25個である。

## 非干渉・終端監査

trace message 710件、bridge record 506件、dynamic request 165件はすべて正規lifecycleで、audit違反0だった。deadline直前に`agent_get_state` 2件をrunnerがMinecraftへforwardせず拒否したためmanual review対象となったが、受理済みAction 38件は全件terminalだった。最後のActionもdeadline拒否前に`succeeded`であり、cleanup cancel forward 0、active Action 0である。git worktreeはexact commitでclean、secret保存0、元の認証状態も不変だった。

## Tool・実行集計

| Tool | 呼出 | 成功 | domain / deadline error |
|---|---:|---:|---:|
| `agent_get_state` | 39 | 37 | deadline拒否2 |
| `agent_get_observation` | 38 | 37 | `SERVER_BUSY` 1 |
| `agent_start_action` | 50 | 38 accepted | 受付拒否12 |
| `agent_get_action` | 38 | 38 | 0 |

受理ActionのMinecraft実行は合計1,101 ticks（約55秒）だった。一方wall timeは約990秒であり、主因はLLMとMCPの細粒度な往復だった。

- observation成功37回で約1.98MB、5,232 record。その94%超がTool result量で、`visible_surface`は4,526件だった
- 全38 observationがroot pageで、`next_cursor`の継続0。放棄したpaged query 2件がleaseを占有し`SERVER_BUSY`を1回発生させた
- mutation batchは15 Action / 96 targetsで利用できており、crop batchの発見性は機能した
- drop回収は単品`collect_visible_item`を14 Actionへ分割した
- `wait_until`は0回。`randomTickSpeed=3000`により推論中に成熟し、待機はボトルネックではなかった

## 残存不具合とR11向け修正

1. **観測出力過多**: delivery-only `filter`を追加し、作物はblock ID / maturity、dropはentity type / displayed itemで絞る。全周内部frameと安全認可は変えない。
2. **移動座標契約の矛盾**: 旧catalogは小数`traversability.from / to`のコピーを要求しつつ、整数`navigate_to_known.target`を要求していた。R10では実際に`expected integer` 1回、その後の推測丸めでroute拒否が発生した。整数feet-space `navigation_target`を観測へ追加し、無変換コピーだけを許可する。
3. **drop回収の往復**: `collect_visible_item_batch`を1〜8件の有限DSL macroとして追加する。各entryは既存のfresh witness、known-safe route、JIT再検証、inventory絶対差分を維持し、失敗時は未開始suffixを停止する。
4. **camera予算の微小不足**: combined container takeと8-target tillが、入力前の幾何camera costとVanilla float回転の差によりaccepted後`BUDGET_EXCEEDED`となった。camera primitiveごとに0.25度の決定論的量子化reserveを事前計上する。
5. **Action境界**: 現在証拠または明示dependency proofで後続nodeまで受付可能な範囲だけを結合し、dropや露出surfaceを新たに作るmutation後は再観測する契約へ短縮・明確化する。

R11は上記を実装したcommitで同じprofile、save、fixture、prompt、5 Tool、fresh `gpt-5.6-sol high`、T0後非干渉を維持して再実行する。
