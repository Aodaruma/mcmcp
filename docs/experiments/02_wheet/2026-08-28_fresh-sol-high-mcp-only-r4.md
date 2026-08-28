# fresh gpt-5.6-sol high MCP-only再評価 R4（2026-08-28）

- 実験ID: `02-wheet-2026-08-28-fresh-sol-high-r4`
- artifact: `sol-high-20260828T110851Z`
- artifact保存先: `C:\Users\aod\Documents\Codex\2026-08-26\mine\outputs\mcmcp-eval\sol-high-20260828T110851Z`
- baseline: `tester1-combined-wheat-f816298-r4`
- repository commit: `e520a02bc38a27359aa33d885f2e27dd51273b1a`
- model: `gpt-5.6-sol`、reasoning effort `high`
- T0: 2026-08-28 11:08:56.8051860Z
- runner終了: 2026-08-28 11:25:56.8261449Z
- 評価時間: 1,020.021秒（17分の評価期限まで継続）
- T0後のoperator介入: なし
- trace audit: **不合格**。評価期限により`turn/completed`がなく、内容違反ではなくlifecycle 2件で不合格
- 総合判定: **不合格**。MCP-onlyで道具取得、ゲート通過、9区画の耕作・播種、複数回の収穫とdrop回収まで到達したが、wheatは最後に確認できた時点で10個であり、64個を完遂しなかった

## 目的とprompt

R3後のdelivery、postcondition、公開schema修正を含むbaselineで、production想定の短い依頼だけから小麦1 stackを完遂できるか再評価した。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

promptはR1～R3と同じSHA-256 `c9ff05f797090476edd6548caf9a5e0eff0c3547b288db0bdc13cc9b4fca41fb`である。補足prompt、座標、経路、Action例は追加していない。effective configのMCP server登録数が0件だったため、評価protocolで許可されたdirect MCP bridgeを使用した。

## 実行環境と開始条件

- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25
- Prism Launcherの既存検証profile `MCMCP-Validation`
- save `tester (1)`
- Survival、開始座標`(199.5, 200.0, 197.0)`、inventoryは空
- チェストはdamage 37のvanilla iron hoe 1個とwheat seeds 64個を保持
- 閉じたoak fence gateの内側に、危険物のないdirt 9区画
- combined wheat fixtureにより`randomTickSpeed=300`、512 rays/tickを一時適用
- fixtureは15分の非更新wall-clock leaseを持ち、成長速度と観測速度だけを変更する
- fixtureはgameplay操作、inventory編集、経路移動を代行しない

runner preflightは、公開5 Toolのlive surface hash一致、control `ready`、game unpaused、worldと観測frameの存在、空inventory、512 rays/tick、`visible_entity=0`、Action idle/terminalを確認した。preflightによるgameplay callは0件である。

## T0後の非干渉条件

T0前にMinecraftを起動してworldへ入り、MCP操作をONにした。T0からrunner終了までは次を行っていない。

- `computer-use`による画面観測または操作
- PowerShellからのgameplay操作
- Minecraftへのkeyboard / mouse入力
- operatorから評価LLMへの追加入力
- operatorによる途中のMCP state、observation、log、artifact確認

したがって、127件のdynamic call、38件の受理Action、world mutationはfresh評価LLMとMCMCP実行層だけによる。runner終了後の調査はartifactとbaseline sourceのread-only解析であり、worldを変更していない。

## 監査結果

| 項目 | 結果 |
|---|---:|
| trace message | 586 |
| bridge record | 394 |
| dynamic request | 127 |
| schema上有効なdynamic call | 127 |
| Tool成功 | 115 |
| domain Tool error | 12 |
| rate limit / HTTP 429 | 0 |
| `turn/completed` | 0 |
| trace audit | **不合格、lifecycle violation 2件** |

Tool別の内訳は次のとおりである。

| Tool | 合計 | 成功 | domain error |
|---|---:|---:|---:|
| `agent_get_state` | 20 | 20 | 0 |
| `agent_get_observation` | 13 | 11 | 2 |
| `agent_start_action` | 48 | 38 | 10 |
| `agent_get_action` | 44 | 44 | 0 |
| `agent_cancel_action` | 2 | 2 | 0 |

observationの2 errorは`FRAME_EXPIRED`である。startの10 errorは、route target unknown 4件、plant supportのcurrent UP face不足2件、no known path 1件、world mutationを`repeat`内に置いたことによる静的拒否1件、worst-case budget不成立1件、gateをlog用の`break_known_face`で破壊しようとしたinvalid argument 1件だった。最後の拒否は、安全上許可されていない破壊をvalidatorが正しく止めたものである。

38件のaccepted Actionは、22件succeeded、13件failed、2件cancelled、runner終了直前に受理されterminalを取得できなかった1件だった。failedの内訳は次のとおりである。

| failure evidence | 件数 |
|---|---:|
| `jit_primitive_budget` | 2 |
| `primitive_budget` | 2 |
| `transfer_full_stack_unavailable` | 2 |
| `jit_target_unknown` | 3 |
| `jit_no_known_path` | 3 |
| `crop_mature_timeout` | 1 |

auditのviolationは`turn/completed`必須条件とturn lifecycle ID整合の2件であり、runnerが17分のdeadlineでCodex turnを終了したことに由来する。127 callはすべて許可された5 Toolだけを使用し、schema違反、secret漏洩、computer-use介入は記録されていない。ただしterminal turnがないため、R4を監査合格とは扱わない。

## 時系列

| UTC | Action / 事象 | 結果 |
|---|---|---|
| 11:08:56 | exact production promptを1回送信 | T0 |
| 11:09:32～11:09:52 | chest inspectを3方式で試行 | `jit_primitive_budget`、`primitive_budget`。最後はchestへのnavigation 1 nodeだけ成功 |
| 11:10:04 | `take_wheat_seeds` | succeeded、seeds 64を取得 |
| 11:10:16、11:10:23 | wooden / stone hoeを推測して取得試行 | どちらも`transfer_full_stack_unavailable` |
| 11:10:30 | `take_iron_hoe` | succeeded、damage済みiron hoeを取得 |
| 11:10:42 | `enter_field` | gate openと畑内への3.54 blocks移動が2/2 succeeded |
| 11:11:05 | `till_first_eight` | 3区画耕作後、4区画目で`jit_primitive_budget` |
| 11:11:15、11:11:22 | `till_middle_row`、`till_back_row` | 各3/3 succeeded。合計9/9 farmland |
| 11:11:31～11:11:49 | front / middle / back rowを播種 | 3 Actionが各3/3 succeeded、9/9播種 |
| 11:12:18 | `crop_198_200_cycle1` | 成熟待機、収穫、再播種が3/3 succeeded |
| 11:12:57～11:15台 | `finish_crop_198_200` | 3 cycle、9/21 nodesまで成功。2,476 ticks時点でLLMがcancel |
| 11:15:45 | `exit_near_drops` | succeeded、畑外へ移動してpickup |
| 11:16:15 | `front_row_collective_cycle` | 1株のwaitとharvest後、同じ株のreplantが`jit_target_unknown` |
| 11:16:27 | `harvest_all_remaining_mature` | 残り8株を63 ticks、156.5度で全て収穫 |
| 11:16:56 | `reenter_empty_field` | gate操作後、畑中心への移動が`jit_target_unknown` |
| 11:17:20 | `stand_in_gate` | succeeded |
| 11:17:40 | `plant_from_gate` | 3株播種 |
| 11:17:51 | `plant_rest_from_gate` | 2株播種後、3株目のsupportが`jit_target_unknown` |
| 11:18:17～11:18:40 | drop回収routeを3回試行 | 1～2 nodes後に`jit_no_known_path` |
| 11:18:51 | `wide_outer_collection_3` | 外周8 waypoint、20.94 blocks、242 ticksを完走 |
| 11:20:02 | `return_gate_path` | gate前へ2/2 navigation succeeded |
| 11:20:14、11:20:45 | `harvest_front_three`、`harvest_middle_existing` | 3株と2株を収穫 |
| 11:21:02 | `plant_far_to_middle` | 最奥列の1株目で`aim_raycast_unavailable`を119回反復し、0/6のままcancel |
| 11:21:39～11:23:57 | face、step back、再観測、gate復帰 | standと観測poseを再構成 |
| 11:23:01 | policyのrays/tickが512から256へ復帰 | combined fixtureの15分lease終了を確認 |
| 11:24:03 | `plant_two_working_rows` | middle / frontの6株を87 ticks、323.3度で播種 |
| 11:24:21 | `six_crop_cycle_1` | 先頭株の`wait_until(max_ticks=1700)`が`crop_mature_timeout` |
| 11:25:55 | `wait_anchor_crop_mature` | queued receipt。terminal取得前にrunner deadline |
| 11:25:56 | evaluator timeout | `evaluation deadline expired before turn/completed` |

主要Actionの効率と結果は次のとおりである。

| name | Action ID | 結果 | nodes | ticks | camera |
|---|---|---|---:|---:|---:|
| `enter_field` | `84b77fc1-06d7-44f2-9baf-020f36b9c3b4` | succeeded | 2/2 | 63 | 197.8° |
| `till_first_eight` | `62b8c02d-b18e-4e2a-b023-1d5f50ad9d46` | `jit_primitive_budget` | 3/8 | 42 | 253.8° |
| `harvest_all_remaining_mature` | `33cc7fd5-7711-4020-a48e-c029f84ace8c` | succeeded | 8/8 | 63 | 156.5° |
| `plant_rest_from_gate` | `ee90f08f-d6bf-46c1-806f-293398ea096a` | `jit_target_unknown` | 2/6 | 56 | 58.3° |
| `wide_outer_collection_3` | `19738e5d-6c54-4e99-bc56-dc15800165ce` | succeeded | 8/8 | 242 | 0° |
| `plant_far_to_middle` | `663321f0-c227-4b54-982f-1b155ddd239d` | cancelled | 0/6 | 364 | 56.9° |
| `plant_two_working_rows` | `70a798b0-a581-44c8-8e0c-ca380b0b9fbe` | succeeded | 6/6 | 87 | 323.3° |
| `six_crop_cycle_1` | `b138e2a2-c9f5-4ba8-931f-8223e3ace03a` | `crop_mature_timeout` | 0/18 | 1,700 | 0° |

`harvest_all_remaining_mature`と`plant_two_working_rows`は、複数primitiveを1 Actionへ接続するDSLが実際に機能し、安定した観測条件なら単発Tool callより効率的であることを示した。一方、mutation後の再観測、camera予算、raycast再現性が不安定な場合は、後続node全体が止まる。

## drop観測と回収

11:17:30の`visible_entity`には9件のitem entityがあり、観測不足でdropを認識できなかったわけではない。内訳はwheat 4 entity、wheat seeds 5 entityで、各recordはitem種別と小数XYZを持っていた。例は次のとおりである。

- wheat: `(198.1794, 199.9375, 202.1616)`
- wheat: `(199.2156, 200.0, 203.25)`
- wheat: `(200.0327, 199.9375, 202.6538)`
- seeds: `(197.8855, 200.0, 200.4773)`
- seeds: `(199.5933, 199.9375, 202.2451)`

しかし、Action DSLのnavigation targetは整数block cellだけで、観測したentityをtokenで追跡する操作、pickup半径内の到達可能点を選ぶ操作、item消滅またはinventory増分を成功条件にする操作がない。LLMはentity XYZからの自動routeではなく、畑の外周を整数waypointで推測して列挙した。最初の3 routeはmap断裂で失敗し、さらに外側の`z=206`を通る4本目だけが完走した。結果としてdrop回収は偶発的な通過pickupに依存し、18回のharvestに対して最後に確認できたwheatは10個だった。

## 最終state

artifact内の最後の`agent_get_state`は11:23:57Zであり、後続の6株播種より前である。

| 項目 | 最終state callの値 |
|---|---|
| control | `ready`、game unpaused |
| player座標 | `(199.50739757323382, 200.0, 199.49421551632753)` |
| health / hunger | 20 / 20 |
| inventory | iron hoe 1、wheat 10、wheat seeds 60 |
| 最終Action | `a1128219-75f0-4158-8571-a3d848785b9e`、succeeded |
| world revision | 677 |
| visible entity | 0 |
| rays/tick | 256 |

その後のAction traceから、次は確定できる。

- `plant_two_working_rows`は6/6 succeededし、`blocks_placed=6`だった
- 対象は`z=201`の3区画と`z=200`の3区画で、最奥`z=202`の3区画は含まれない
- 成功した播種以後にharvest Actionは成功していない
- `six_crop_cycle_1`は最初の成熟待機だけで1,700 ticksを使い、収穫・再播種nodeへ進まなかった
- `wait_anchor_crop_mature`はrunner終了直前に受理されたが、terminal stateはartifactにない

したがって、runner終了直前には少なくとも6株が再播種済みで、播種による通常の在庫差分を適用するとseedsは54個相当である。ただし、この時点のlive `agent_get_state`は取得されておらず、最終control、world revision、crop ageは確定値として記録できない。判定には最後のstate callとserver-confirmed Action progressだけを使用し、terminal未取得Actionを成功とは扱わない。

## 達成・未達

達成した項目:

- fresh LLMが補助promptなしでチェスト、ゲート、畑を発見した
- seeds 64とdamage済みiron hoeをMCP-onlyで取得した
- 閉じたfence gateを通常操作で開き、最初の`enter_field`だけで畑内へ移動した
- 9/9区画をfarmlandへ変更し、9/9区画へ初回播種した
- 8株連続harvest、6株連続plantをprogrammed DSLで完走した
- trace上18回のmature wheat harvestを実行した
- visible itemの種別とXYZをMCP observationだけで取得した
- delivery failureでcontrolがOFFになるR3の停止は再発しなかった
- 禁止されたgate破壊はvalidatorが拒否した

未達の項目:

- wheatを64個以上集める
- harvestしたdropを漏れなく回収する
- 最奥列を含む9/9区画を再播種する
- 9区画をまとめて成熟待機、収穫、再播種する
- evaluator deadline内にturnをterminalへ到達させる
- 最終control、inventory、crop stateをlive stateで確認する
- trace auditを合格させる

## 根因

### 1. 公開720度と実効360度のbudget不一致

R4のTool schemaと`agent_get_state.policy.max_camera_degrees`は720度を公開していたが、baseline sourceの`ActionDslCompiler.PHASE_ONE_HARD_LIMIT`はcameraを360度へ固定していた。requestで720度を指定しても`effectiveBudget`はcomponent-wise minimumにより360度へ縮小される。

さらにmutationのJIT costは、解析的なtarget角だけでなく、100 ticksのview lease中に生じ得るcamera travelを加算して最大360度を予約する。`till_first_eight`は3 nodesで実測253.8度を使ったため、4 node目の保守的costが実効残量106.2度へ収まらず、`jit_primitive_budget`になった。LLMからは720度を使えるように見えるため、max値で再試行しても回復できないcontract mismatchである。

container inspectにも別のcost不一致がある。最初の例示どおり20,000 msを指定したActionは、開始後のelapsed timeへ400 ticks＝20,000 msの全boundを再加算して即座に`jit_primitive_budget`になった。長いdurationへ増やした後も、plannerは観測ray-hitへのcamera costを計算する一方、container adapterはblock centerへ向くため、4.5度または9度の実cameraがoccurrence boundを超えて`primitive_budget`になり得る。artifactは超過componentを返していないため後半2件のcomponent自体は断定できないが、baseline sourceでplannerとadapterのaim pointが一致していないことは確認できる。

### 2. 非衝突crop mutationが経路mapを広く失効させる

baselineの`synchronizeKnownTraversability`は、block mutationの種類やcollision shapeを区別せず、変更blockからx/z各±1、y -2～+3にあるcellへ接続するedgeをSTALE化する。9区画のplant、growth、harvestが続くと、非衝突のwheat age変化だけでも3x3畑とgate付近のedgeが反復して失効する。

同tickのlocal observationはplayer周辺を再投影するが、失効した全接続を常に復元できるとは限らない。baselineの`LocalObservationVolume.expandTraversable`はBFSで初めて到達したcellだけを記録し、既に到達済みのcellへ入る別edgeは記録前にskipするため、1 frameの出力は完全な局所gridではなく疎な到達treeになる。一方、A*は斜めedgeを使う際、同じfrom cellから両側へ出る直交edgeが`CONFIRMED`であることを要求する。疎なtreeはこのside edgeを持つ保証がなく、観測済みの斜めedge自体が経路探索では使用不能になり得る。

A*はtargetへ接続する非STALE edgeが1本もない場合を`TARGET_UNKNOWN`、target cellはあるが接続graphが切れている場合を`NO_PATH`とする。過剰失効、疎な再投影、斜めcorner条件の組合せが次の実測と一致する。

- gate open後の畑中心が`jit_target_unknown`
- drop回収routeが1～2 nodes進んだ後に`jit_no_known_path`
- より広い外周routeは同じ地形で8/8 succeeded

よって、物理的なfence閉塞だけが原因ではなく、world revisionごとの過剰なedge失効と局所graphの穴が主因である。斜めcorner条件自体はswept-AABB安全性のため維持し、不足edgeの再観測またはcardinal routeへfallbackすべきである。

### 3. mutation後のsurface evidenceが全revision単位で失効する

plant / harvestのJIT plannerは、latest frame、current world revision、blockとface一致、非null ray hit、同じinteraction pose、reach内をすべて満たすsurfaceだけを使用する。1株を変更するとworld revisionが進むため、次の株はまず`target_unknown`となり、全周frameの再構築を待つ。

成功したbatchでは2 ticks程度で`jit_primitive_bound`へ回復した。一方、手前株や既に植えたcropによりsupportのUP faceが遮られるposeでは、40 ticksの再観測期限内に証拠が戻らず、`front_row_collective_cycle`と`plant_rest_from_gate`が`jit_target_unknown`で終わった。`PATH_BLOCKED`という外側codeはnavigationとsurface evidence不足を同じ分類へ畳んでおり、LLMが移動すべきか待つべきかを判断しにくい。

### 4. `aim_raycast_unavailable`のretryが有限でない

`plant_far_to_middle`では最奥列の最初のsupportをplannerが受理した後、semantic実行時のlive raycastが同じfaceを再現できなかった。planned aimがある場合、adapterは観測由来の1点だけを候補にし、0.75度以内へ向いた時点のraycastが外れると`AIM_RAYCAST_UNAVAILABLE`を返す。遠いpartial surfaceでは生のray-hitがedge寄りになり、わずかな角度差、丸め、手前形状によって別faceへ当たり得る。

runtimeの`retryAgentMutationAim`は失敗のたびに`primitivePlanDeadlineTick = current progress + 40`へ置き直す。したがって、同じ証拠が再びbindされる限り全体deadlineへ到達しない。R4では`aim_raycast_unavailable`と`jit_primitive_bound`を各119回繰り返し、364 ticks、0/6 nodesのままLLMによるcancelまで継続した。これは一時的な観測待ちではなく、実行層のlivelockである。

### 5. drop用の意味的Actionがない

dropの正確なitem種別とXYZは観測できていたが、移動DSLは整数の固定cellだけを受け取る。item entityは移動・合流・消滅し得るのに、entity token、現在位置への追従、pickup可能な近傍goal set、inventory deltaによる完了条件がない。LLMが手作業で外周waypointを作るため、token消費と移動距離が増え、nav mapの小さな穴にも弱くなった。

### 6. 6株だけの再播種で止まった理由

6株は内部上限で自動的に選ばれた数ではなく、LLMが最後に送ったprogramの明示的な対象数である。

1. 最奥`z=202`から始めた6株programが、1株目のraycast livelockで0/6のままcancelされた。
2. face、後退、再観測、gate復帰を行った後、LLMは既知に成功しやすい`z=201`と`z=200`だけを`plant_two_working_rows`へ列挙した。
3. この6株は全て成功したが、最奥3株はprogramに含まれていなかった。
4. 11:23:01にcombined fixtureの15分leaseが終了し、rays/tickは512から256へ、同じfixture管理下の`randomTickSpeed`も加速値から元の値へ復帰した。
5. lease終了後に植えた6株の最初の`wait_until(max_ticks=1700)`は成熟を確認できずtimeoutした。
6. 次のwaitを開始した直後、17分のevaluator deadlineへ達した。

したがって、直接原因は「最奥列のraycast livelock後に2列だけへscopeを縮小したこと」、継続不能の原因は「fixture lease終了後の成熟待機とrunner deadline」である。なお、`wait_until`はlatest current-revision frameに対象の`crop_mature=true`がある場合だけ成功するため、timeoutはworld内で絶対に未成熟だった証明ではなく、期限内に正規観測で成熟を確認できなかったことを意味する。

## 次の修正

### P0: production blocker

1. `aim_raycast_unavailable`のretryをAction全体で有限化する。最初のprimitive plan deadlineをretryごとに延長せず、attempt回数と累積ticksを上限化し、超過時はrecoverableな`TARGET_OCCLUDED`または`INTERACTION_POSITION_REQUIRED`でterminalへする。
2. raw ray-hit 1点ではなく、face内側へepsilonを取った複数の解析的aim候補を作る。cameraが十分収束し、live crosshairとfaceが一致した候補だけをdispatchする。同じ候補を再bindし続けない。
3. Tool schema、state policy、compiler hard limitを単一の定数源へ統一する。720度を公開するなら実効上限も720度にし、360度を維持するならschemaも360度にする。failure evidenceへ超過component、used、required、remainingを返す。
4. mutation camera costから「100 ticks中に起こり得る任意view scan」の過大予約を分離し、実際の解析的aim列と有限誤差からprogram全体のcamera上限を計算する。8 block batchが実測範囲内なら途中停止しないことをtestする。
5. navigation edge invalidationをcollision / support / fluid / hazardの変化に限定する。wheat age、air↔wheat、farmland moistureなどswept-AABBと支持面を変えないmutationではedgeを維持する。gate open/closeのような形状変更は限定範囲だけ失効させ、同revisionのlocal evidenceを即時再投入する。`expandTraversable`では`reached`をqueue投入の重複防止だけに使い、到達済みcellへ向かう安全edgeもrecordすることで、A*のcorner証拠を欠かさない。

### P0: 評価基盤

6. combined wheat fixture leaseをevaluator timeoutより長くするか、runnerが評価中だけ明示的に保持し、turn terminal後に必ずrestoreする。少なくとも17分評価で15分時点に加速が切れないようにする。restore失敗と元の`randomTickSpeed` / rays値は引き続きfail-closedで検証する。

### P1: production効率と自律性

7. `collect_visible_item`を追加する。frameに由来する`displayed_item`と連続座標を受け、最新の同種itemを再照合しながらpickup可能な最近傍reachable cellへ移動する。成功は対象itemのinventory絶対個数増加だけで確認し、安全経路または増加確認がなければ明示的に失敗する。
8. `harvest_and_replant_known_wheat`またはboundedなpatch Actionを追加する。harvestのserver-confirmed postcondition、変化しないfarmland support、seed在庫を同じfinite primitive内で引き継ぎ、全周frameのglobal revision更新を各株で待たない。
9. world evidenceをglobal revisionだけで全失効させず、変更cellと遮蔽関係に基づく局所invalidityへする。少なくともserver-confirmed mutationで「対象cropはairになった」「supportはfarmlandのまま」を次nodeへ安全に引き継ぐ。
10. crop waitに`any/all known patch mature`を追加し、対象patchの観測coverageと未確認理由を返す。見えていないことと未成熟を区別し、単一の遮蔽株が全cycleを止めないようにする。
11. container plannerとadapterで同じaim pointを使用し、20,000 msの公開minimumに内部elapsed headroomを含める。chest inspectが成功すれば、hoe種類を2回推測する無駄もなくなる。

### P2: 診断性

12. `PATH_BLOCKED`を、navigationの`TARGET_CELL_UNKNOWN` / `GRAPH_DISCONNECTED`と、mutationの`SURFACE_NOT_CURRENT` / `FACE_OCCLUDED`へ分ける。reobservation期限、候補face、必要なpose変更をevidenceへ含める。
13. A*の斜めcorner安全条件は緩和せず、side edge不足時にcardinal-only routeと局所再観測を試す。route failureには最初に切れたedgeを返す。
14. 同じworld、同じexact prompt、T0後非干渉条件でR5を実行する。合格条件はwheat 64個以上、9/9 farmland、収穫済み全区画が再播種済み、control `ready`、全Action terminal、trace audit合格とする。
