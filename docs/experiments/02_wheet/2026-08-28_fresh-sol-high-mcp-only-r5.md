# 固定空中fixtureでのfresh gpt-5.6-sol high MCP-only回帰評価 R5（2026-08-28）

- 実験ID: `02-wheet-2026-08-28-fresh-sol-high-r5`
- artifact: `sol-high-20260828T135723Z`
- artifact保存先: `C:\Users\aod\Documents\Codex\2026-08-26\mine\outputs\mcmcp-eval\sol-high-20260828T135723Z`
- baseline: `tester1-combined-wheat-2cfe459-r5`
- repository commit: `2cfe4599c4d1f47cc2542b354929daf131c62aa3`
- model: `gpt-5.6-sol`、reasoning effort `high`
- T0: 2026-08-28 13:57:30.8624674Z
- runner終了: 2026-08-28 14:14:14.4703745Z
- 評価時間: 1,003.608秒（turn duration 1,003.480秒）
- evaluator上限: 1,020秒、残り約16.4秒
- T0後のoperator介入: なし
- trace audit: **合格**、violation 0件
- 固定fixture上のcore task判定: **合格**。MCP-onlyでチェスト確認、道具取得、ゲート通過、耕作、播種、成熟待機、収穫、drop回収を行い、最終inventoryで小麦64個を確認した
- fixture強化completion判定: **未達**。評価後oracleはfarmland 8/9、replanted 0/9であり、9区画の再植付けまでは完了していない
- ユーザー用意環境の受入判定: **未実施**。本runはユーザーがworld内に用意した場所ではなく、test harnessが固定座標へ構築した別の空中arenaを使用した

## 目的とprompt

R4で判明したpickup、観測revision、有限aim retry、camera/container budget、fixture leaseの問題を修正したbaselineが、production想定の短い依頼だけから小麦1 stackを完遂できるか評価した。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

promptはR1～R4と同じSHA-256 `c9ff05f797090476edd6548caf9a5e0eff0c3547b288db0bdc13cc9b4fca41fb`である。補足prompt、座標、経路、Action例は追加していない。effective configのMCP server登録数は0件であり、評価protocolで許可されたdirect MCP bridgeを使用した。

## 実行環境と開始条件

- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25
- Prism Launcherの既存profile `MCMCP-Validation`
- save `tester (1)`
- Survival、開始inventoryは空
- damage 37のvanilla iron hoe 1個とwheat seeds 64個を持つsingle chest
- 閉じたoak fence gateの内側に、危険物のないdirt 9区画
- combined wheat fixtureにより`randomTickSpeed=3000`、512 rays/tickを一時適用
- fixture leaseはarmから20分の非更新wall-clock期限
- fixtureは耕作、成長判定、移動、収穫、drop移動、inventory編集を代行しない

### 評価場所に関する訂正

本runの場所はユーザーが用意した小麦畑ではない。既存profileに残っていた`-Dmcmcp.fixture.phase5.mode=combined_wheat`により、login時にtest harnessが固定workspace `(193,199,193)`～`(206,204,206)`を再構築し、playerを`(199.5,200.0,197.0)`へteleportした。これは既存地形から隔離した再現可能な回帰fixtureだが、ユーザー指定環境での移動・探索を含む受入試験の代替にはならない。

operatorがR4と同じbaselineの再現を優先し、ユーザーが用意した場所を使う要件と固定fixture回帰を混同したことが原因である。したがって、本ノートは固定fixture上の実装回帰としてのみ扱い、ユーザー環境に対する成功実績として引用しない。

runner preflightは、公開5 Toolのlive surface hash一致、control `ready`、game unpaused、worldと観測frameの存在、空inventory、512 rays/tick、`visible_entity=0`、Action idle/terminalを確認した。preflightによるgameplay callは0件である。

## 非干渉条件

T0前だけcomputer-useを用い、Minecraft起動、`tester (1)`へのlogin、MCP操作ONを行った。T0からrunner終了までは次を行っていない。

- `computer-use`による画面観測または操作
- PowerShellからのgameplay操作
- Minecraftへのkeyboard / mouse入力
- operatorから評価LLMへの追加入力
- operatorによる途中のMCP state、observation、log、artifact確認

runner終了後に限りartifactとlogを解析し、読み取り専用の`/mcmcp_fixture phase5 combined_wheat_status`を1回実行した。これは評価terminal後の事後確認であり、合否へ介入していない。

## 結果

最終のauthoritative `agent_get_state`は次のとおりだった。

| 項目 | 値 |
|---|---|
| control | `ready`、game unpaused |
| player座標 | `(199.16206439806854, 200.0, 201.49688747845084)` |
| health / hunger / air | 20 / 20 / 300 |
| inventory | iron hoe 1、wheat 64、wheat seeds 171 |
| 最終Action | `98f2dc27-288a-481d-8b9b-753797e626c8`、succeeded、`COMPLETED` |
| world revision | 1106 |
| rays/tick | 512 |

最終観測には地面上のwheat itemはなく、wheat seeds itemが2 entity残っていた。LLMの最終応答「小麦64個（1スタック）、現在所持品にある」は最終stateと一致し、`turn/completed.status=completed`、`error=null`、Codex exit code 0で終了した。

## Tool・Action監査

| Tool | 呼出 | 成功 | domain error |
|---|---:|---:|---:|
| `agent_get_state` | 64 | 64 | 0 |
| `agent_get_observation` | 59 | 58 | 1 |
| `agent_start_action` | 70 | 66 | 4 |
| `agent_get_action` | 66 | 66 | 0 |
| `agent_cancel_action` | 0 | 0 | 0 |
| 合計 | 259 | 254 | 5 |

- trace message 1,012件、bridge record 790件
- 259件のdynamic requestは全てschema-valid
- 受理Action 66件は全てterminal取得済みで、61 succeeded、5 failed
- transport failure、HTTP 429、orphan response、未回収Action、cancelは0件
- 許可された5 Tool以外の利用、shell、computer-use、browser、web、apps、plugins、multi-agent、secret永続化は0件
- `audit.json.passed=true`、`violations=[]`
- `audit-stderr.log`と`app-server-stderr.log`は空

domain errorは`FRAME_EXPIRED` 1件、`PROGRAM_BUDGET_UNPROVABLE` 1件、`TARGET_UNKNOWN` 2件、`SAFETY_PRECONDITION` 1件だった。Action terminal failureは次の5件であり、LLMはいずれも観測更新、program分割、またはrepositionで回復した。

| Action | 進捗 | failure |
|---|---:|---|
| `till_eight_plots` | 1/8 | `BUDGET_EXCEEDED: motion` |
| `plant_and_grow_cycle_1` | 8/9 | `BUDGET_EXCEEDED: wait_remaining_budget` |
| `harvest_cycle_1` | 3/8 | `PATH_BLOCKED: aim_raycast_unavailable_repeated` |
| `harvest_remaining_cycle_1a` | 0/2 | `PATH_BLOCKED: aim_raycast_unavailable_repeated` |
| `plant_cycle_2` | 5/8 | `BUDGET_EXCEEDED: jit_primitive_budget` |

有限aim retryにより、R4のような無制限spinは再発しなかった。全Action progressの合計は2,119 ticks、移動22.567 blocks、camera 8,740.65度、interaction 15、blocks placed 65、blocks broken 65だった。

## 時系列

1. chestを`inspect_known_container`で確認し、iron hoe 1、wheat seeds 64を正規evidenceとして得た。
2. 2-nodeの`take_known_container_stack`で両方を取得した。
3. `open_known_fence_gate`と`navigate_to_known`を1 Actionへ接続し、gateを開いて3.408 blocks移動した。
4. 耕作Actionのbudget failure後、対象を分割して8区画を耕作した。
5. 8区画を基本単位として、batch播種、代表作物の`wait_until`、batch収穫、drop回収へ収束した。
6. wheatはcycle 1終了後7、以降15、23、31、39、47、55、63へ増加した。
7. 最後に1区画を播種、成熟待機、収穫し、authoritative stateで64個を確認した。

cycle 3以後の主なbatch plant / harvestは各8/8 nodesを完走した。複数primitiveを1 Actionへ接続するprogrammed DSL、解析的camera、navigation-neutral crop revisionが実worldで継続動作した。

## `collect_visible_item`の実証

- 24試行中22件が受理され、22件全てterminal succeeded
- 拒否2件は`SAFETY_PRECONDITION`と`TARGET_UNKNOWN`で、安全側に停止してfresh witness再取得後に回復
- 全24 targetの`displayed_item`と連続XYZは、先行する正規`visible_entity` recordと完全一致
- 22件全てに`NODE_EVIDENCE item_pickup=minecraft:wheat,inventory_before=N,inventory_after=M`があり、各回で`M>N`
- このprimitiveが直接証明した増分は合計27 wheat

残り37 wheatにはharvest直後や移動中のvanilla自動pickupが含まれる。開始inventoryは空、chestにwheatはなく、途中のauthoritative stateでwheatが単調増加し、最終stateが64であるため、task全体のpickup証明は成立する。item消滅だけを成功とみなす経路は使われていない。

## 評価後fixture oracle

runner終了後、20分leaseが期限満了して加速値を正常復元した後のstatusは次のとおりだった。

```text
state=rolled_back wheat=64 farmland=8/9 replanted=0/9
chest_hoe=0 chest_seeds=0 gate_open=true complete=false
lease_remaining_seconds=none rays_per_tick=256 rays_saved=none
stop_reason=lease_expired
random_ticks.mode=normal current=3 saved=none
```

したがって、exact promptの「小麦1 stack」は合格である。一方、fixtureの強いcompletion gateである「64 wheatかつ9/9 farmlandかつ9/9 replanted」は未達であり、core task成功と後片付け完了を混同しない。今回のpromptには最終再植付けが含まれていないため、これをcore taskの不合格理由にはしない。

## 改善点

### P0: 安定性

1. **時間余裕を増やす。** 1,020秒上限に対して残り約16.4秒しかなく、単発の機能PASSであって再現性は未証明である。同一baseline・同一prompt・同一非干渉条件で連続成功を確認する。
2. **単品drop回収をbatch化する。** 22件の単品`collect_visible_item`に加え、state 64回・observation 59回と往復数が多かった。主な改善対象はTool実行時間そのものではなく、観測・回収の分割によるLLM推論・往復overheadである。freshness、pickup AABB、inventory増加条件を維持したまま、複数の観測済みdropを1 Actionで順次回収できるgoal-count型primitiveまたはbounded listを検討する。
3. **budgetを事前に証明する。** `wait_until(max_ticks=12000)`を全体`max_ticks=12000`の末尾へ置くprogramや、耕地化による0.0625 blockの高さ変化をmotion超過とみなすケースを、admission時に具体的な不足component付きで拒否または補正する。

### P1: 効率と完全性

4. **解析的interaction poseを改善する。** `aim_raycast_unavailable_repeated`は有限終了したが2回発生した。face内側の複数候補、reach、遮蔽から成功可能な立ち位置をAction開始前に選ぶ。
5. **8区画固定から9区画へ一般化する。** fresh LLMは中央1区画を作業用の立ち位置として残した。9区画全利用や最終再植付けを別要件として求める場合は、立ち位置を畑外周へ計画できる観測・templateを提供する。
6. **gate通過先の根拠を明確化する。** gate内側targetは観測済みgateから1 block導出され、開扉後JIT検証を通過した。安全だが、開扉後の新規traversabilityを同一Action内で明示的に取得できると監査根拠が強くなる。

## 判定

R5は、ユーザーが指定したexact production promptに対するfresh `gpt-5.6-sol high` MCP-only固定fixture回帰 **PASS** である。チェスト取得、gate通過、耕作、播種、成熟待機、収穫、物理pickup、64 wheatの最終確認まで、T0後の画面操作・shell gameplay・追加contextなしで完遂した。

ただし、deadline余裕は小さく、fixture全9区画の再植付けは未達である。また、ユーザーが用意したworld内の場所では実施していない。よって「隔離fixtureでMCP-only課題を達成できる機能」は実証済みだが、「ユーザー環境でも移動を含めて達成できること」「productionで安定して短時間に再現できること」「強いfixture cleanup gate」は今後の課題とする。
