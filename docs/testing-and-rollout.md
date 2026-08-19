# テストと段階導入

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

## Phase 1: 読み取りと停止PoC

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
- 再観測・server-confirmed actionでmemoryが更新される
- dimension/session間でmemoryが混ざらない
- stale memoryだけで能動actionを開始できない
- Entity遮蔽後に現在座標が更新されず、hidden updateだけでlast-knownがrefreshされない
- player識別子を返さず、opaque Entity refのTTL切れと任意UUIDを拒否する

## Phase 2: `stationary_break`

- 最初は最大60秒、合格後も上限300秒
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

## Phase 3: 有限semantic action

- `navigate_to`
- `break_block`
- `place_block`
- `interact_block`
- `interact_entity`
- structured routine failureとevent cursor

合格条件:

- すべてのactionでserver-confirmed postcondition
- retry前にfresh observation
- retry/local repairがdeclared boundsを越えない
- mismatch/unknownを`SUCCEEDED`にしない
- player/passive/tamed Entityをattack対象にしない
- LOS/reach/cooldown/collisionを回避しない
- event seq単調、ring buffer truncation時もcurrent state完全
- `needs_replan` event後はfailureを保持して`FAILED`へ終端する

## Phase 4: Block planと建築

- `compare_block_plan`
- phase分割された`apply_block_plan`
- state property、rotation、mirror、air/clearance
- checkpointとworld-based reconcile
- 建築前後のresource estimateと`get_recipes`

合格条件:

- current/memory/unknownを混同しない
- planを再実行してcompleted blockをskip
- 他player/world変更を検出して停止
- support face、waterlogged、door/bed multi-block、stairs、hopper等を正しく検証
- 密閉内部を閉じる前にphase verificationし、wall-through再検査しない
- partial update、server lag、chunk unload、material不足をfault injection
- rollbackなしで残差planへ収束

## Phase 5: Inventory、農林業、maintenance

- allowlist screen handler
- `craft_items`、`transfer_items`
- `tend_crop_area`、`harvest_tree_area`
- `survey_area`
- 食事、`sleep_at_bed`

合格条件:

- unexpected screen/manual click/slot desyncで停止
- userが事前に開いたcontainer screenをadoptせず、routine自身が対象を開く
- craft/transfer retryで重複しない
- source/destination双方のcountをserver同期後に確認
- cropの全BlockStateを観測し成熟判断
- tree routineが指定region外やhidden logを直接探索しない
- surveyがcurrent/last-known/unknownとcoverageを返す
- unknownな洞窟・壁裏があれば完全湧き潰しと断定せず、spawn surface評価を`predicted`と返す
- bed-unsafe dimensionでsleepしない
- bed occupied、monster nearby、time restriction、timeoutを構造化failureにする
- `sleep=prefer`は安全なら継続し、`require`は確認不能を成功扱いしない
- 睡眠のrespawn point変更を確認済み`effects`として返す
- maintenance前checkpoint、帰還後diffを必須にする

## Phase 6: one-shotと完了後安全化

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
- action/routineのserver-confirmed postcondition
- failure/reconcileで未完了を成功扱いしないこと
- Voice Chatのfail-closedとstate restore
- 対象Modpackの起動・接続・30分稼働
- crash/stop時にinputが残らないこと
