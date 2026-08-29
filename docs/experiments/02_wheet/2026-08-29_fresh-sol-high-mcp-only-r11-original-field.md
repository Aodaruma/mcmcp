# 元の小麦畑でのfresh gpt-5.6-sol high MCP-only評価 R11（2026-08-29）

- 実験ID: `wheat-original-v1-r11-sol-high`
- artifact: `C:\Users\aod\Documents\Codex\2026-08-26\mine\mcmcp-eval-artifacts\wheat-original-v1-r11-sol-high`
- baseline: `wheat-original-v1-r11-remote`
- repository commit: `86504bd6baa14c67aaee24f67dcf18ddbb59bdd6`
- model: `gpt-5.6-sol`、reasoning effort `high`
- T0: `2026-08-29T01:53:07.0944785Z`
- runner終端: `2026-08-29T02:09:35.8043566Z`
- T0後のoperator介入: なし
- 判定: **未達（26 / 64）**。trace監査は合格し、chest取得から収穫・回収・再播種まではMCP-onlyで到達した

## 条件

R10後に追加したdelivery-only observation filter、整数`navigation_target`、`collect_visible_item_batch`、camera量子化reserveを含むJARを使用した。環境は同じremote Docker/Prism、profile `MCMCP-Validation`、save `tester (1)`、元のoak fence囲いの畑である。固定空中arenaは使用していない。fixtureは`wheat-original-v1`、apply hashは`66d3382fa788edcb00dd767ab0495d4a16ff6af23b5783ded1171fee1d78e782`、`randomTickSpeed=3000`とした。

promptは次の一文だけで、座標、Action例、過去runの文脈は与えていない。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

公開Toolは`agent_get_state`、`agent_get_observation`、`agent_start_action`、`agent_get_action`、`agent_cancel_action`の5個だけである。T0前だけMinecraft起動、world login、fixture適用、MCP操作ONを行った。T0からrunner terminal record確定までは画面観測、computer-use、keyboard/mouse、shellによるgameplay補助、追加prompt、admin bridge変更を行っていない。

## 結果

| 項目 | 値 |
|---|---|
| chest / container取得 | inspect、netherite hoe 1、wheat seeds 64の取得に成功 |
| gate / 移動 | gate openと畑内移動に成功 |
| block mutation | break 33、place系48。耕作、播種、収穫、再播種を実行 |
| 最終inventory | netherite hoe 1、wheat 26、wheat seeds 74 |
| health / hunger / air | 20 / 20 / 300 |
| trace audit | PASS、violation 0 |
| accepted Action | 39件。成功34、fail-safe失敗5、未終了0、cancel 0 |

runnerは時間制限で終了した。畑には植え直した小麦と成熟した小麦が残っており、最終観測でも成熟株9件を確認した。目標との差は38個である。

## 非干渉・終端監査

trace message 949件、bridge record 684件、dynamic request 224件はすべて監査対象となり、audit違反0だった。deadline直前の`agent_start_action` 1件はMinecraftへforwardせず拒否された。受理済みAction 39件は全件terminalで、最後のActionも`succeeded`、cleanup cancel forward 0、active Action 0である。git worktreeはexact commitでclean、secret保存0、元の認証状態も不変だった。

## Tool・実行集計

| Tool | 呼出 | 成功 | domain / deadline error |
|---|---:|---:|---:|
| `agent_get_state` | 54 | 54 | 0 |
| `agent_get_observation` | 58 | 58 | 0 |
| `agent_start_action` | 73 | 39 accepted | 受付拒否33、deadline拒否1 |
| `agent_get_action` | 39 | 39 | 0 |

受理ActionのMinecraft実行は合計1,284 ticks（約64秒）だった。一方wall timeは約989秒であり、大半はLLMのAction組立て、受付エラーからの修正、観測とActionの逐次往復に費やされた。最初の収穫Action受理はT0から約576秒後で、残り約7分しか生産工程に使えなかった。

observation 58回中50回が新しいfilterを使用しており、必要なsurface / entityへ絞る契約は利用された。一方、dynamic callはR10の165件から224件へ増え、最終wheatも39から26へ減った。R11は新機能の存在だけでは往復削減にならないことを示した。

## 受付拒否の内訳

| code | 件数 | 観測した内容 |
|---|---:|---|
| `INVALID_ARGUMENT` | 15 | op固有fieldの形を一項目ずつ修正。inspect 5、take 2、gate 1、navigate 1、till batch 2、plant batch 3、collect batch 1 |
| `TARGET_UNKNOWN` | 10 | mutation後や順次処理中に、後続targetの現在証拠を再利用できなかった |
| `NO_KNOWN_PATH` | 4 | 現在のknown-safe経路では対象へ到達できなかった |
| `PROGRAM_BUDGET_UNPROVABLE` | 4 | catalog記載値またはモデル推定値ではplannerのworst-caseを証明できなかった |
| `EVALUATION_DEADLINE_IMMINENT` | 1 | deadline直前のwait Actionをrunnerがforward前に拒否 |

`INVALID_ARGUMENT`の中心は、`target`と`position`の混同、`expected_block`、`item`、`minimum_inventory_count`、`tolerance`、`hoe_item`、`seed_item`の欠落、およびplant targetの`{target,support}`構造だった。strict schemaによる拒否自体は安全側だが、現在のTool descriptionでは正しいsignatureを短時間で発見しにくい。

`PROGRAM_BUDGET_UNPROVABLE`では、特に次の不一致を観測した。

- inspectはcatalog記載の`25000 ms / 400 ticks / camera 180° / interaction 1`で拒否され、R11では`30000 ms / 600 ticks / camera 360° / interaction 2`で受理された。
- takeは`max_interactions=2`で拒否され、3が必要だった。
- navigateは短いdistanceとcamera 0°の推定で拒否され、より大きな宣言値で受理された。
- 8-target harvestは`60000 ms / 1200 ticks`で拒否され、`120000 ms / 2400 ticks`で受理された。

## 実行時失敗

5件はいずれも外部code上は`PATH_BLOCKED`としてfail-safe停止した。

| Action | 進捗 |
|---|---|
| `plant_batch_3` | 2 target完了後、3番目のfresh reproofで停止 |
| `collect_cycle1_a` | wheatを0→4まで回収後、4番目の子nodeで`target_unknown`となり停止 |
| `harvest_cycle2` | 5 target完了後、6番目のfresh reproofで停止 |
| `collect_cycle_4` | wheatを17→18まで回収後、2番目の子nodeで停止 |
| `row14_collect_1` | 1番目のcollect子nodeで停止 |

`collect_visible_item_batch`は4 Action受理されたが、全target完了は1件だけだった。現実装はparserで通常の`collect_visible_item`子node列へ展開するため、先のdropへ移動・回収している間に、後続dropが移動、merge、付随回収されると元の座標witnessが失効しやすい。失敗したActionでも途中の回収成果はserver-confirmed inventory差分として残っており、不正な継続はしていない。

## 改善候補（未実装）

この節はR11の結果から得た設計候補であり、本記録時点では実装していない。優先順位も確定ではなく、利用者feedbackを受けて決める。

1. **正確なAction signatureを高い位置へ戻す**: strict schemaを緩めず、inspect、take、gate、navigate、till / plant / harvest / collect batchの必須fieldをTool description冒頭へ短く列挙する。
2. **validation errorをまとめて返す**: 最初の違反1件だけでなく、上限付き`violations[]`として一度に返し、一fieldずつ修正する往復を減らす。
3. **budget契約を一致させる**: catalogの最小値・exampleとplannerを一致させる。またはcompilerが安全なworst-caseをpolicy上限内で導出し、拒否時に`required`と`effective`をcomponent別に返す。policy上限は緩和しない。
4. **collect batchを第一級実行単位にする**: batch全体のinventory baselineと未回収集合を保持し、途中の付随pickupを進捗として認定する。消えたwitnessはinventory差分と組み合わせて判定し、未知entityの探索は行わない。
5. **独立mutation batchの部分進行を検討する**: 任意の`eligible_subset` modeで、各targetを従来どおりfresh reproofし、安全に処理可能なsubsetだけ実行してtarget別結果を返す。既存の厳密fail-fast modeは残す。
6. **目標条件付き有限ループを検討する**: 公開inventoryの`wheat >= 64`を条件としたbounded `repeat_until`をDSLへ追加し、静的上限と各iterationのJIT proofを維持する。ただしstatic target更新の問題が残るため優先度は低い。

現時点の第一候補は、農作業専用の大きな自動化を追加することではなく、正しいDSLを一度で組み立てられる契約、batch内の自然なworld変化を安全に扱う実行単位、拒否理由の機械可読化である。次回修正・再実験は利用者feedbackを受けるまで保留する。
