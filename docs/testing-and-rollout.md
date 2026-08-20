# テストと段階導入

## 現在地点

Phase 0〜5は完了しています。本体MODと別source setのdevelopment fixture MODを使って下記のgateを通過し、inventory・農林業・調査・睡眠まで受入を完了しました。Phase 6は設計済み・未実装、Phase 7はv1に含めません。

現在のMCP surfaceは、既存8 toolに読み取り専用`get_recipes`を加えた9 tool、既存7 routineにPhase 5の6 kindを加えた13 kindです。既存toolのshapeは変えず、`completion_intent`は`finish_goal`固定を維持します。

Phase 5完了判定の証跡は次のとおりです。

| 検証層 | 結果 |
|---|---|
| 自動test | Java 25でunit/integration test 344件、harness test 11件、いずれも失敗0。GameTest 5/5 |
| Development fixture | `survey_area`が1/1確認で成功。`transfer_items`が通常barrel画面のopen、12 item移送、close/reopen full readbackを経て12/12確認・unknown 0で成功 |
| Production Prism実Modpack | fixtureなしの最終JARで、正式MCP handshake、9 tools・13 routines、既知recipe限定応答、全dimension保存、正常shutdown、PIDと8765 listenerの解放を確認。CraftAgent由来ERROR・FATALは0 |

## Phase 1〜5の開発・検証手順

Java 25を指定し、リポジトリ直下で実行します。

```powershell
# 単体/統合テスト、本体JAR、fixture JAR
.\gradlew.bat clean test harnessTest harnessJar build

# fixtureの自動GameTest
.\gradlew.bat runGameTestServer

# 本体+fixtureによる破棄可能なシングルプレイヤー手動試験
.\gradlew.bat runHarnessClient
```

手動試験では新規シングルプレイヤーワールドを使い、`/craftagent_fixture load`で固定arenaを準備します。fixtureは`-Dcraftagent.testHarness=true`、integrated server、単独playerなどをすべて満たさなければ変更を拒否します。コマンドと固定座標の詳細は[`src/harness/README.md`](../src/harness/README.md)を参照してください。

Phase 3の固定scenarioは`/craftagent_fixture phase3 navigate|break|place|lever|cow|reset`で準備します。`phase3_action_fixture` GameTestは移動lane、採掘target、設置support/destination、leverの完全なBlockState、NoAIかつpersistentなcowというfixture前提を検査します。action実行、停止・失敗経路、production Prism実Modpackのlive gateは、上記の完了証跡として別途確認済みです。

Phase 4の固定scenarioは`/craftagent_fixture phase4 all_satisfied|mutations|waterlogged|directional_stairs|hopper|shortage|divergence|hidden`で準備します。`phase4_block_plan_fixture` GameTestはexact full before/after state、operation順、資材不足、hidden current拒否、waterlogged slab、directional stairs、hopperを実BlockStateで検査します。semantic施工はdevelopment live gate、実Modpack互換性・起動停止はfixtureなしのproduction gateで分けて確認します。

本体成果物は`build/libs/craftagent-<version>.jar`、fixtureは`build/libs/craftagent-<version>-test-harness.jar`です。後者は開発専用であり、通常のPrism Launcher instanceやマルチプレイ環境には導入しません。`runHarnessClient`は両source setを開発環境から読み込みます。

## 原則

メインの「くらふとぶ！」instanceを直接変更しません。Prism Launcherで複製した検証用instanceにだけMODを追加し、各gateを順番に通します。

- local single playerでfault injection
- 複製instanceでModpack compatibility
- stationaryな短時間処理からmultiplayer検証
- 前phaseの停止・同期・観測境界が通るまで次の能力を公開しない
- experimental routineはlocal UIで既定OFF

## Phase 0: 設計

- [x] 実環境のMinecraft / NeoForge / Java / MOD version確認
- [x] server接続とSimple Voice Chat接続のbaseline確認
- [x] client-only MCP/NeoForge/Voice Chatの実現性調査
- [x] 通常プレイヤー操作と観測の境界
- [x] block observation、session memory、差分API
- [x] routine inner/outer loop、failure/reconcile、one-shot定義
- [x] Entity user handoffと完了後safety policy
- [x] MCP Java SDK 2.0.0 / protocol 2025-11-25へPoC前提を修正

## Phase 1: 読み取りと停止PoC（完了）

- client-only NeoForge MODの最小build
- embedded loopback MCP server、Origin、auth、rate/size limit
- MCP 2025-11-25 initialize、tools/list、tool call相互運用
- `get_status`、scoped `get_snapshot`、`compare_block_plan`、`emergency_stop`
- client thread dispatch、command deadline、priority stop flag
- current/last-known/unknownのsession memory
- title、world join/leave、dimension、disconnect、shutdown lifecycle
- 既存log baselineとの差分

合格条件:

- LANからportへ接続できない
- 不正Origin/auth/protocol header/巨大inputを拒否
- GET stream非対応時に仕様どおり405
- 30分のidle/world出入りでcrash、thread leak、tick悪化なし
- HTTP threadからMinecraft stateへ直接触るcode pathなし
- 観測したBlockStateの全propertyを返す
- glass/water/stairs/slabの可視境界testを通す
- 壁越し未観測blockを値・Boolean一致のどちらでも取得できない
- 一度観測したblockが時刻付き`last_known`で残る
- hidden block updateだけではmemoryが更新されない
- 再観測、またはサーバー同期を確認した自操作でmemoryが更新され、出所を`interaction_confirmation`として返す
- dimension/session間でmemoryが混ざらない
- stale memoryだけで能動actionを開始できない
- Entity遮蔽後に現在座標が更新されず、hidden updateだけでlast-knownがrefreshされない
- player識別子を返さず、opaque Entity refのTTL切れと任意UUIDを拒否する

## Phase 2: `stationary_break`（完了）

- 公開schemaでは最大60秒
- crosshair target、reach、focus、health、visible threat、progressを毎tick監視
- 2秒以下のinternal attack lease
- `PRECHECK -> EXECUTE -> WAIT_SERVER_SYNC -> VERIFY`
- Simple Voice Chat mute/restore
- cancel、emergency stop、実input、disconnect、死亡、例外の全経路でrelease

合格条件:

- 100回のstart/stopで保持inputが残らない
- force disconnect、Alt+Tab、Esc、死亡、Voice Chat切断で1 tick相当を目標にrelease
- cancel/stop成功応答時点でclient thread上のinputが解放済み
- mute前state true/falseの双方を正しく扱い、manual変更を上書きしない
- 通常プレイヤーと同じ採掘速度、reach、server判定
- already-satisfied、server lag、block replacementを誤成功しない
- timeoutしたstart commandが後から発火しない

## Phase 3: 有限semantic action（完了）

- `navigate_to`
- `break_block`
- `place_block`
- `interact_block`
- `interact_entity`
- structured routine failureとevent cursor

現在の実装境界:

- `navigate_to`: Phase 3 v1は回転を固定し、forward/back/strafeだけで進める短い平坦路に限定する。jump/sprint、段差越え、pathfinding、block破壊は行わず、loadedな足元・頭上空間、安定床、fluid/hazard不在、bounds/travel上限を毎tick確認する。positiveなserver ACKは存在しないため、入力停止後にtolerance内、安定床上、低速、位置drift上限内を10 client tick連続で満たし、その間にposition/rotation/motion correctionを受けないことを`server-reconciled`の根拠とする
- `break_block`: 現在crosshair、通常reach、liveな`expected_before`一致を要求し、通常採掘後のprediction ACKとサーバー由来の完全なBlockStateが`expected_after`（v1ではair）と一致して初めて成功する
- `place_block`: main handの単一cell `BlockItem`を、実際のhit/support faceから導かれる指定座標へ1回設置する。bed/double-height itemを除外し、supportは通常useが別操作を消費しないclosed 6-ID allowlistに限定する。prediction ACKとサーバー由来の完全なBlockStateを検証する
- `interact_block`: empty main hand、non-sneak、現在crosshair、通常reachに限定する。v1 allowlistはlever、fence gate、vanillaのwooden trapdoorだけで、door、button、container、未知MOD blockは除外する。1回の通常use後、prediction ACKとサーバー由来の完全なBlockStateを検証する
- `interact_entity`: current world session/dimensionで現在可視なopaque `entity_ref`が指すadult cowだけを、main handのbucketで1回搾乳する。crosshair、LOS、通常reach、bounds内を再確認し、dispatch後に届いたfreshなselected-slot inventory syncと絶対目標countの`minecraft:milk_bucket`を成功条件にする。曖昧な再dispatchを避けるためretryしない

合格条件:

- block actionはprediction ACKとサーバー由来の完全なBlockStateでpostconditionを確認する
- navigationをpositive ACKがあるかのように表現せず、上記10 tickの`server-reconciled`根拠をevent/resultへ残す
- cow搾乳はfreshなselected-slot inventory syncと絶対目標countを両方確認し、1 dispatch・retryなしを守る
- retry前にfresh observation
- retry/local repairがdeclared boundsを越えない
- mismatch/unknownを`SUCCEEDED`にしない
- player/passive/tamed Entityをattack対象にしない
- LOS/reach/cooldown/collisionを回避しない
- event seq単調、ring buffer truncation時もcurrent state完全
- `needs_replan` event後はfailureを保持して`FAILED`へ終端する
- `/craftagent_fixture phase3 navigate|break|place|lever|cow|reset`の各scenarioと`phase3_action_fixture` GameTestを通す
- fixtureなしのproduction Prism実Modpackで起動、action、Voice Chat復元、正常shutdownを確認する

## Phase 4: Block planと建築（完了）

- 既存の読み取り専用`compare_block_plan`と、7番目のroutine `apply_block_plan`
- 移動を所有しない、1 call = 外部分割済み1 phase、最大64 cell
- `verify_only / break_to_air / place / replace`の閉じたoperation集合
- runtime registry上の全propertyを含む完全な`expected_before / expected_after`
- offsetとstateへmirrorを先、Y軸時計回りrotationを後に適用
- current-only preflight、操作直前recheck、prediction ACK、完全なserver state、checkpoint、world-based reconcile
- 設置資材は現在client inventoryとeligible hotbarを開始baselineにし、各place後にfreshなinbound selected-slot inventory syncを要求
- final verificationは全targetを同じclient tickでcurrentとして再取得し、完全state一致・unknown 0を要求
- Phase 2〜4共通の破壊元allowlistは`minecraft:cobblestone / stone / dirt / obsidian / grass_block`の5 ID。BlockEntity、流体state、未知・MOD blockは拒否
- target cellのpostconditionだけを保証し、通常vanillaの隣接block更新やgame eventによるtarget外無変化は保証しない

合格条件:

- current/memory/unknownを混同せず、hiddenな必須cellをfail closedにする
- planを再実行してcurrent exact-stateでcompletedなblockだけをskipする
- 他player/world変更をglobal reconcileで検出し、保持inputを解放して停止する
- support face、waterlogged slab、directional stairs、hopperを正しく検証し、door/bed等のmulti-block itemは受理しない
- 密閉内部を閉じる前にphase verificationし、wall-through再検査しない
- partial update、server lag、chunk unload、material不足、資材sync不足をfault injectionする
- `break_to_air / replace`で5 ID以外、BlockEntity、流体stateをadmissionとpacket直前の両方で拒否する
- Phase 3/4の設置ではcanonicalな`cobblestone / dirt / grass_block / obsidian / smooth_stone / stone`以外のsupportを拒否し、containerやtoggle blockの通常useを呼ばない
- all-satisfied、mutation、shortage、divergence、hiddenのdevelopment live scenarioを確認する
- development fixtureで実施工とfault scenarioを確認し、fixtureなしのproduction Prism実Modpackでは9 tools・13 routines、lock/Voice Chat接続、正常shutdownを確認する
- rollbackなしで残差planへ収束する

上記合格条件はJava 25の全test、development live gate、production Prism互換性gateで確認済みです。`progress.completed`は処理済み／過去に確認済みの単調checkpointであり、現在の完成数は`verification.confirmed`と`goal.verified`で判断します。

## Phase 5: Inventory、農林業、maintenance（完了）

- `get_recipes`によるclient-known `RecipeDisplayEntry`の限定列挙とresource estimate。coverageは`source = client_known_recipe_displays`、`complete = false`
- allowlist screen handler
- `craft_items`、`transfer_items`
- `tend_crop_area`、`harvest_tree_area`
- `survey_area`
- standaloneの`sleep_at_bed`。食事や睡眠の自動挿入はPhase 6境界に残す

合格条件:

- unexpected screen/manual click/slot desyncで停止
- userが事前に開いたcontainer screenをadoptせず、routine自身が対象を開く
- container clickをACK扱いせず、close→同じ対象をreopen→container/player全slotのfull readbackで目標countを確認する
- craft/transfer retryで重複しない
- source/destination双方のcountをserver同期後に確認
- cropの全BlockStateを観測し成熟判断
- tree routineが入力で宣言され操作直前にもcurrent exact-stateを確認できたcellだけを扱い、指定region外・隣接・hidden logを探索しない。成功を木全体の完全伐採と表現しない
- surveyがcurrent/last-known/unknownとcoverageを返す
- unknownな洞窟・壁裏があれば完全湧き潰しと断定せず、spawn surface評価を`predicted`と返す
- bed-unsafe dimensionでsleepしない
- bed occupied、monster nearby、time restriction、timeoutを構造化failureにする
- `sleep=prefer`は安全なら継続し、`require`は確認不能を成功扱いしない
- 睡眠のrespawn point変更は当該bed actionに対応する受信済みvanilla respawn設定signalがある場合だけ確認済み`effects`として返す
- maintenance前checkpoint、帰還後diffを必須にする
- 6 routineとも`completion_intent = finish_goal`だけを受け、Phase 6の`continue_goal`やouter loopを先取りしない

## Phase 6: one-shotと完了後安全化（未実装）

- LLM outer loopによる複数routine orchestration
- 中間routineの`continue_goal`と最終routineの`finish_goal`
- resource shortage、world divergence、safe handoffの再計画
- safe anchor、food/sleep、finalization
- local policyによるoptional normal disconnect
- goal completionとfinalization failureの分離

合格条件:

- 一度のユーザーgoalから複数routineを実行し、確認済み完了か根拠付き停止になる
- `continue_goal`で中間routine後に帰宅・切断せず、`finish_goal`だけが完了policyを実行する
- 省略時は`finish_goal`となり、回数・総時間・unlock expiry上限を越えてfinalizationを延期できない
- routine hard deadline、`ask` timeout、fallback、reserveがunlock expiry内に収まらなければ開始を拒否する
- unknownを含む必須predicateで成功しない
- safe anchorが塞がれた場合、`goal.verified=true`とroutine failureを正しく併記
- bed/safe anchorがwork region外でも、開始時に承認済みcorridorを固定できる場合だけ実行する
- goalがwork deadline直前に完了しても、予約済みFINALIZING時間を確保する
- user所有のgate/water/blockをcleanupせず、routine所有を証明できない場合はfinalization failureにする
- `WAITING`中もhostile、低体力、hard deadlineを監視する
- `ask`は期限付きlocal UI promptと固定fallback、`stay`はrelease後に無期限guardしない
- disconnectは許可済みlocal policyでのみ発生
- disconnect前にinput/screen release、audit event、Voice Chat復元
- auto reconnect/loginしない
- low health、visible hostile、fall riskでretreat/stop policyを守る
- temporary shelterやcombatを暗黙に開始しない

## Phase 7: 収容済みEntity搬送（experimental）

初版catalogには含めません。個別gateを全て通ったmethodだけlocal opt-inで有効化します。

- `operate_prepared_transfer`
- mode別にboat/minecart/sealed waterwayを分離試験
- userがcapture、route、destination cageを準備
- passenger/containment、gate順序、destination安定をserver-confirmed

合格条件:

- 汎用`transport_entity`や任意captureを公開しない
- target lost、vehicle empty/destroyed、route blocked、containment breachを検出
- 脱走時に追跡せずgate close、retreat、user handoff
- source/destinationを同時に開けない
- hostile transportはlocal test worldで十分な成功率と安全性を確認するまでmultiplayer無効
- fishing rod、aggro/餌/POI誘導、自動押し込みは別experimental flag
- fishing rod試験はlocal test harness内に限定し、MCP catalogへ公開しない
- success時はdestination安定、全開口閉鎖、temporary flow/power停止、player退避、全input releaseを確認する

## Multiplayer compatibility gate

Phase 2以降、能力ごとに短時間から試験します。

事前確認:

- 利用者が接続先のruleとclient automationの扱いを確認
- local設定で作業region、対象、最大時間、破壊、Entity、completion policyを制限
- log採取とMOD削除によるrollback手順を準備

最初は1分、5分、15分と段階的に延ばし、server負荷、anti-cheat、他playerへの影響を確認します。問題があれば複製instanceからMODを外します。

## Baritone等の外部adapter

PoCには含めません。導入する場合は次を別gateにします。

- normal movement/input pathだけを使う
- visible/observed memory以外のblock検索をLLMへ公開しない
- hidden ore/structure searchを禁止
- pathfinderがbounds、破壊allowlist、danger policyを越えない
- adapter failure時にcore safetyが停止できる

## Release判定

次のいずれかが未達なら、その環境・能力での能動自動化はNO-GOです。

- 全stop pathの自動test
- MCP loopback/auth/origin/protocol conformance
- 観測・memoryのwall-through防止test
- action kind固有のserver-confirmedまたはserver-reconciled postconditionと、その根拠
- failure/reconcileで未完了を成功扱いしないこと
- Voice Chatのfail-closedとstate restore
- 対象Modpackの起動・接続・30分稼働
- crash/stop時にinputが残らないこと
