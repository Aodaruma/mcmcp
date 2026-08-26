# テストと段階導入

## 現在地点

Phase 0〜6は完了しています。本体MODと別source setのdevelopment fixture MODを使って下記のgateを通過し、one-shot continuationと固定`stay`完了処理まで受入を完了しました。Phase 7はv1に含めません。現在はその後段として、決定論的build runnerとCreative Blueprint captureのStage 3/4 development prototypeを検証しています。

現在のMCP surfaceは、Phase 6の9 toolへCreative専用の`capture_creative_region`と型付き`edit_creative_world`を末尾追加した11 tool、routineは13 kindです。全13 startの`completion_intent`は省略可能な`finish_goal | continue_goal`で、省略時は`finish_goal`です。

現在の完了判定の証跡は次のとおりです。

| 検証層 | 結果 |
|---|---|
| 自動test | Java 25でunit/integration test 347件・失敗0・error 0、harness test 11件・失敗0。GameTest 5/5 |
| Phase 5 development fixture | `survey_area`が1/1確認で成功。`transfer_items`が通常barrel画面のopen、12 item移送、close/reopen full readbackを経て12/12確認・unknown 0で成功 |
| Phase 6 development live chain | `survey_area(continue_goal)`が1/1確認・unknown 0で成功してunlockを維持。続く`survey_area(finish_goal)`も同結果で成功し、`goal_finished` lockを確認 |
| Phase 6 Production Prism実Modpack | fixtureなしの最終JAR（SHA-256 `F97007854B671DC5DC0B9F9437761C1203D4AD309ED24E451118AF3F70E902D3`）で、MCP 2025-11-25、9 tools・13 routines、catalog `phase-6`、overworld接続、Voice Chat接続、CraftAgent error 0を確認。通常closeでserver stop、world/all dimensions保存、PIDと8765 listenerの解放を確認。Phase 5 JARは退避済み |

## Phase 1〜6の開発・検証手順

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

Phase 4の固定scenarioは`/craftagent_fixture phase4 all_satisfied|mutations|waterlogged|directional_stairs|hopper|shortage|divergence|hidden|build_runner`で準備します。`phase4_block_plan_fixture` GameTestはexact full before/after state、operation順、資材不足、hidden current拒否、waterlogged slab、directional stairs、hopper、2作業地点のbuild-runner配置を実BlockStateで検査します。semantic施工はdevelopment live gate、実Modpack互換性・起動停止はfixtureなしのproduction gateで分けて確認します。

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
- standaloneの`sleep_at_bed`。食事や睡眠の自動挿入は行わない

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
- Phase 5時点では6 routineとも`completion_intent = finish_goal`だけを受け、Phase 6で共通schemaを更新するまで`continue_goal`を先取りしない

## Phase 6: one-shotと完了後安全化（完了）

- LLM outer loopによる複数routine orchestration
- 全13 startで省略可能な`completion_intent = finish_goal | continue_goal`。省略時は`finish_goal`
- `continue_goal`後もroutine-local cleanup、Voice Chat復元、安全なstay checkpointを必須としてlocal armingを維持
- 同じworld session・1回のlocal armにつき`continue_goal`最大16回。local armに時間上限なし
- current world sessionのlocal armとcapabilityが有効な場合だけadmission
- `finish_goal`成功後の固定`stay` policyと`goal_finished` lock
- 失敗、cancel、emergency stop、world session変更時のchain破棄・lock
- safe anchor、`ask`、自動disconnect、自動food/sleep maintenanceはv1対象外。必要ならLLMが明示的な`navigate_to`、`sleep_at_bed`を中間routineとして実行

合格条件:

- schema契約で全13 kindの省略、`finish_goal`、`continue_goal`を受理し、それ以外を拒否する
- `survey_area(continue_goal)`から`survey_area(finish_goal)`のdevelopment live chainが各1/1確認・unknown 0で成功する
- `continue_goal`成功後はunlocked、`finish_goal`成功後はlockedかつreasonが`goal_finished`
- 16 routine上限、world session束縛、idempotent replay非加算を自動testで確認する
- 時間経過ではlocal armが失効せず、明示無効化またはworld session変更後は開始を拒否する
- active routineはclient tickが止まっていても`max_duration_seconds + 5秒`のwall-clock deadlineで停止する
- safe-stay checkpointがworld/player存在、alive、on-ground、非passenger、health 6以上、水平速度の二乗0.01以下、item-useなし、画面なし、screen ownership idle、現在可視hostileなしを要求する
- unknownを含む必須predicateで成功しない
- 失敗、cancel、emergency stopでinput/screenを解放し、Voice Chatを復元してchainを破棄・lockする
- `stay`はrelease後に無期限guardせず、safe anchor帰還、`ask`、disconnect、maintenance、temporary shelter、combatを暗黙に開始しない

## Post-Phase 6 Stage 3/4: build runnerとCreative Blueprint

Stage 3は`tools/run-build-gate.ps1`です。`craftagent.dev-build-gate/v1`のclosed manifestだけを受け、`navigate_to / apply_block_plan`を最大17 routine、最大900秒で順次実行します。非最終stepは`continue_goal`、最後だけ`finish_goal`とし、各terminalで`SUCCEEDED / goal.verified / finalization.succeeded`をすべて要求します。

Stage 4の固定modeは次です。

- `build_runner`: base→top順の2-cell phaseで作る2段柱×2、途中に通常navigation 1回
- `creative_capture`: 既存galleryをCreativeで開き、cheats/GM permissionを有効化し、playerから32 block超離れた固定1,024-cell region `192,199,192..207,202,207`をcapture可能にする

実行例:

```powershell
# 副作用なしのmanifest検査
pwsh -File tools/run-build-gate.ps1 tools/build-gates/build-runner.example.json -ValidateOnly

# build runner用の破棄可能world
.\gradlew.bat runHarnessClient -PcraftagentFixturePhase3Mode=build_runner
pwsh -File tools/run-build-gate.ps1 tools/build-gates/build-runner.example.json

# Creative capture用の破棄可能world
.\gradlew.bat runHarnessClient -PcraftagentFixturePhase3Mode=creative_capture
```

Creative captureは`start / status`で実行します。距離、client preload、512 cell制限は設けず、各辺256、4,194,304 block、64 chunk column、1 active job、1 chunk in flight、artifact展開後64 MiBへ制限します。現在dimensionの生成済みchunkは順次一時loadし、未生成chunkは生成しません。全cellはgzip artifactへ保存し、MCP応答には進捗とterminal metadataだけを返します。通常観測memoryへ書かず、BlockEntity、fluid、multi-cell、Entity再構築はmanual扱いです。

このprototypeの合格条件:

- manifestのunknown property、bounds矛盾、重複target、phase順、継続予算を副作用前に拒否する
- runner開始後のLLM呼出し0
- 1つの安全なwork poseから2 apply phaseが1 chainで完了し、4 targetがcurrent exact、材料が8→4になる
- failure時にactive routineをcancelし、その成否によらず最後にemergency stopを試す
- 最終statusが`active_routine=null / lock.reason=goal_finished`になる
- Creative capture hashがanchor、dimension、cell ID、入力順に依存せず、全BlockState propertyを結合する
- Creativeのhidden readがWorldMemoryやSurvival profileへ混ざらない
- 材料集計とmanual項目を分け、Entity censusをserver-completeと表現しない
- multiplayer、Survival、cheatsなし、GM permissionなし、armなしを個別に拒否する
- 32 block超、client未load、512 cell超が、それだけでは拒否理由にならない
- 各辺256、4,194,304 block、64 chunk column、展開後64 MiBの境界値と超過拒否を確認する
- unload済み生成chunkをcaptureでき、未生成chunkを生成しない
- start/status応答に全cellを含めず、gzip artifactへ完全なpalette＋RLE Blueprintを保存する
- start/end server tickと非atomic consistencyを明示し、world変更・arm失効・shutdownでjobと一時chunkを解放する
- 最後にunit/integration、harness test、GameTest、上記2 live modeをまとめて確認する

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
