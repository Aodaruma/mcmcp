# アーキテクチャ

## 基本方針

LLMは「何をするか」を決め、Minecraftクライアント内の決定論的コードが「各tickでどう実行し、何を確認するか」を担当します。HTTPスレッドからMinecraft状態へ直接触れません。

```text
MCP client / LLM
      |
      | Streamable HTTP, 127.0.0.1
      v
MCP transport + schema validation
      |
      v
Command inbox (bounded)
      |
      | Minecraft client thread only
      v
Routine supervisor -----> Safety controller -----> Emergency stop
      |                         |
      +----> Observer           +----> Voice Chat adapter
      |        |
      |        +----> Session world memory
      v
Tick executor / Screen handler
      |
      v
Vanilla input and interaction path
      |
      v
Server-authoritative update -> Postcondition verifier
```

## MCP transport

- 公式MCP Java SDKのStreamable HTTP providerを利用し、protocol本体を独自実装しない
- PoCはJava SDK 2.0.0とMCP protocol 2025-11-25を固定して相互運用試験する
- 2026-07-28固有機能は、Java SDKと利用MCP clientが追従するまで前提にしない
- 単一`/mcp` endpointを`127.0.0.1`だけで提供する
- JSON-RPC messageはPOSTで受け、独立GET SSE streamを提供しない場合はGETへ405を返す
- server-initiated notificationへ依存せず、routine状態は`get_routine` pollingで取得する
- Origin、Bearer token、protocol header、request size、JSON depth、rateを検証する
- SDKとHTTP依存はshade + relocateし、他MODのJackson/Reactor/Servlet実装と衝突させない
- 固定portが使用中でも外部interfaceへfallbackせず、MCP起動を失敗させる

Streamable HTTPを使う理由は、MinecraftをMCP clientの子processとして起動せず、標準出力をMCP専用にできないためです。外部Python/Node sidecarは初版で使いません。

## Command inbox

- HTTP threadは認証、schema検証、deadline確認、queue投入までを行う
- queueは小さな上限を持ち、満杯なら`busy`で拒否する
- Minecraft状態の読み取り・変更はすべてclient threadへdispatchする
- 短時間tool callにはdeadlineを持たせ、期限後に遅延実行しない
- 長時間処理は`routine_id`を返してHTTP request寿命から分離する
- cancelとemergency stopは通常開始commandより先にclient tickで確認する

`emergency_stop`受理後はpending startを破棄し、全入力解放とlock完了後に成功を返します。

## Observerとworld memory

Observerは次を同一client tickで採取します。

- playerの体力、空腹、位置、向き、選択slot、interaction reach
- inventoryの通常表示情報とitem components
- crosshair target、hit face、hit position
- 現在観測可能なblockと全BlockState property
- 現在観測可能なEntityと通常画面で分かる状態
- world、screen、connection、Voice Chat、routine状態

現在観測と過去記憶を混ぜません。

- `current`: 今回の視認・interactionで確認
- `last_known`: 過去の視認、または自分の操作後にserver同期まで確認
- `unknown`: 根拠なし

loaded chunk、hidden block update、同期済みEntityという理由だけで公開しません。詳細は[観測・記憶モデル](observation-model.md)に定めます。

## Routine supervisor

- 同時に入力またはscreenを所有する能動routineは1つだけ
- routine開始時にworld session、work bounds、期限、local capability policyを固定する
- work boundsとlocal UIで承認済みのbed/safe anchor/transit corridorを、同じdimensionの固定execution envelopeとしてVALIDATING時に確定する
- block/entityの有限actionは`PRECHECK -> EXECUTE -> WAIT_SERVER_SYNC -> VERIFY`で動く
- `navigate_to`は別FSMの`PRECHECK -> EXECUTE -> MOVE -> SETTLE -> VERIFY`で動く。Phase 3 v1は回転固定のforward/back/strafeによる短い平坦路だけを扱い、positive ACKではなく入力停止後10 client tickの安定とposition/rotation/motion correction不在を`server-reconciled`の根拠にする
- 各input ownershipは最大2秒の内部leaseで、tick safetyに合格した場合だけ更新する
- 高水準の別作戦は自動選択しない
- 同じpostconditionへの有限retry、再照準、再接近、許可範囲内のrepathだけを行う。Phase 3のEntity interactionは再dispatchの重複を否定できないためretryしない
- postcondition確認済みの境界でのみcheckpointを進める
- checkpointは安定床上で全inputを解放でき、未確定action/screen操作がない境界に限る
- 再開はworldと目標状態を比較してreconcileし、rollbackしない

Phase 4の`apply_block_plan`はこのsupervisor上で動く7番目のroutineです（実装・受入完了）。1 callは外部で分割済みの1 phase・最大64 cellに限定し、`max_travel_blocks=0`として現在の安全な立ち位置から視点とhotbarだけを一時所有します。各cellの`expected_before / expected_after`はruntime registry上の完全なBlockStateで、`verify_only / break_to_air / place / replace`以外を受けません。offsetとstateはいずれもmirrorを先、Y軸の時計回りrotationを後に適用します。

preflight、各dispatch直前、prediction ACK後、最終確認はlast-known memoryを代用しないcurrent-onlyです。設置資材は開始時の現在client inventoryとeligible hotbarをbaselineとして不足を拒否し、各place後はfreshなinbound selected-slot inventory syncが届くまで次へ進みません。最後は全target cellを同じclient tickのcurrentな完全stateとして確認し、unknownが0の場合だけgoalを確認済みにします。

公開stateは`QUEUED / VALIDATING / RUNNING / WAITING / FINALIZING / SUCCEEDED / FAILED / CANCELLED`です。phaseとfailure schemaは[自動化runtimeと回復](automation-runtime.md)に定めます。

`WAITING`中もSafety controllerと期限監視を続けます。期限はwork用soft deadlineと、maintenance/FINALIZING reserveを含むhard deadlineに分け、soft deadlineではFINALIZINGへ移り、hard deadlineでは即時releaseします。非緊急failureは固定envelope内のsafe checkpointへbounded returnできますが、emergency stop、cancel、死亡、切断時は帰還を試みません。

外部MCP heartbeatは必須にしません。安全性はlocal unlock、routine deadline、内部action lease、毎tickのSafety controllerで担保します。

## Tick executor

- 通常のKeyMapping、attack、use、inventory click経路を使う
- player座標、速度、Entity motion、inventory、NBT、server同期値を直接書き換えない
- block/entity reach、LOS、cooldown、collision、採掘速度を回避しない
- `stop_all`は保持した入力とitem useを必ず解放し、複数回呼んでも安全にする
- 予期しない画面では停止する
- 破壊元は`minecraft:cobblestone / stone / dirt / obsidian / grass_block`の閉じた5 ID allowlistへ限定し、canonicalなvanilla registry entry、BlockEntityなし、空のFluidStateをadmissionとpacket直前の両方で確認する

低レベルのhold操作は内部に閉じ込めます。MCPへ公開するのは、期限、対象、postconditionを持つ意味的なroutineだけです。

block actionの確認範囲は要求したtarget cellです。通常のvanilla interactionに伴う隣接block更新、drop、音・particle等のgame eventを抑止せず、target外の全world stateが不変であることはpostconditionに含めません。

## Screen handler

クラフトとコンテナ操作のため、GUIを一律禁止にはしません。

- routineが開いたallowlist済みscreen/menuだけを所有できる
- screen class、menu/sync ID、slot revisionを各操作前後に確認する
- unexpected screen、manual input、slot desyncで入力を解放して失敗する
- inventoryを直接変更せず、通常click後のserver同期を確認する
- screen終了時はautomation ownershipだけを解放し、ユーザーが開いた画面を勝手に操作しない
- ユーザーが事前に開いたscreenを途中からadoptしない

## Survival maintenance

長時間作業中の食事と睡眠は、routineの安全なcheckpointでだけ割り込みます。

- 登録済み、または当該taskでserver-confirmedとなったfood/bedだけを使う
- bed-safe dimensionかを確認する
- maintenance復帰後にsnapshot/diffを再取得する
- maintenanceが開始時のexecution envelope、破壊、Entity権限を拡張しない

未知のbedを壁越しに探索せず、失敗は通常のroutine failureとしてLLMへ返します。

## Entity境界

Phase 3 v1の`interact_entity`は、current world session/dimensionで現在可視なopaque refが指すadult cowを、通常reach・LOS・crosshair内でmain-hand bucketにより1回だけ搾乳する操作に限定します。成功はdispatch後のfreshなselected-slot inventory syncと`minecraft:milk_bucket`の絶対目標countで確認し、retryしません。取引、餌やり、毛刈り、騎乗等の汎用interactionは、個別の観測可能なpostconditionを設計・検証する後続phaseまで公開しません。

万能な`transport_entity`、自動捕獲、押し込み、釣り竿pullは公開しません。

後期にEntity搬送を試す場合も、ユーザーが対象をboat/minecart/sealed cellへ収容し、routeとdestinationを封鎖した後の`operate_prepared_transfer`へ限定します。client側でEntity座標、AI、vehicle membershipを直接変更しません。

## 完了処理

domain goal確認後、routineは`FINALIZING`へ移ります。

1. goal postconditionを確認
2. 当該routineが作成・変更し、server-confirmed ownership recordを持つscreen、gate、temporary stateだけを片付ける
3. local policyに応じsafe anchorへ戻る、または安全停止する
4. 全入力を解放する
5. Voice Chatの状態を復元する
6. local policyが明示的に許す場合だけ通常切断する

中間routineの`completion_intent=continue_goal`ではroutine-local cleanup、stable checkpoint、全input解放、Voice Chat復元まで行い、safe anchor帰還、`ask`、切断は実行しません。ユーザーgoalを閉じる`finish_goal`だけがlocal `after_completion` policyを実行します。省略時は`finish_goal`で、`continue_goal`はlocal UIが許可したsessionの回数・総時間・unlock expiry上限内だけ受理します。

自動再接続と自動ログインは行いません。建築が完成しても必須finalizationに失敗した場合、`goal.verified=true`を保持しつつroutine全体は`FAILED`とします。

## ライフサイクル

1. 物理client専用entrypointで初期化
2. タイトル画面ではMCPを起動しても能動routineはlocked
3. world参加後に互換性、Voice Chat、安全設定を検査
4. ユーザーがlocal UI/keyでcurrent world sessionを明示unlock
5. routine開始時にVoice Chatをmuteし、成功を再確認
6. client tickで観測、操作、postcondition、安全条件を確認
7. 完了・失敗・cancel・実入力・切断の全経路で入力を解放
8. world退出時にmemoryを旧sessionとして切り離しauto-lock
9. client終了時にroutineを停止してMCP serverをgraceful shutdown

Local unlockはcurrent world sessionとユーザーが有効化したcapability profileへ束縛し、期限切れ、切断、dimension safety failure、emergency stopで自動lockします。MCPからunlockするtoolは作りません。

## 依存関係

Simple Voice Chatは任意依存とし、専用adapter以外から参照しません。対象packの既定policyでは、導入済みなのにmute状態を確認・変更できない場合、能動自動化をfail closedにします。未導入環境では音声経路がないものとして動作可能ですが、packごとのlocal policyで禁止できます。
