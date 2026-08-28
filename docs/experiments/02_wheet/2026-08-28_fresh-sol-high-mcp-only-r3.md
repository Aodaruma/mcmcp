# fresh gpt-5.6-sol high MCP-only再評価 R3（2026-08-28）

- 実験ID: `02-wheet-2026-08-28-fresh-sol-high-r3`
- artifact: `sol-high-20260828T102249Z`
- artifact保存先: `C:\Users\aod\Documents\Codex\2026-08-26\mine\outputs\mcmcp-eval\sol-high-20260828T102249Z`
- baseline: `tester1-combined-wheat-0e7904e-r3`
- repository commit: `0e7904ed7674e91866d14b82b04bffa2a3470cea`
- model: `gpt-5.6-sol`、reasoning effort `high`
- T0: 2026-08-28 10:22:54.9882098Z
- 終了: 2026-08-28 10:26:50.0320521Z
- 評価時間: 235.044秒
- T0後のoperator介入: なし
- trace audit: **合格**
- 総合判定: **不合格**。道具取得、ゲート通過、9区画の耕作、3区画の播種までは到達したが、内部のAction delivery確認失敗によりcontrolが`off`へ遷移し、小麦64個の収穫を完遂できなかった

## 目的とprompt

R2で追加したenum発見性修正後、同じ新規LLM条件でproduction依頼を完遂できるか確認した。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

promptはR1/R2と同じSHA-256 `c9ff05f797090476edd6548caf9a5e0eff0c3547b288db0bdc13cc9b4fca41fb`である。補足prompt、座標、経路、Action例は会話へ追加していない。effective configのMCP server登録数が0件だったため、評価protocolで許可されたdirect MCP bridgeを使用した。

## 実行環境と開始条件

- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25
- Prism Launcherの既存検証profile `MCMCP-Validation`
- save `tester (1)`
- Survival、開始座標`(199.5, 200.0, 197.0)`、inventoryは空
- チェストはdamage 37のvanilla iron hoe 1個とwheat seeds 64個を保持
- 閉じたoak fence gateの内側に、危険物のないdirt 9区画
- combined wheat fixtureにより`randomTickSpeed=300`、512 rays/tickを一時適用
- fixtureのwall-clock leaseは15分であり、今回のrunはその範囲内で終了
- fixtureは成長速度と観測速度だけを変更し、gameplay操作やinventory編集を代行しない

runner preflightは、公開5 Toolのlive surface hash一致、control `ready`、game unpaused、worldと観測frameの存在、空inventory、512 rays/tick、`visible_entity=0`、Action idle/terminalを確認した。preflightによるgameplay callは0件である。

## T0後の非干渉条件

T0前にMinecraftを起動してworldへ入り、MCP操作をONにした。T0から`turn/completed`までは次を行っていない。

- `computer-use`による画面観測または操作
- PowerShellからのgameplay操作
- Minecraftへのkeyboard / mouse入力
- operatorから評価LLMへの追加入力
- operatorによる途中のMCP state、observation、log、artifact確認

したがって、55件のdynamic call、17件の受理Action、world mutationはfresh評価LLMとMCMCP実行層だけによる。run終了後に行った追加確認はread-only MCP callだけで、worldを変更していない。

## 監査結果

| 項目 | 結果 |
|---|---:|
| trace message | 252 |
| bridge record | 178 |
| dynamic request | 55 |
| schema上有効なdynamic call | 55 |
| Tool成功 | 50 |
| domain Tool error | 5 |
| rate limit / HTTP 429 | 0 |
| transport / protocol failure | 0 |
| `turn/completed` | 1 |
| trace audit | **合格、violation 0件** |

Tool別の内訳は次のとおりである。

| Tool | 合計 | 成功 | domain error |
|---|---:|---:|---:|
| `agent_get_state` | 11 | 11 | 0 |
| `agent_get_observation` | 7 | 6 | 1 |
| `agent_start_action` | 18 | 17 | 1 |
| `agent_get_action` | 19 | 16 | 3 |

domain error 5件は、期限切れobservation frame 1件、現在のvisible surfaceとして確定できない耕作target 1件、action ID欠落1件、最終Action参照時のoutput contract error 2件である。accepted Actionのterminal failureはTool呼出し自体が成功した結果なので、この5件とは別に数える。

17件のaccepted Actionは、9件succeeded、7件が`agent_get_action`からfailedとして正常取得、最後の1件が事後stateで`DELIVERY_UNCONFIRMED`だった。

## 時系列

| UTC | Action / 事象 | 結果 |
|---|---|---|
| 10:22:54 | exact production promptを1回送信 | T0 |
| 10:23:21 | `inspect_supply_chest` | `jit_primitive_budget`、実行node 0 |
| 10:23:30 | `inspect_supply_chest_retry` | `primitive_budget`、実行node 0 |
| 10:23:42 | `face_supply_chest` | succeeded。解析的camera操作でチェストへ正対 |
| 10:23:48 | `inspect_faced_chest` | succeeded。iron hoe 1、wheat seeds 64を確認 |
| 10:23:57 | `take_farming_supplies` | 2/2 nodes succeeded。seeds 64とiron hoe 1を取得 |
| 10:24:12 | `enter_farm` | gate openは成功、続く移動が`PATH_BLOCKED / jit_target_unknown` |
| 10:24:26 | `step_into_farm` | succeeded、3.408 blocks移動して畑内へ到達 |
| 10:24:49 | `till_eight_plots` | 2区画耕作後、3区画目で`jit_primitive_budget` |
| 10:25:04–10:26:02 | 小分けの耕作Action | 成功と再観測を組み合わせ、事後確認では9/9区画がfarmland |
| 10:26:12 | `plant_front_row` | 2区画播種後、3区画目で`jit_primitive_budget` |
| 10:26:21 | `plant_east_front` | succeeded、手前列の3区画目を播種 |
| 10:26:31 | `plant_middle_row` | start responseはqueued。その後Actionは`DELIVERY_UNCONFIRMED`となり、controlが`off`へ遷移 |
| 10:26:34、10:26:46 | 最終Actionの`agent_get_action` | 2回とも`INTERNAL_ERROR: invalid tool result` |
| 10:26:50 | LLMが再armを依頼してturn完了 | audit合格、task自体は未完了 |

主要Actionの結果は次のとおりである。

| name | Action ID | 結果 | 有効だった処理 |
|---|---|---|---|
| `inspect_supply_chest` | `f2581e52-a40d-491c-b3c1-3fb1d4902921` | failed | なし |
| `inspect_supply_chest_retry` | `bb10ed51-7142-4525-9eaf-c34d30b617cb` | failed | なし |
| `face_supply_chest` | `989454ea-8f50-47cf-afdc-46b3676f6a8b` | succeeded | camera |
| `inspect_faced_chest` | `5d0cf4e4-1a85-4244-95cd-3e6e269482f1` | succeeded | container inspect |
| `take_farming_supplies` | `39d93194-c280-43ba-81b6-095f4b66eacc` | succeeded | seeds、hoe取得 |
| `enter_farm` | `30bfc275-74ff-4550-9275-98f199253ef6` | failed | gate openまで成功 |
| `step_into_farm` | `de032aa3-fa18-4a81-9dbb-d478b5d25f29` | succeeded | gate通過 |
| `till_eight_plots` | `dbf35990-f6c5-4336-86f9-6bb18314d81b` | failed | 2区画耕作 |
| `till_plot_3` | `01aae3a3-efc1-456b-90c4-7d138039a1aa` | succeeded | 1区画耕作 |
| `till_plot_four` | `37e24f62-54a2-471b-b7eb-b36a447ee5ff` | succeeded | 1区画耕作 |
| `till_east_plots` | `d765513f-e98c-4309-88a9-8a35bbd4dc7e` | succeeded | 2区画耕作 |
| `till_back_plots` | `664a1782-7117-4a14-b267-f98f6f7464a0` | failed | `postcondition_changed`だがworldでは対象がfarmland化 |
| `till_last_visible_plot` | `360a0ba0-c7a9-4a4e-af7d-fecd76d91302` | failed | `postcondition_changed`だがworldでは対象がfarmland化 |
| `till_center_front` | `421b6fc7-3fa3-4144-990e-a7fef26e6f8e` | succeeded | 1区画耕作 |
| `plant_front_row` | `c2e6fbf7-24a9-4b6e-8206-3d271d082ce0` | failed | 2区画播種 |
| `plant_east_front` | `b3402486-20f4-41f4-8230-9ce9e5125629` | succeeded | 1区画播種 |
| `plant_middle_row` | `a4d4b4a7-5269-4b56-833a-688cd706028a` | failed | `DELIVERY_UNCONFIRMED`、実行前に停止 |

`take_farming_supplies`はR2で発見できなかった正規値を初回で使った。

- seeds: `default_components_only`
- damageのあるiron hoe: `item_id_any_components`

これにより、R2のenum発見性修正がproduction promptだけのfresh LLMに有効だったことを確認できた。

## 最終state

artifact内の最終`agent_get_state`（10:26:46Z）は次のとおりだった。

| 項目 | 最終値 |
|---|---|
| control | `off`、game unpaused |
| player座標 | `(199.35706923890692, 199.9375, 200.39048322582664)` |
| health / hunger | 20 / 20 |
| inventory | iron hoe 1、wheat seeds 61 |
| wheat | 0 |
| 最終Action | `a4d4b4a7-5269-4b56-833a-688cd706028a`、`failed / DELIVERY_UNCONFIRMED` |
| world revision | 394 |
| visible entity | 0 |

run終了後のread-only MCP観測では、次を追加確認した。

- farmland 9/9
- mature wheat crop 3/9
- wheat item 0
- playerは畑内
- controlは引き続き`off`

最終LLM messageは「種64個と鉄の鍬を回収済み」と表現したが、64は取得時の数量であり、最終inventoryは3区画へ播種した後の61個である。判定にはLLMの自然言語ではなくMCP stateを採用する。

## 達成・未達

達成した項目:

- fresh LLMが補助promptなしでチェスト、ゲート、畑を発見した
- chest inspect後、seeds 64とdamage済みiron hoe 1をMCP-onlyで取得した
- 閉じたfence gateを通常操作で開き、畑へ入った
- 9/9区画をhoeでfarmlandに変更した
- 3区画へwheat seedsを植え、成長加速下で3株が成熟した
- multi-node Action失敗後、再観測と小さなActionへ分割して複数回回復した
- 55件すべてを正規bridgeで処理し、trace auditをviolation 0件で完了した

未達の項目:

- 残り6区画を播種する
- 成熟小麦を収穫してdropを回収する
- 収穫後に再播種する
- wheatを64個以上集める
- operatorの再armなしで自律的に最後まで継続する
- 最終controlを`ready`に保つ

## 根因

### Action delivery失敗がcontrolをOFFにした

`plant_middle_row`はschema検証とAction admissionを通過し、queued receiptを返した。しかし、HTTP response後の内部delivery確認を完了できず、Actionは`DELIVERY_UNCONFIRMED`でfailedになった。runtimeのfail-closed処理がこのrecoverable failureでもlocal armingをlockしたため、MCP操作全体が`off`へ遷移した。

T0後にoperator入力はなく、`ready_expires_at`もnullだったため、時間切れや手動UI操作によるOFFではない。delivery確認が失敗した直接のpredicateがadmission changeか確認deadlineかはartifactへ残っておらず、ここは確定できない。

run後のsource-level postmortemでは、delivery ACK処理がresponse受領確認に加えて、変動するplayer pose・observation・経路を含む`admissionFenceCurrent`を二重に評価していたことを確認した。最終Actionは5秒の確認期限より前に失敗しており、この二重admissionが`DELIVERY_UNCONFIRMED`を生成し、続く`closeAgentControl`が永続leaseまで破棄したことが直接原因である。安全preflight自体はAction予約直前と実行開始直前に残し、delivery ACKからだけ分離する必要がある。

さらに、commit `0e7904e`の公開`agent_get_action.outputSchema`にはruntimeが返した`DELIVERY_UNCONFIRMED`がfailure code enumとして含まれていなかった。そのため、LLMの2回の照会は詳細なfailure evidenceではなく`INTERNAL_ERROR: The Minecraft client returned an invalid tool result.`へ置換された。`agent_get_state`の要約だけが最終codeを保持した。

### 加速中の正規block state変化を失敗と判定した

`till_back_plots`と`till_last_visible_plot`は`SERVER_DENIED_OR_DESYNC / postcondition_changed`を返したが、事後worldでは対象を含む9区画すべてがfarmlandだった。`randomTickSpeed=300`では、hoe use直後のfarmland `moisture=0`がserver確認前に有効範囲内の別moistureへ変化し得る。実行層が初期postconditionとの完全一致だけを要求したため、正しいVanilla state evolutionをdesyncと誤判定した。

播種でも、期待するwheat `age=0`が確認前に成長し得る。同じ完全一致条件は今後のplant Actionにも偽陰性を生じさせる。

### 複数nodeのprimitive / camera budget

4 Actionが`primitive_budget`または`jit_primitive_budget`で失敗した。特に8区画耕作と3区画播種は2 nodesまで成功してから、後続targetへの大きなcamera回転で停止した。LLMは単独Actionへ分割して回復できたため今回の最終停止要因ではないが、長いDSLを一度で確実に実行するには改善余地がある。

ゲート開放後の最初の移動も`jit_target_unknown`で失敗したが、再観測後の単独navigationで回復できた。

## 次の修正

1. `agent_get_action.outputSchema.failure.code`へ`DELIVERY_UNCONFIRMED`を追加し、runtime enumと公開catalogの全値一致をtestで固定する。
2. delivery確認失敗、admission change、recoverable Action failureでは、入力を解放して`AGENT`から`READY`へ戻す。明示的なローカルUI OFFだけがauthorizationを破棄する。
3. emergency stopは実行だけを停止してREADY leaseを保持し、ユーザー指定どおりMCP操作ONを維持する。
4. hoeのpostconditionは同じfarmlandで`moisture=0..7`、wheat播種は同じcropで`age=0..7`を正規の時間発展として許容する。他blockや範囲外propertyは引き続き拒否する。
5. semantic mutationのclient prediction、server confirmation、live precheckで同じpostcondition matcherを使用し、結果判定を一貫させる。
6. multi-node ActionのJIT camera予算を保守的な最悪値だけで消費しすぎないよう検証する。修正までは、mutation後に再観測し1～2区画単位へ分割する方法を有効な回復策として維持する。
7. build、unit test、GameTest、catalog hash、audit self-testを通し、同じworld、同じexact prompt、同じT0後非干渉条件でfresh R4を実行する。
8. R4の合格条件は、wheat 64個以上、9区画すべてfarmlandかつ収穫済み区画が再播種済み、control `ready`、trace audit合格とする。
