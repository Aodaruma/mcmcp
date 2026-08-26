# MCMCP NeoForge — Minecraft MCP Client MOD 設計・仕様書

- 文書版: 0.6
- 作成日: 2026-08-26
- 状態: MOD本体は実装着手可、接続先MCP hostのみ未選定
- 対象: Prism Launcher「くらふとぶ！-v01.2」
- 規範別紙: MCMCP_MCP_Tool_Catalog.json
- 試験別紙: MCMCP_Prism_互換試験ベースライン.json

## 0. 結論

本件は、次の構成で実現する。

- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25 向けのクライアント専用MOD
- 配布物は、Prism Launcherのmodsフォルダーへ入れる単一jar
- MCPサーバーはMODと同じMinecraft JVM内で起動
- MCP接続はMCP 2026-07-28準拠、127.0.0.1限定のStreamable HTTP
- サーバーコンパニオン、対Minecraftゲームサーバーcapability handshake、独自の対ゲームサーバー通信、外部MCP bridgeは作らない
- LLMは型付きAction DSLまたは同じDSLで書かれたtemplateを渡し、各primitiveはMOD内の決定論的ランタイムが実行
- サバイバルでは、player eyeから遮蔽されない全周visual、局所移動安全volume、実再生soundなど、明示した観測情報だけで判断する
- 全周visualはcameraを回さず取得し、position soundのevent IDとbest-effort entity hintをLLMへ公開する
- `face_known_position`の観測目的回数制限は設けず、一般のnode・時間・角速度・累積camera budgetだけを適用する
- Esc、Screen上のMCP操作OFF、world離脱を常にAgentより優先する
- chat、inventory、menuの表示とfocus喪失だけではActionを停止しない
- Agent実行中の物理キーボード・マウス入力は、EscとScreen上の状態ボタンを除きMinecraftへ渡さない

最初の実装は、既知地点への移動、既知地点への視点変更、有限待機を組み合わせるAction DSL v1から開始した。Phase 1の操作・回避・観測境界が成立したため、Phase 2の最初のprimitiveとして、宣言済みの可視面を持つoak / birch幹だけを通常attack入力で破壊する`break_known_face`を追加する。農業、建築、資源入手、レッドストーン用primitiveは引き続き1種類ずつ追加する。

## 1. 対象環境

ローカルのPrism Launcher設定を読み取り専用で確認した結果、ユーザー指定「くらふとぶ！」に対応する実在インスタンスは一意の「くらふとぶ！-v01.2」だった。本仕様ではこれを確定対象とする。

| 項目 | 固定値 |
|---|---|
| PrismインスタンスID | くらふとぶ！-v01.2 |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.59 |
| Java | Microsoft OpenJDK 25.0.1, 64-bit |
| LWJGL | 3.4.1 |
| 最大メモリ | 8196 MiB |
| 現在のMOD数 | 24 |
| MOD配置先 | C:\Users\aod\AppData\Roaming\PrismLauncher\instances\くらふとぶ！-v01.2\minecraft\mods |

NeoForge公式の26.2 MDKもJava 25を対象としている。開発は公式の26.2 ModDevGradle MDKを基礎にし、NeoForge依存版だけを対象インスタンスに合わせて26.2.0.59へ固定する。

複数Minecraft版、Fabric、Forge、複数ローダーを共通化する層は作らない。

## 2. 参照調査の整理

参照会話の重要点を、今回の確定要件へ合わせて整理すると次のとおり。

| 論点 | 採用する考え方 | 今回の変更 |
|---|---|---|
| LLMの役割 | 高位Goalを有限Action DSLへ構成・失敗後に再計画 | DSL契約を明文化 |
| 低位操作 | MOD内の状態機械・経路探索・反射制御 | 維持 |
| 観測境界 | LLMだけでなく経路探索にも禁止情報を渡さない | FOV限定から、遮蔽を守る全周visualへ変更 |
| 危険対応 | LLMを待たずClientTick単位で継続・再計画・緊急回避・停止を選別 | 一律停止を廃止 |
| 実プレイヤー | 別botではなく、現在のローカルプレイヤーを一時操作 | 維持 |
| MOD基盤 | FabricクライアントMOD | NeoForge 26.2へ変更 |
| MCP配置 | 外部TypeScript bridge | 削除し、MOD内Java実装へ統合 |
| サーバー連携 | companionと対ゲームサーバーcapability grant | 完全に削除 |
| マルチプレイ判断 | サーバーの明示許可 | ローカル設定とユーザー判断だけに変更 |

既存事例から利用するのは設計思想であり、そのまま移植はしない。

- mcpfabric: 実プレイヤーを通常のクライアント入力経路から操作する考え方
- mc_aiplayer: LLMのGoalと決定論的Taskを分離する考え方
- MCMCP: Minecraft JVM内にJDK標準HTTPサーバーとGsonでMCPを内蔵する先例

調査範囲では、Minecraft 26.2 / NeoForge、実プレイヤー操作、公平な観測境界、MOD内MCPを一体で満たす完成実装は確認できなかった。

## 3. 目的と非目的

### 3.1 目的

ユーザーがScreen buttonでMCP操作をONにしている間、ローカルMCPクライアントから受けた型付きAction DSLに従い、現在のプレイヤーを安全に代行操作する。

将来の製品Goalは次のとおり。

1. 既知地点への移動
2. 可視・既知の木の伐採と植林
3. 登録済み農地の収穫と再植え
4. 小規模Blueprintの建築
5. 通常探索による資源入手、クラフト、精錬
6. 制限されたレッドストーン回路の構築と試験

### 3.2 非目的

- サーバープラグインまたはserver companion
- Minecraftゲームサーバーに対するcapability request/grant/revocation
- NeoForge custom payloadなどの独自対サーバー通信
- 外部Node.js、TypeScript、Python、別Javaプロセス
- Mineflayerやfake playerなどの別bot
- 任意Java/JavaScript/Pythonコード実行
- execute_command、teleport、give、fill、packet spoofing
- 生チャンク走査、find_blocks、非露出鉱石探索
- アンチチート回避、公開サーバー規約の回避
- PvP、ランキング、競争経済での自動化
- 完全無事故、完全rollback、サーバールール適合の保証

「サーバー通信をしない」とは、MOD独自の確認・capability通信を追加しないという意味である。Minecraftクライアントが通常プレイに必要なVanilla通信を行うことまでは禁止しない。

## 4. 段階的な製品範囲

### Phase 1: Control & Navigation MVP

- MOD内MCP endpoint
- 認証、入力検証、固定長command queue
- Screen上のMCP操作ON/OFF、状態HUD、Esc緊急停止
- 1件だけのtask state machine
- Action DSL v1 validator/compiler（有限if、固定回数repeat、MVP primitiveのみ）
- survival_omnidirectional全周visual observation
- 半径4 blockのLocal Observation VolumeとVanilla一致の斜めswept-AABB判定
- LLMへ公開するposition sound / entity hint
- セッション内Known Traversability Map
- 観測済み地点への32 block以内の移動
- 危険度分類と、移動だけで完結する最小緊急回避
- 構造化status、cancel、失敗理由

### Phase 2: 伐採と農業

- 可視・既知の単純な木を伐採
- 苗木を回収できた場合の植林
- 手動登録済み農地の成熟作物を収穫
- 種を確保した上で全区画を再植え

### Phase 3: 小規模建築

- ローカルに登録した許可box
- 最大256変更のBlueprint
- Vanillaの移動・設置・破壊だけを使用
- 資材表、設置順、事後条件、変更履歴

### Phase 4: 探索・資源・クラフト

- frontier探索
- acquire_itemのGoal分解
- 採掘、クラフト、精錬、保管
- セッションを越える合法的な地点記憶

### Phase 5: レッドストーン

- 真理値表と入出力を持つRedstoneSpec
- 許可部品と最大footprint
- Blueprintへの変換
- 入力操作と出力観測による自動試験

各Phaseは前Phaseの受入条件を満たしてから着手する。DSLの構文と検証器はPhase 1で固定し、後続Phaseでは試験済みprimitiveだけをopcode allowlistへ追加する。任意コード実行や汎用スクリプト言語には拡張しない。

## 5. システム構成

~~~text
Local MCP Host / LLM
        |
        | Streamable HTTP
        | Authorization: Bearer ...
        v
127.0.0.1:8765/mcp
┌──────────────────────────────────────────┐
│ NeoForge Client MOD: MCMCP NeoForge      │
│                                          │
│  MCP Endpoint                            │
│    └─ auth / JSON-RPC / schema validation│
│                    |                     │
│                    v                     │
│  Bounded Command Queue                   │
│                    |                     │
│                    v                     │
│  ClientTick Agent Runtime                │
│    ├─ Action DSL Validator / Compiler     │
│    ├─ Task State Machine                 │
│    ├─ Omnidirectional Observer            │
│    ├─ Observation Frame Store             │
│    ├─ Local Observation Volume            │
│    ├─ Known Traversability Map + A*      │
│    ├─ Reflex Governor                    │
│    ├─ Input Arbiter                      │
│    └─ HUD / Audit Trace                  │
└────────────────────┬─────────────────────┘
                     |
                     | Vanilla client input/action path
                     v
              Minecraft Client
                     |
                     | 通常のMinecraft通信のみ
                     v
              Singleplayer / Server
~~~

サーバー側には本プロジェクトの構成要素を置かない。

### 5.1 実装単位

単一Gradle module、単一MOD jarとする。概念上のクラス責務は次のとおり。

| 責務 | 内容 |
|---|---|
| McmcpClient | クライアント専用初期化と終了処理 |
| McpEndpoint | HTTP、認証、JSON-RPC、MCP method routing |
| GameGateway | command queueとimmutable status snapshot |
| AgentRuntime | ClientTickごとの状態遷移 |
| ObservationPolicy | 許可された観測だけを生成 |
| OmnidirectionalObserver | player eye基準の全周visual rayとvisible entity filter |
| ObservationFrameStore | 安定frame、件数上限、opaque cursor、短期pagination |
| SoundClueStore | 実再生position soundの集約、entity hint、TTL管理 |
| ActionCompiler | JSON AST、権限、静的budgetを検証しprimitive列へ変換 |
| LocalObservationVolume | 半径4 blockの通過可能volumeとVanilla一致の斜め移動安全判定 |
| KnownTraversabilityMap | 出典と鮮度を持つ観測済み通行空間・支持面・遷移 |
| Navigator | KnownTraversabilityMap上の保守的A*と局所再計画 |
| InputArbiter | synthetic入力と物理入力の調停 |
| AgentOverlay | 右下icon、Screen状態button、短いEsc案内の表示 |

一実装しかないinterface、factory、plugin systemは作らない。NeoForge APIは対象版へ直接実装する。

### 5.2 スレッド境界

~~~text
HTTP worker
  ├─ auth / JSON / DSL構造検証
  ├─ bounded queueでclient snapshot取得
  ├─ immutable snapshotだけでpredicate / A* / cost planning
  ├─ bounded queueでcommit要求
  └─ JSON response送信成功後にdelivery confirm

NeoForge ClientTick
  ├─ admission snapshotを取得
  ├─ commit直前に現在状態を再検証
  ├─ ActionをUNCONFIRMEDで予約
  ├─ confirm済みActionだけを1 step進める
  ├─ 入力を反映する
  └─ immutable snapshotを公開
~~~

不変条件:

- HTTP workerはMinecraft APIへ直接触れない
- ClientTickはHTTPやLLMを待たない
- Minecraftのclient/world/player参照を別threadへ渡さない
- workerへ渡すのはsession、control epoch、pose、policy、observation frame、Known Traversability Map等のimmutable valueだけ
- workerの結果はclient threadでworld/session、control、pose、policy、観測依存edge、安全条件を再照合してから採用する

command queueはJDKのArrayBlockingQueueで固定長32件、公開snapshotはAtomicReferenceで保持する。満杯ならSERVER_BUSYを返し、無制限にメモリを消費しない。

HttpServerはlisten backlog 16、daemon worker 2 threadの固定executorで動かす。virtual threadや無制限executorは使わない。

`agent_start_action`はJSON/DSL構造をworkerで先に検証し、client threadへ固定長queueでimmutableな`AgentAdmissionSnapshot`の取得だけを依頼する。workerはそのcopy上でpredicate availability、capability、静的budget、既知経路、安全条件をpreflightし、合格結果だけをclient threadへcommitする。1つのabsolute call deadlineがcapture、planning、commitを通して有効で、既定2秒・設定上限30秒とする。planningはcancel/期限を各work単位で検査し、A*は1探索2,048・route expansion合計32,768、abstract pose 4,096、pose transition 16,384を上限とする。期限切れ・cancel・上限超過ではcommitせず入力を出さない。

commit時は同時Task、world/session、control epoch、READY、pose、predicate availability、capability、観測依存edge、local safety、設定、multiplayer policy、position-correction revisionを現在値で再検証する。合格時だけActionを1件予約し、同時2件目は`TASK_BUSY`とする。HTTP workerへMinecraftのclient/world/player参照は渡さない。

## 6. 操作権

### 6.1 優先順位

~~~text
実行中の物理Esc緊急停止
  > Screen上のMCP操作OFF
  > world/player lifecycle
  > Safety Governor
  > Task Runtime
  > Action DSL
~~~

### 6.2 状態

公開lease APIは作らない。Screen buttonのON操作を、時間制限のない1 Action限りの内部許可として扱う。MCP操作ON/OFFはAgentの変更操作を許可するgateであり、内蔵HTTP endpoint自体の起動・停止ではない。OFF中も`agent_get_state`と終了済みActionの参照は可能で、`agent_start_action`だけを拒否する。

~~~text
OFF
  └─ Screen右下のMCP操作ON
       v
READY（ON、時間制限なし、1 Action限り）
  ├─ valid agent_start_action → AGENT
  ├─ Esc → READYのまま、VanillaのEsc動作だけ実行
  └─ OFF / world変更 → OFF

AGENT
  ├─ 能動的危険 → RECOVERING
  ├─ Esc → 現在ActionをEMERGENCY_STOP、入力解除、READY
  └─ success / failure / cancel → OFF

RECOVERING
  ├─ Esc → 現在ActionをEMERGENCY_STOP、入力解除、READY
  └─ 安全化 / 回避不能 / recovery budget超過 → OFF

全状態
  └─ OFF / world・player消失 → OFF
~~~

Action終了後は原則OFFへ戻す。唯一、AGENT/RECOVERING中の物理Escによる緊急停止だけは、現在Actionを終了した後に同じworld・capabilityのREADYへ戻す。複数stepは1つのDSL programにまとめられ、古いMCP接続がActionを連続投入することはできない。

### 6.3 即時停止条件

- AGENTまたはRECOVERING中の物理Esc押下
- Screen右下のMCP操作OFF
- `agent_cancel_action`
- world unload、dimension変更、respawn、死亡、server disconnect
- player、levelまたは入力所有権の不変条件消失
- 未処理例外、内部状態破損

停止時は同じClientTickまでにAgent所有のsynthetic入力をすべて解除し、pending commandを破棄し、control epochを進めて古いqueue entryを無効化する。実行中EscはActionを`EMERGENCY_STOP`で終了してREADYへ戻し、その後にVanillaのEsc動作も通すため、ゲーム中ならpause menuを開き、Screen上なら通常どおり閉じられる。READY中のEscはMCMCPの停止処理を起こさず、Vanillaへそのまま渡す。

次は、それ自体では停止条件にしない。

- chat、menu、inventoryなどのScreen表示
- ゲーム画面のfocus喪失
- Esc以外の物理キーボードまたはマウス入力
- healthが固定閾値を下回ったことだけ
- 経路上の支持面または地形が変わったことだけ
- 通常Actionのbudget超過時に、現在進行中の危険が存在する場合

health、支持面、流体、被攻撃などは第10章の危険度判定へ渡し、継続、再計画、緊急回避、停止を分ける。通常Actionのbudgetが尽きても既知の能動的危険が続く場合は、Goal実行だけを止め、固定のrecovery budget内で安全化を優先する。

### 6.4 物理入力の隔離

OFFとREADYではMinecraft本来の入力を変更しない。AGENTとRECOVERINGでは、Minecraftウィンドウへ届いた物理キーボード・マウス入力を次の2種類だけ例外として受理し、それ以外はVanillaのgameplay、camera、Screen widgetへ渡さない。

1. Esc
2. MCMCP状態ボタンのhit box内に対する左クリック

Alt+TabなどOSが処理する操作や他アプリの入力は対象外である。物理入力を検出しただけでActionを停止する仕様は廃止する。これにより、chatを開いたままfocusを外してもActionは継続でき、誤った移動、視点、attack、use、inventory操作は混入しない。

実装はNeoForge 26.2の`InputEvent.MouseButton.Pre`、`MouseScrollingEvent`、`InteractionKeyMappingTriggered`、`ScreenEvent`、`MovementInputUpdateEvent`を優先する。`InputEvent.Key`は26.2でcancellableではないため、Client testで完全隔離できないことを確認した場合は、`KeyboardHandler#keyPress`の入口だけに狭いcancellable Mixinを置く。広範なMixin、Access Transformer、OS global hookは使わない。物理mouse turnはAGENT中だけ感度を0へ置き換え、synthetic camera rotationは別の所有経路から適用する。

Agent所有の移動は物理`KeyMapping#setDown`として注入せず、各non-paused ClientTickでVanillaの物理入力収集後に、`MovementInputUpdateEvent`から最終movement inputへ`AgentInputState`を適用する。cameraはgame thread上の専用ownerから角速度制限付きdeltaを適用する。これにより、非pauseのchat、inventory、multiplayer pause menuが物理key stateをreleaseしてもAgent入力は失われない。対象版26.2.0.59でevent後に入力が上書きされる場合に限り、最終input更新点1か所へのcancellable Mixinへ置き換え、両経路を併存させない。

Vanillaがsingleplayerを実際にpauseしている間はActionをcancelせず、synthetic入力をupにして実行tickと`max_duration_ms`の計測を凍結する。simulation再開後、world revisionと安全条件を再検証してから続ける。multiplayerなどsimulationが継続しているScreenでは通常どおりAgentを進める。

### 6.5 HUDとScreen操作

ゲーム中、右下には16 × 16 pxの非interactiveな状態iconだけを表示する。文字列、座標、token、常設panelは表示しない。ON/OFF操作はScreen表示中のbuttonだけで行う。状態は色だけに依存せず、輪郭も変える。

| 状態 | icon表現 | 意味 |
|---|---|---|
| OFF | 灰色の空円 | MCP変更操作を拒否 |
| READY | 橙色の時計 | ON、Action待機中 |
| AGENT | 青色の矢印 | DSL実行中 |
| RECOVERING | 紫色の盾 | 緊急回避中 |
| FAULT | 赤色の感嘆符 | endpointまたは内部異常 |

`FAULT`は6.2のcontrol stateではなく、`OFF`へ重ねて表示するUI専用presentation stateである。endpoint bind、token初期化、または内部不変条件の異常時に優先表示し、実行中Actionがあれば`INTERNAL_ERROR`で終了して入力を解除する。Screen buttonは`MCP操作: FAULT`とし、local error codeはtooltipへ表示する。world参加やONクリックだけでは解除せず、安全なendpoint再初期化に成功するかclientを再起動した場合だけ通常のOFF表示へ戻す。endpointが応答可能なら`agent_get_state.control.mode`は`off`、直前Actionの`end_reason`は`INTERNAL_ERROR`を返す。

AGENT開始時だけ3秒間、「自動操作中 — Escで緊急停止」という短いoverlay noticeを出し、その後はiconだけに戻す。

AGENTまたはRECOVERING中は、gameplayとScreenの双方でゲーム画面の外縁へ2 pxの黄色枠を常時表示する。READY、OFF、FAULTでは表示しない。

chat、inventory、pause menuを含む任意の`Screen`表示中は、「icon + 状態文 + MCP操作 ON/OFF」の1 buttonを追加する。原則は右下だが、chatでは入力欄と候補一覧を塞がないよう右上へ配置する。

- OFFでworldとplayerが有効: `MCP操作: OFF`
- READY: `MCP操作: ON / 待機中`
- AGENT: `MCP操作: ON / 実行中`
- RECOVERING: `MCP操作: ON / 緊急回避中`
- FAULT: `MCP操作: FAULT`
- worldなし、死亡画面、接続中: 状態を表示するがONはdisabled

クリック時の操作説明とFAULTのlocal error codeはtooltipへ置き、button本文を状態だけにする。button幅は全状態の最長文ではなく、現在の状態文にiconとpaddingを加えた幅へ毎frame追従して画面占有を抑える。OFFクリックはActionを`USER_DISABLED`で終了し、入力を解除してOFFへ戻す。ボタン自身のclickだけは6.4の入力隔離を通過する。buttonはnarration textとkeyboard focusを持つが、AGENT中のkeyboard activationはEsc以外を遮断するためmouse click専用である。

ゲームHUDは`RegisterGuiLayersEvent`、Screen buttonは`ScreenEvent.Init.Post`で追加し、既存Screen classを置換しない。`Minecraft.screen == null`のframeだけgameplay iconを描画し、Screen表示中はHUD側iconを描かず、button内のiconと状態文へ置き換える。右marginと下margin（chatでは上margin）は既定8 px、既存MODと重なる場合のためoffsetだけをclient configで変更可能にする。

## 7. 観測境界

### 7.1 survival_omnidirectionalの原則

行動は、ユーザーGoal、有効なローカルpolicy、許可済み観測履歴だけから決まらなければならない。「クライアントへ同期済み」と「本policyで利用可能」は同義ではない。

~~~text
Action(t) =
  f(user_goal, authorized_observation_history(t), local_policy)
~~~

本profileは、現在のplayer eye位置から身体を動かさず見回せば得られる全周情報を、camera yaw/pitchを変更せず提供する。現在画面のFOVには限定しないが、opaqueな遮蔽面の裏、任意chunk、遠隔座標は公開しない。禁止情報はMCP resultから隠すだけでなく、NavigatorとTask Runtimeにも渡さない。

### 7.2 許可する証拠

次の情報だけをdimension付きworld XYZ、観測tick、world revision、provenance、鮮度とともに記録する。

- 自playerの位置、向き、velocity、AABB、health、absorption、hunger、air、fire、fluid、status effect
- 自inventory、equipment、現在選択slot
- 第7.3節の全周visual observationで確認したsurfaceとentity
- Vanilla crosshair hit result
- 第7.4節のLocal Observation Volumeで得たsupport、clearance、transition、fluid、hazard
- 接触、衝突、実移動、採掘、設置、item useの結果
- 実際に再生開始したposition soundと第7.5節のentity hint
- damage eventで通知された原因、直接原因、発生XYZ
- ユーザーが明示入力した地点、農地、建築box
- 同じworld session内で上記から得た記憶

`client_tick`と`world_revision`はworld session内の単調増加longとして`agent_get_state.world`にも公開する。revisionはblock state更新、chunk load/unload、world境界変更の処理後に増やし、entityの通常移動やsound再生だけでは増やさない。world unload、respawn、dimension変更では観測・Mapを消去した上で新sessionを0から開始する。LLMとruntimeはrecordのtick/revisionを現在値と比較して鮮度を判断する。

### 7.3 Omnidirectional Visual Observation

観測原点はthird-person cameraではなくplayer eye positionとし、水平360度・上下180度をworld軸固定のdeterministic equal-area ray集合で観測する。方向集合は2,048方向へ固定し、既定は1 ClientTickあたり256方向、8 active tickで1 frameを更新する。ray/tickは64〜512のlocal performance設定内で調整でき、frame所要tickは`ceil(2048 / rays_per_tick)`となる。半径は`min(configured radius, 32 block, current fog distance, loaded boundary)`で、`sampling_coverage=1.0`は予定した全方向を更新済みという意味であり、連続球面の完全走査を意味しない。

frameは複数tickのtemporal compositeであり、単一時刻・単一原点の球面snapshotとは表現しない。各visual recordへ、そのrayまたはentity line-of-sightを採った時点の`eye_origin`、`observed_tick`、`world_revision`を付ける。Local Observation Volume由来recordには`observer_position`、sound clueには最終観測時の`world_revision`を付ける。frame responseの`frame_completed_tick`から各recordの鮮度を計算できるようにする。

観測はcamera yaw/pitch、FOV、Screen、focusに依存せず、Minecraft入力やcamera回転量を一切発生させない。背後でもeye位置から見通せるsurface/entityは取得できるが、opaque wallの裏は`UNKNOWN`のままとする。

rayは次を別channelで評価する。

| channel | 用途 |
|---|---|
| OUTLINE | interaction対象面 |
| VISUAL | 視覚遮蔽、visible surface、透過後の有限segment |
| COLLIDER | Local Observation Volumeの衝突形状。visual判定の代用にはしない |

glassのように視覚を通すが衝突するblock、slab、stairs、fence、trapdoor、snow、waterlogged blockを`air/solid`二値へ潰さない。visible surfaceには視覚で判別可能なblock ID、位置、面、shape classを記録する。透過面はその面を記録してrayを継続するが、custom renderer、alpha semantics、未ロード境界、shape内部開始、例外は`UNKNOWN`とする。

近傍entityは半径内のbounded query後、eye位置からAABBの複数sample点へのVISUAL line-of-sightでfilterする。1点以上が遮蔽されていなければ正確なEntityType、AABB、XYZ、velocityをvisible recordへ出せる。UUID、NBT、inventory、AI target、壁裏entityは公開しない。sparse rayが小さいentityを偶然外すことは、このentity専用line-of-sightで補う。

ray結果は`HIT / MISS / UNKNOWN`の三値とする。`MISS`が証明するのは検証済み有限segmentだけで、周辺cell、曲がり角、終端の裏側を既知にしない。

### 7.4 Local Observation Volumeと斜めswept-AABB

全周visualとは別に、運動安全用としてcurrent player AABBを中心とするEuclidean半径4 block、最大6 transitionのLocal Observation Volumeを毎tick維持する。player AABBが実際に通過可能な隣接transitionだけを展開し、solid、閉じたdoor、通れない隙間、unloaded、`UNKNOWN`で展開を止める。広域air flood-fillにはしない。

斜め移動では、current AABBと`AABB.move(intendedDelta)`を包む直方体に触れたblockをすべて衝突扱いしてはいけない。その包絡AABBは候補VoxelShapeを集めるbroad phaseだけに使い、矩形の角にあるが実際の移動軌跡と交差しないblockは除外する。

collision解決の規範値はVanilla 26.2のresolverが返す`resolvedDelta`とする。実装は`Entity#move`内の`collide(Vec3)`呼出を、bot制御中のlocal playerかつ対象版のplayer movement用`MoverType`だけ狭いMixinExtras `@WrapOperation`で包み、`intendedDelta`と`resolvedDelta`を同じgame threadで記録する。`resolvedDelta`は衝突解決済み候補であり、許可後の実移動証拠にはtick前後のplayer位置差分を使う。独自のpoint ray、単純な対角直線、固定substepをsolid collisionの真値にしない。これにより斜めのaxis解決、corner slide、world border、entity collision、step-up、slab、stairs、fence等をVanilla結果へ一致させる。MixinExtrasはNeoForge同梱分を使い、新しいruntime dependencyを追加しない。

hypothetical transitionは同じVoxelShapeとVanillaのaxis順で保守的に評価し、実移動または接触で確認するまで`PROBE_ALLOWED`を越えて昇格させない。axis順は`intendedDelta`について`|x| < |z|`ならY→Z→X、それ以外はY→X→Zとし、`|x| = |z|`はXを先にして決定的にする。各axis segmentの長さには`resolvedDelta`の対応成分を使い、そのsegment開始AABBを`expandTowards(axisDelta)`した領域だけを実通過領域へ加える。最後に全segmentを1個の包絡箱へ潰さない。

斜めpathのfluidは各axis segmentのswept player AABBと`FluidState#getAABB`相当の実高さ・形状との交差で接触を判定し、接触した`FluidState#getFluidType()`で危険度を分類する。包絡矩形の未通過cornerにあるfluidは接触扱いせず、途中segmentで触れたfluidはendpointが乾いていても記録する。未知のmodded FluidTypeは`UNKNOWN → REPLAN`とし、一律STOPにしない。非流体blockのinside判定は別にblock側のinside collision shapeとVanilla通過結果を使う。

supportは最終AABB直下1e-6 blockの薄いslabを`findSupportingBlock`へ渡し、交差する支持blockが存在するかだけを確認する。返る代表BlockPosは移動前playerとの距離で選ばれ得るため、予測supportのidentityや面積としてMapへ保存しない。supportがなければ下方向collisionから実落差を求める。通常歩行の許容落差を越える場合はAgent由来成分だけをneutralにしてREPLANし、重力、knockback、piston等の外力をゼロにしない。step-up候補は`maxUpStep`と頭上clearanceを含むVanilla結果へ従う。複数tick先を一括simulationせず、1回のVanilla moveごとに再観測する。

各movement heartbeat直前には、現在の実AABB、camera yaw、発行予定keyからworld座標系の正規化deltaを再構成して早期検証する。最終gateは`Entity.move(SELF, intendedDelta)`の`maybeBackOffFromEdge`直後・private `collide(Vec3)`直前に置き、慣性、knockback、jump、step-upを含むVanillaの実deltaを同じresolverでpreviewして、残distance budgetと局所安全条件を再検証する。proofはplayer identity、level identity、world revision、1 player tickへ束縛し、直前のreconciliation revisionが変化していれば入力を拒否する。実移動traceにも発行時revisionを保持し、次の観測までにrevisionが変わったtraceは`CONTACT`へ昇格させない。

Agent由来の加速・jumpはtick間で別台帳へ保持し、Vanillaのcollision、stuck reset、block speed factor、ground friction、air dragと同じ変換だけをその成分へ適用する。serverのvelocity全置換packetでは旧成分を破棄し、explosion等の加算外力では保持する。未証明、再計画、primitive完了、cancel、OFF、Escでは、その時点で追跡できるAgent成分だけを実velocityから差し引き、外力は残す。通常navigationが水、溶岩、騎乗、elytra、creative flightへ入った場合、またはcollision endpointの支持blockがbounce restitutionを持つ場合は、未実装のfluid/flying/restitution変換を推測せず移動前にneutralizeしてREPLANする。整数NavCell中心や経路corridorだけを、斜め入力の許可根拠にはしない。

Volumeから外へ出せるのはsupport、clearance、transition、fluid、suffocation、hazard、loaded/unknownの派生値だけである。raw block ID、ore、container、block entity、構造名は捨てる。候補VoxelShapeを集める包絡broad phaseにcellが入っただけでは、BLOCKED、fluid接触、support、HAZARDへ昇格させない。

### 7.5 position soundとentity hint

`PlaySoundEvent`ではなく、実際の再生開始を示す`PlaySoundSourceEvent`または`PlayStreamingSourceEvent`のposition soundだけを採用する。raw `sound_event`、category、dimension、絶対XYZ、first/last observed tick、age、occurrences、provenanceに加え、event IDから熟練者相当の`entity_hint`をbest-effortで生成し、LLMへ公開する。

Vanillaの`minecraft:entity.<candidate>.*`は、candidateがclientのEntityType registryに存在すればそのresource locationを`entity_hint`へ入れる。parrot imitationなら発音主体である`minecraft:parrot`、generic/shared/unmappedならnullとする。modded eventも同じ命名規約でregistry照合できる場合だけhintを付ける。raw `sound_event`は変換せず併記するため、LLMは`parrot.imitate.zombie`等の追加意味を自力で判断できる。

| sound_event例 | entity_hint |
|---|---|
| minecraft:entity.zombie.ambient | minecraft:zombie |
| minecraft:entity.skeleton.step | minecraft:skeleton |
| minecraft:entity.creeper.primed | minecraft:creeper |
| minecraft:entity.parrot.imitate.zombie | minecraft:parrot |
| minecraft:entity.generic.explode | null |

clueは最大32件、最終観測から600 active ClientTick保持する。同じdimension/event/categoryで、10 tick以内かつEuclidean距離2 block以内の音は1件へ集約する。複数候補があれば距離が最短、`last_observed_tick`が最新、作成順が最古の順で1件を決め、`occurrences`と最新XYZ/tickを更新する。`occurrences`は音の再生回数でありentity数ではない。上限超過で未期限切れclueを捨てた場合、捨てたclueが本来600 tickで失効する時点までframe summaryの`recent_sound_clues_truncated`をtrueにする。world unload、respawn、dimension変更で全消去する。immutable frame内の`age_ticks`はframe完成tickで固定し、page取得時刻では増加させない。

追加の推定評価fieldは公開しない。`entity_hint`はevent IDの正規化補助であり、実entityの存在証明ではない。`/playsound`やMODでも同じeventを再生できるため、実在・個体数・現在位置の判断はraw ID、鮮度、視覚、damage等を合わせてLLMが行う。

LLMはraw event ID、entity hint、鮮度を視覚・damage情報と合わせて判断できる。ただしsound単独では、entity UUID、個体数、現在位置、通路、洞窟、block、支持面を確定せず、Known Traversability Map、既知target、Local Observation Volumeを更新しない。`navigate_to_sound`や`attack_sound`は作らず、soundだけでMODが移動、攻撃、RECOVER、STOPを開始しない。音源XYZを通常Actionへ使う場合も、別の証拠で既知になったtarget/pathが必要である。Phase 1 DSL predicateにはsoundを追加しない。

relative sound、UI、music、非減衰音、実際に再生開始しなかった音にはworld XYZ clueを作らない。subtitle本文、raw audio、resource-pack file pathも公開しない。

### 7.6 信頼しない文字列

scoreboard、chat、看板、本を禁止した理由は、worldまたはserver由来文字列をLLMへの命令、DSL、policy変更、権限付与として解釈しないためである。表示そのものを改変する意味ではない。将来読むGoalを追加する場合も、origin付き非権威dataとして返し、次を一切認めない。

- 文章を制御命令へ昇格
- JSON、URL、座標文字列を自動実行
- MCP操作ON、multiplayer allowlist、budget、capabilityの変更
- bearer tokenやlocal fileの要求

初版のMCP resultとaudit logにはこれらの本文を含めない。resource locationであるsound event IDもdataとしてのみ扱う。

### 7.7 禁止する情報とAPI

- 全周visual半径とLocal Observation Volume外のloaded chunk走査
- 任意座標への`getBlockState`を使った探索
- opaque遮蔽面の裏にあるraw block identity、鉱石、洞窟、構造物
- 全周line-of-sightもsound/damage/contactもないwall越しentity
- seedから導出した座標、structure locator、全world block/entity検索
- sound clueからの地形、path、現在entity生成
- server由来文字列による権限変更

次のAPI概念は公開・内部ともに作らない。

~~~text
find_blocks(...)
navigate_to(unseen_resource_coordinate)
scan_chunk(...)
get_blocks_in_area(...)
navigate_to_sound(...)
attack_sound(...)
execute_command(...)
~~~

Phase 1で許可するのは`navigate_to_known(location)`だけである。`explore_frontier`、`mine_visible_face`、`search_known_memory`はPhase 4のinternal planner conceptとして追加可否を再検討し、MCP Toolとして直接公開しない。

### 7.8 Known Traversability Map

各cell/edgeへsupport、clearance、transition、fluid/hazard、semantic face、provenance、observed tick、world revisionを別々に保持する。provenanceは次を区別する。

- `OMNIDIRECTIONAL_VISUAL`: rayが通過した有限segmentとvisible face
- `LOCAL_VOLUME`: 半径4 block内で検証したsupport、clearance、transition、fluid
- `CONTACT`: 実衝突、実移動、成功したinteraction
- `SOUND`: Map更新禁止

状態は次の4値とする。

| 状態 | 意味 | 使用可否 |
|---|---|---|
| CONFIRMED | support、clearance、transitionが有効 | 通常経路に使用 |
| PROBE_ALLOWED | supportは確認済みだがtransitionの一部が未確定 | 低速の1 micro-stepだけ許可し、actual resolverで再検証 |
| BLOCKED | actual collision、危険流体、支持不能を確認 | 使用禁止 |
| STALE | revisionまたは鮮度が失効 | 再観測まで通常使用禁止 |

全周visual rayだけでplayer AABB全体のclearanceを確定しない。`CONFIRMED` transitionには`LOCAL_VOLUME`または`CONTACT`証拠を必要とする。地形変更では影響cell/edgeだけをSTALEにし、現在AABBが危険でなければ停止せず局所再計画する。Mapはworld session内のメモリだけに保持する。

### 7.9 限界

全周観測はcameraを回さず、人間が同じ位置で見回せば得られる情報を短時間でまとめて取得する意図的なassistである。2048 sampleは連続球面の完全走査ではなく、透明・custom renderingにも`UNKNOWN`が残る。client MODは同期済みchunkを技術的には読めるため、Policyとnon-interference testで不使用を検証するが、悪意ある改変に対する外部証明はできない。

Local Observation Volume外の未知危険、opaque wall裏、未ロード領域は事前に分からないため、完全無事故は保証しない。

## 8. MCP endpoint

### 8.1 transport

- Endpoint: http://127.0.0.1:8765/mcp
- Transport: Streamable HTTP
- 対応基準: MCP 2026-07-28
- 通信形式: stateless / POST-only / JSON response
- 同時に実行できるTask: 1件
- stdio: 非対応
- GET、protocol session、長時間SSE: 非対応
- LAN bind、0.0.0.0: 非対応

Minecraftはすでに起動しているプロセスなので、MCP clientがsubprocessを起動するstdioは適さない。Streamable HTTPを使う。

MCP 2026-07-28は各requestが自己完結するstateless仕様であり、GET streamとprotocol-level sessionを廃止している。MVPはTools server profileだけを使い、各POSTへ単一JSON responseを返す。

JDK 25標準のHttpServerとMinecraft同梱Gsonでclean-room実装する。Spring、Node、Servlet containerは追加しない。公式MCP Java SDK 2.0.1はMCP 2025-11-25世代で、現行stateless仕様へ未対応のため採用しない。SDK 3.x以降が現行仕様へ対応した時点でのみ再評価する。

### 8.2 security

- 初回起動時に256-bit bearer tokenをSecureRandomで生成
- Authorization headerを全MCP requestで必須化
- tokenはconstant-time比較
- tokenをURL、chat、log、MCP resultへ出さない
- Origin headerなしは許可し、Originがあるrequestはすべて403（初版はbrowser client非対応）
- Host headerをlocalhost / 127.0.0.1に制限
- CORSを許可しない
- request body上限64 KiB
- JSON depth、文字列長、配列長、数値範囲を制限
- 全Tool callをtoken bucketで20 request/秒、burst 40件に制限し、超過時は429とRetry-Afterを返す
- endpoint I/O timeoutを設定
- 固定portが使用中ならMinecraftをcrashさせず、MCPだけ無効化してHUDへ表示

MCP仕様も、ローカルHTTP serverについてOrigin検証、localhost限定bind、認証を求めている。

### 8.3 lifecycle

- MODのphysical client初期化でHTTP serverを起動
- world未参加時もserver/discover、tools/list、tools/call(name=agent_get_state)は応答
- worldがなければ操作系callはNO_WORLD
- client終了時にserverとexecutorをclose
- endpoint例外はgame threadへ伝播させない

クラウド上だけで動くMCP clientは127.0.0.1へ接続できない。利用には、同じPCで動作しStreamable HTTPへ接続できるMCP hostが必要である。外部tunnelやbridgeは今回の除外対象とする。

### 8.4 request contract

すべてのPOSTで次を検証する。

- Content-Typeがapplication/json
- bodyが単一のJSON-RPC 2.0 request objectであり、batch配列やresponseではない
- MCP-Protocol-Version headerが2026-07-28
- body内のio.modelcontextprotocol/protocolVersionが2026-07-28
- headerとbodyのversionが一致
- Mcp-Method headerとJSON-RPC methodが一致
- tools/callではMcp-Name headerとparams.nameが一致
- header名はcase-insensitive、method/name値はcase-sensitiveとして比較
- Mcp-Nameが`=?base64?...?=`形式ならUTF-8へdecodeしてからbodyと比較
- params._metaへclientCapabilitiesが存在
- clientInfoは任意。存在する場合だけnameとversionを検証

不一致は400とHeaderMismatch、非対応versionは400とUnsupportedProtocolVersionError、未知methodは404とJSON-RPC -32601を返す。

成功する全JSON-RPC responseのresult._metaへ`io.modelcontextprotocol/serverInfo`（name=`mcmcp`、version=`0.1.0`）を付ける。HTTP JSON responseのContent-Typeは`application/json`、文字encodingはUTF-8とする。

MVPで実装するmethod:

- server/discover
- tools/list
- tools/call

server/discoverはMCP 2026-07-28で必須である。これはMOD自身のMCP version、Tools、identityをMCP clientへ返す処理であり、Minecraftゲームサーバーへcapability確認を送るものではない。独自対ゲームサーバー通信は発生しない。

discover resultのcapabilitiesはToolsだけとする。

~~~json
{
  "resultType": "complete",
  "supportedVersions": ["2026-07-28"],
  "capabilities": {"tools": {"listChanged": false}},
  "_meta": {
    "io.modelcontextprotocol/serverInfo": {
      "name": "mcmcp",
      "version": "0.1.0"
    }
  },
  "ttlMs": 0,
  "cacheScope": "private"
}
~~~

Resources、Prompts、Tasks extension、Subscriptions、Sampling、Elicitation、SSE、Progressは実装しない。

### 8.5 Tools

| Tool | 変更 | 内容 |
|---|---:|---|
| agent_get_state | No | player、inventory集計、policy、DSL capability、Agent状態、最新観測frame概要を取得 |
| agent_get_observation | No | 最新の全周visual、局所traversability、hazard、sound clueをframe単位でpage取得 |
| agent_start_action | Yes | READY状態で検証済みAction DSL v1を1件開始 |
| agent_get_action | No | Action、現在node、resource counter、回避、失敗、traceを取得 |
| agent_cancel_action | Yes | actionを冪等にcancel |

raw key、raw mouse、packet、任意commandを操作するToolは公開しない。`agent_start_action`がLLM生成DSLの検証・実行口を兼ねるため、template専用ToolやDSL実行Toolを追加しない。`agent_get_observation`は読み取り専用で、OFF中も使用できる。

`tools/list`は上表の順序で固定し、各ToolへJSON Schema 2020-12のinputSchemaとoutputSchemaを付ける。単一pageの`resultType: "complete"`として返し、必須cache hintは`ttlMs: 0`、`cacheScope: "private"`とする。Tool一覧は実行中に変えず、`listChanged`はfalseとする。

Toolの規範的なname、description、inputSchema、outputSchemaは別紙`MCMCP_MCP_Tool_Catalog.json`とする。Java実装、`tools/list`、schema unit testは同じcatalog内容から生成または照合し、別々の手書き定義を持たない。

全Toolの成功応答は`resultType: "complete"`、`isError: false`、outputSchemaに一致する`structuredContent`を返す。互換性のため、同じJSONを直列化したTextContentも`content`へ1件入れる。業務上の拒否や実行失敗は`isError: true`とし、success用outputSchemaとの混同を避けるため`structuredContent`を付けず、`content`へ`code`、`message`、`recoverable`を持つJSON文字列を返す。未知Toolや壊れたrequestはJSON-RPC errorとする。以下の応答例は成功時`structuredContent`の中身を示す。

#### 8.5.1 Observation frame

`agent_get_state.observation`は、大量の観測recordそのものではなく、`latest_frame_id`、設定観測半径、全方位対応、oldest/newest tick、`sampling_coverage=1`、kind別件数、sound切り捨て有無だけを返す。方向ごとの実効終端は`unknown_boundary`で示し、単一の実効半径へ丸めない。world未参加時と最初の完成frame生成前はnullとする。

全周visualは既定8 active ClientTick、設定変更時は`ceil(2048 / rays_per_tick)` tickで1 immutable frameを完成させる。完成前のframeを公開せず、rolling保持は最新2 frameとする。`cursor=null`の初回pageが続きpageを必要とする場合だけ、そのframeをpagination leaseへpinする。leaseは同時最大2件、最終accessから60秒、初回accessから最大5分で失効し、時間は`System.nanoTime`で測る。これによりrolling更新中もLLMが同じframeを最後まで読め、保持量は最新2件とpin 2件までに限定される。上限中の3件目は`SERVER_BUSY`、pinされていない保持外IDは`FRAME_EXPIRED`を返す。world unload、respawn、dimension変更で全frame、lease、cursorを破棄する。

`agent_get_observation`入力:

- `frame_id`: `agent_get_state`が返したID
- `kinds`: `visible_surface / visible_entity / traversability / hazard / unknown_boundary / sound_clue`の1〜6種
- `cursor`: 初回null、続きは直前の`next_cursor`
- `limit`: 1〜256件

返却recordは第7章の許可条件に従い、responseには`frame_completed_tick`を含める。traversabilityは単一cell座標でなく`from / to` edge、target support、transition clearance、fluidとして返し、斜めtransitionも曖昧にしない。cursorは`SecureRandom`で生成した128 bit以上のopaqueなBase64URL tokenとし、server-side lease内のframe、kind集合、offsetへ束縛する。任意center、任意radius、任意chunk、任意entity IDをqueryする入力は設けない。壊れた・未知・期限切れcursor、別frame/kindへの使い回しは`INVALID_CURSOR`とする。同じ有効cursorの再送は同じpageを返し、失われたHTTP responseを再試行できる。`next_cursor=null`でpage終了である。

全周観測はcamera yaw/pitch、入力、Action camera budgetを変更しない。LLMが明示的に`face_known_position`を使うことは妨げず、その回数は通常のAST、実行node、時間、camera累積budgetだけで制限する。

#### 8.5.2 Action、program、primitive

- Action: `agent_start_action`で作られる1回の実行instance
- program: LLMが生成できる型付きJSON AST
- primitive: MODが決定論的に実行する有限のsemantic opcode
- template: 同じDSLで記述した検証済みprogram例。特権や別実行器を持たない

固定の高位Actionだけを選ぶ方式にはしない。LLMは許可済みprimitive、有限`if`、固定回数`repeat`を組み合わせられる。ただし、LLMが未知のopcodeを発明して実行することはできない。新しいMinecraft能力は、MOD側にprimitive、結果検証、安全試験を追加し、catalogのopcode allowlistへ載せたreleaseから使用可能になる。

Action DSL v1の制御構造:

- program bodyは順次実行
- `if`はnodeへ入った時点のpolicy-filtered `AgentSnapshot`を1回評価
- `repeat`はJSON内の固定`count`だけを使用し、1〜16回
- primitive失敗はAction全体を失敗
- while、until、再帰呼出し、並列実行、変数、任意式、catch、finally、on_cancelはなし
- Safety Governor、Esc、OFF、cancelをDSLから捕捉・無効化できない

現在許可するprimitive:

| opcode | capability | 内容 |
|---|---|---|
| navigate_to_known | movement | Known Traversability Map上の地点へ移動 |
| face_known_position | camera | 既知座標へ角速度制限付きで向く |
| wait_ticks | なし | 1〜200 active tick待機 |
| break_known_face | camera, block_break | 宣言した可視・既知のoak / birch幹1個を、指定したVanilla axeで通常入力から破壊 |

`break_known_face`の`tool_item`はhotbar内の該当axeを決定論的に選択する契約であり、任意slot操作を公開しない。後続Phaseでは`select_item`、`use_known_face`、`place_on_known_face`などを必要性と安全試験が成立した時だけ個別に追加する。raw attack/useや任意座標操作へ一般化しない。

predicateは次のpolicy-filtered snapshot fieldだけを使用できる。

- numeric: health、hunger、airと`lt / lte / eq / gte / gt`
- boolean: on_fire、submergedと`eq`
- inventory: item id別countと数値比較
- status: status effect idの有無
- 最大4 atomic predicateの`all`または`any`

chat、scoreboard、看板、本、sound、raw ray、任意block/entity queryをpredicateにできない。fieldがsnapshotに存在しない場合はfalseへ丸めず、`PREDICATE_UNAVAILABLE`で失敗する。

例:

~~~json
{
  "schema_version": 1,
  "program": {
    "dsl_version": 1,
    "name": "approach_and_face",
    "capabilities": ["movement", "camera"],
    "body": [
      {
        "id": "approach",
        "op": "navigate_to_known",
        "target": {
          "dimension": "minecraft:overworld",
          "x": 100,
          "y": 64,
          "z": 120
        },
        "tolerance": 0.75
      },
      {
        "id": "health_gate",
        "op": "if",
        "condition": {
          "field": "health",
          "comparison": "gte",
          "value": 8
        },
        "then": [
          {
            "id": "face_target",
            "op": "face_known_position",
            "target": {
              "dimension": "minecraft:overworld",
              "x": 102,
              "y": 65,
              "z": 120
            }
          }
        ],
        "else": [
          {"id": "hold", "op": "wait_ticks", "ticks": 20}
        ]
      }
    ]
  },
  "budget": {
    "max_duration_ms": 30000,
    "max_ticks": 600,
    "max_distance_blocks": 32,
    "max_camera_degrees": 360,
    "max_interactions": 0,
    "max_blocks_broken": 0,
    "max_blocks_placed": 0
  }
}
~~~

構造上限:

- AST depth: 4
- source node: 64
- repeat展開後の最大実行node: 256
- top-level body: 1〜32 node
- branch/body: 各0〜16 node
- node id: program内で一意
- request全体: 64 KiB以下

compilerは各nodeを`ticks / duration / distance / camera / interactions / breaks / places`のworst-case cost vectorへ変換する。sequenceは和、`if`は各成分のbranch最大値、`repeat`は固定回数倍とする。overflow、上限を証明できないprogram、request budgetまたはlocal hard limitを越えるprogramは入力を発生させず拒否する。実行時も各node開始前と各ClientTickで実counterを再検証する。さらにprimitive nodeごとのcost boundをcompiled programへ保持し、repeatで同じnodeを再度実行する場合もlogical occurrenceごとに開始counterを固定する。replanでは開始counterを更新せず、成功して次のoccurrenceへ進んだ時だけ更新するため、再計画でprimitive予算を補充できない。ここでcompile時の`duration`は20 TPSでのactive tick scheduling見積り（1 tick = 50 ms）であり、低TPSやclient stallを含むwall-clock完了保証ではない。`max_duration_ms`はこれと独立した`System.nanoTime`基準のhard deadlineとして各出力前に検査し、pause時間だけを除外するため、tick見積りを満たしていてもstall時は入力を出さず`BUDGET_EXCEEDED`で終了できる。

templateは`agent_start_action.inputSchema.examples`に掲載し、実装repositoryにも次のJSONを置く。

- [`navigate_to_known.json`](action-templates/navigate_to_known.json): 1地点への移動
- [`approach_and_face.json`](action-templates/approach_and_face.json): 移動、health分岐、視点変更または待機
- [`known_route.json`](action-templates/known_route.json): 既知区間を固定回数だけ往復する
- [`break_known_oak_column.json`](action-templates/break_known_oak_column.json): 地上から届く、現在可視な3段oak幹を下から順に破壊する

templateもcustom programと同じvalidator、capability、budget、READY許可、安全条件を通る。

#### 8.5.3 受付と応答

受付条件:

- worldとplayerが存在
- READY許可が有効
- 実行中Taskがない
- AST、predicate、capability、static budgetが有効
- 全targetと必要経路がKnown Traversability Mapで使用可能
- targetが同じdimension
- 第10章の安全事前条件を満たす
- multiplayerの場合はローカルallowlist済み
- 全budgetが上限内

受付成功:

~~~json
{
  "schema_version": 1,
  "action_id": "550e8400-e29b-41d4-a716-446655440000",
  "state": "queued",
  "accepted_at": "2026-08-26T00:00:00Z"
}
~~~

返却上の`state: "queued"`は、内部の`UNCONFIRMED`とconfirm後の`QUEUED`を同じ公開状態へ写像した値である。client threadでActionを`UNCONFIRMED`として予約した後、HTTP responseを送信できた場合だけdelivery confirmをqueueへ入れる。送信失敗時は予約をabandonし、confirmされないまま5秒経過したActionも自己失効する。`UNCONFIRMED`中はAction tick、primitive開始、入力出力を行わない。

confirm後も最初の入力直前にadmission snapshotとの整合を再検証する。world/session、control、pose、policy、観測依存edge、安全条件等が変化していれば、公開済みaction_idを入力なしで`failed`へ遷移させる。

agent_get_action:

~~~json
{
  "schema_version": 1,
  "action_id": "550e8400-e29b-41d4-a716-446655440000",
  "state": "failed",
  "progress": {
    "phase": "finished",
    "current_node_id": null,
    "executed_nodes": 3,
    "total_node_upper_bound": 4,
    "distance_travelled": 11.4,
    "camera_degrees": 42.0,
    "interactions": 0,
    "blocks_broken": 0,
    "blocks_placed": 0,
    "ticks": 247
  },
  "failure": {
    "code": "PATH_BLOCKED",
    "recoverable": true,
    "evidence": ["known_edge_invalidated"]
  },
  "trace": []
}
~~~

`progress`のschema上限は通常Actionと、そのActionをpreemptしたrecoveryの累積上限である。したがってdistanceは32 + 16 = 48 block、cameraは360 + 360 = 720度、tickは600 + 200 = 800となる。通常Actionのinteraction / place予算は0、break予算は最大8で、recoveryはinteraction 8 / break 4 / place 8を別枠で持つため、公開break counterの上限は12である。同dimension内のserver correction、teleport、knockbackなど外力で実測値がこの固定契約を越えた場合、公開counterはschema上限へ飽和させると同時に内部overflow latchを立て、Actionをbudget超過として終了する。飽和値を「上限内」と誤認したり、契約外の値を返したり、外力を相殺したりはしない。

agent_get_stateの返却対象:

- health、absorption、hunger、air、fire、submerged、位置、向き、dimension
- current client tickとworld revision
- 自inventoryのitem別集計
- OFF / READY / AGENT / RECOVERING状態、game pause
- 有効policyとhard limit
- DSL version、構造上限、現在許可されたcapability
- 最新immutable observation frameのID、範囲、鮮度、coverage、kind別件数
- 現在または直近action_idと終了理由

生chunk、遮蔽されたentity、chat、看板、本、seed、tokenはTool resultへ含めない。許可された観測recordだけを`agent_get_observation`で最大256件ずつ返す。Action traceはagent_get_actionで最大256件まで返す。既に行った移動、破壊、設置、攻撃、item消費はtransactionではなく、cancel時に自動rollbackしない。不可逆primitiveは実行直前にも観測、capability、budgetを再検証する。

### 8.6 内部Task state

~~~text
UNCONFIRMED → QUEUED → RUNNING → SUCCEEDED
     │                       → FAILED
     │                       → CANCELLED
     └─ delivery失敗 / 5秒失効 → FAILED

QUEUED → CANCELLED
~~~

`UNCONFIRMED`はHTTP配信を確定するための内部状態で、公開wire stateでは`queued`とする。RECOVERINGはTask stateではなくRUNNING中の高優先度runtime phaseとして記録する。Vanillaがpause中もTask stateはRUNNINGのまま入力を出さず、simulation再開時に再検証する。RETRYINGとRESUMINGを公開stateにはしない。terminal後の再試行は、新しいON操作とaction_idで行う。

MCP Tasks extensionは使用しない。MCP request自体は短時間でJSON responseを返し、ゲーム内の長時間処理はaction_idを持つ内部Taskとして管理する。

action_idはJDKのUUID.randomUUIDで生成する。保持するのは実行中Actionと直前のterminal Actionの最大2件だけで、world離脱とclient終了時に全件破棄する。期限切れまたは未知のIDはACTION_NOT_FOUNDとする。

認証・HTTP制限違反はTool dispatch前にHTTP error、MCP request構造の違反はJSON-RPC error、domain errorは`resultType: "complete"`かつ`isError: true`のTool resultとして返す。

domain errorのTextContentは次のJSON objectを1件だけ直列化する。schema外fieldは付けない。

~~~json
{
  "code": "MCP_OPERATION_DISABLED",
  "message": "Enable MCP operation from the in-game Screen before starting an action.",
  "recoverable": true
}
~~~

受付前error:

- INVALID_ARGUMENT
- MCP_OPERATION_DISABLED
- NO_WORLD
- TASK_BUSY
- MULTIPLAYER_NOT_ALLOWED
- TARGET_UNKNOWN
- NO_KNOWN_PATH
- SAFETY_PRECONDITION
- PROGRAM_TOO_COMPLEX
- PROGRAM_BUDGET_UNPROVABLE
- PREDICATE_UNAVAILABLE
- CAPABILITY_DENIED
- SERVER_BUSY
- ACTION_NOT_FOUND
- FRAME_EXPIRED
- INVALID_CURSOR

`FRAME_EXPIRED`と`INVALID_CURSOR`は`agent_get_observation`、`ACTION_NOT_FOUND`はAction参照Toolに用いる。残る受付条件は`agent_start_action`の完全preflightで検出できれば、action_idを割り当てず同期的なTool errorとして返す。合格後からClientTick直前までにsnapshot/policyが変わった場合は、同じ入力を再検証し、既に返したaction_idを`failed`へ遷移させる。この再検証では`PREDICATE_UNAVAILABLE`または`CAPABILITY_DENIED`が起こり得るため別紙`agent_get_action.failure`にも含めるが、構造だけで確定する`PROGRAM_TOO_COMPLEX`と`PROGRAM_BUDGET_UNPROVABLE`は含めない。world、経路、安全性の失効は`WORLD_CHANGED`または`PATH_BLOCKED`へ正規化する。どの場合もMinecraft入力は発生させない。

実行後の終了理由:

- COMPLETED
- CANCELLED_BY_CLIENT
- USER_DISABLED
- EMERGENCY_STOP
- BUDGET_EXCEEDED
- PATH_BLOCKED
- WORLD_CHANGED
- SAFETY_RECOVERED
- RECOVERY_EXHAUSTED
- SERVER_DENIED_OR_DESYNC
- INTERNAL_ERROR

## 9. Task Runtime

すべてのprogramは次の順序を守る。

~~~text
Validate JSON AST
  → Compile predicates、capabilities、worst-case budget
  → Check READY authorization and world preconditions
  → Build bounded primitive plan
  → Execute at most one deterministic primitive step per ClientTick
  → Safety Governor preemption / local replan
  → Validate Postconditions
  → Success or structured failure
  → Release synthetic input
~~~

### 9.1 共通budget

- 最大active実行時間（Vanilla pause中を除く）
- 最大tick数
- 最大移動距離
- 最大camera回転量
- 最大block破壊数
- 最大block設置数
- 最大interaction数

実行器は指定budgetとローカルhard limitの小さい方を採用する。超過しそうなGoal primitiveは実行しない。能動的危険がなければそのtickで`BUDGET_EXCEEDED`としてOFFへ戻し、危険が進行中ならGoalを破棄して第10章の固定recovery budgetだけを使用する。

program全体のeffective budgetに加え、各logical primitive occurrenceにもcompile済みcost boundを適用する。距離とcameraの実行器へ渡す残量は両者の小さい方とし、tick、duration、interaction、break、placeも各tickで双方を検査する。`wait_ticks`も同じ対象であり、replanやprobeによってoccurrence上限を更新しない。

### 9.2 navigate_to_known — MVP

対応:

- 5〜32 block
- 同一dimension
- CONFIRMEDまたは条件を満たすPROBE_ALLOWED edge
- 同一高さの通常歩行と、既知edge上のslab、stairs
- forward、back、strafe、視点調整

非対応:

- block破壊・設置
- doorやcontainer操作
- ladder、水泳、boat、elytra
- gap jump、parkour
- sprint、combat
- frontier探索
- full-block 1段分のstep-up / step-down edge自動生成

経路が変化した場合は影響edgeだけをSTALEにし、現在AABBが安全なら再検証と局所再計画を行う。未知supportへは出ず、既知graphとPROBE_ALLOWEDだけで代替経路がない場合に`PATH_BLOCKED`とする。現在AABBが危険なら第10章のRECOVERINGへ昇格する。

Phase 2時点のmovement gateは、既に選ばれた上下edgeのVanilla resolved movementを検証できるが、Local Observation Volumeからfull-block高低差edgeを能動生成する処理は未実装である。必要edgeを推測・合成せず、target自体が未知なら`TARGET_UNKNOWN`、targetは既知でも接続edgeがなければ`NO_KNOWN_PATH`として入力前にfail-closedとする。

### 9.3 harvest_tree — Phase 2

対象を、地上から到達可能な可視・既知のoakまたはbirch、宣言幹数8以下に限定する。高位の固定`harvest_tree` opcodeは作らず、同じ`break_known_face`を並べた通常DSL templateとして表現する。

事前条件:

- treeが可視・既知
- 道具耐久とinventory空き
- 最大破壊数budget
- survival、接地、非水中・非飛行で、各幹を攻撃lease内に破壊できる速度
- 指定面へのVanilla reach、crosshair、world border、保護、block IDの直前一致

事後条件:

- 宣言した対象幹をすべて処理
- 各幹はVanilla prediction ACKとauthoritative airの両方で確認
- 取得item数は観測値として報告できるが、drop由来や回収完了を保証しない

初回sliceはAction受付時に全対象面が現在可視である単純な直立幹だけを扱う。同一targetの重複と`repeat`内の破壊を静的拒否し、隠れた幹をchunk走査して探索しない。破壊で新たに露出した面の遅延再観測、drop回収、苗木の植林は後続sliceとする。

### 9.4 tend_plot — Phase 2

対象はユーザーがローカル登録した矩形plotに限定する。

- wheat、carrot、potato、beetrootから開始
- 成熟を合法的に観測した作物だけを収穫
- 再植え用itemを確認してから破壊
- 全収穫cellの再植えを事後条件とする
- 農地上でjumpしない
- 水源、照明、周辺blockを変更しない
- modded cropとmodded inventoryは初期非対応

### 9.5 build_blueprint — Phase 3

初期限界:

- 最大box: 9 × 9 × 9
- 最大変更: 256 block
- NBTなしの通常full block
- fluid、gravity block、container、portal、command blockは不可
- 許可box内だけ
- survivalではinventory内のitemだけ
- creativeでもfill/setblockを使わず、通常設置操作だけ

Blueprint:

~~~json
{
  "origin": {"x": 0, "y": 64, "z": 0},
  "size": {"x": 5, "y": 4, "z": 5},
  "palette": ["minecraft:oak_planks"],
  "blocks": [
    {"dx": 0, "dy": 0, "dz": 0, "palette": 0}
  ]
}
~~~

自動rollbackは保証しない。失敗時は入力を止め、変更済み位置をtraceとして返す。

### 9.6 RedstoneSpec — Phase 5

LLMへblockを1個ずつ操作させず、次を入力とする。

~~~text
RedstoneSpec
  ├─ inputs
  ├─ outputs
  ├─ truth_table
  ├─ timing_constraints
  ├─ allowed_components
  └─ max_footprint
~~~

MOD側がBlueprintへ変換し、向き、support、設置順を解決する。leverなどの入力を通常操作し、観測可能な出力だけで真理値表を試験する。複雑な最適化や任意回路合成は初期対象外とする。

## 10. Reflex Governor

Safety GovernorはGoalを達成せず、現在Actionより常に高優先度で、MCPやLLMの応答を待たない。healthや支持面を一律の停止条件にはせず、ClientTickごとに次の4分類から選ぶ。

### 10.1 危険度分類

| 分類 | 条件 | 処理 |
|---|---|---|
| CONTINUE | 変化が行動範囲外、または安全性が同等 | Mapだけ更新して継続 |
| REPLAN | 現在地は安全だが、経路・支持・流体・対象が変化 | 入力を一度neutralにして局所再計画 |
| RECOVER | 能動的な損害、呼吸不足、危険な落下などが進行 | Goalをpreemptし、有限の緊急回避 |
| STOP | Esc、OFF、制御対象消失、内部不変条件違反 | 即時入力解除、queue破棄。物理EscはREADY、他はOFF |

RECOVERへの昇格は固定health値ではなく、次で決める。

~~~text
estimated ticks until irreversible harm
  <= estimated ticks to verified safer state + safety margin
~~~

推定不能で現在損害が進行中ならRECOVER、現在安全ならREPLANとする。healthが低くても減少が止まり、既知退路が安全なら停止せず、回復または再計画を選ぶ。healthが高くても溶岩、窒息、落下、反復被弾が進行中ならRECOVERする。

### 10.2 使用する危険証拠

- health + absorptionのtick差分、last damage source、その観測tick
- air supplyの実測減少率、underwater、water breathing
- on fire、remaining fire ticks、lavaとのAABB交差、fire resistance残り時間
- player AABB、velocity、tick間XYZ、support、collision、fluid shape
- current AABB、Vanillaが解決したaxis別swept path、Local Observation Volume
- server position correctionの頻度、world update停止、client tick遅延

last damage sourceは過去値が残り得るため、non-nullだけで新しい攻撃と判定しない。effective health差分と自前のdamage tickを組み合わせる。落下距離は公開fieldへ依存せず、on-groundだった最後のtick以降の下降量を位置履歴から保守的に積算する。raw sound eventとentity hintはLLMへ公開するが、それだけで実entity、地形、危険を確定しない。

### 10.3 ケース別処理

| ケース | 判定 | 優先する有限行動 |
|---|---|---|
| 低health、損害停止、退路安全 | REPLAN | 安全地点へ退避し、許可済み回復itemがあれば結果確認付きで使用 |
| 反復被弾、近距離攻撃、projectile/explosion予兆 | RECOVER | 攻撃源から離れる、確認済み遮蔽へ移動、使用可能なら盾・blocking itemを構える |
| 炎上 | REPLANまたはRECOVER | 既知の水・消火可能空間へ移動、間に合う場合だけfire resistance等を使用 |
| Fire Resistanceなしで溶岩接触 | RECOVER | 最短の非溶岩方向へ上昇・横移動、退出後に消火。水設置はdimension・面・射程・反映を検証 |
| Fire Resistance残りが退出所要時間を十分上回る | REPLAN | 慌てて停止せず、最短退出経路へ切替 |
| air不足、浮上経路あり | RECOVER | 衝突のない上方向へ浮上 |
| air不足、天井あり | RECOVER | 最寄りの既知呼吸空間、上向きbubble column、間に合うwater breathing、検証可能な空気確保の順 |
| 数歩先の支持面消失 | REPLAN | forwardをneutral、影響edgeをSTALE化して代替経路 |
| 足元消失、低い安全面へ着地可能 | CONTINUEまたはREPLAN | 着地点へ姿勢制御し、着地後再計画 |
| 致死落下、lava、void方向 | RECOVER | 既知の横着地点へ寄せる。bucket、block、elytra等は専用試験とpreconditionを満たす場合だけ |
| 経路外のblock更新 | CONTINUE | affected evidenceだけ更新 |
| 次のVanilla axis別swept pathへlavaまたは崩落が到達 | RECOVER | 侵入方向と逆の既知安全空間へ離脱 |
| suffocation | RECOVER | 直前のfree AABBへ戻る。breakは緊急policyと対象検証がある場合だけ |
| cactus、wither rose、成長済みsweet berry bushとの接触 | RECOVER | `contact_damage`として記録し、同じdamage surfaceを増やさない既知の乾いた安全面へ退出 |
| powder snow / freezing | REPLANまたはRECOVER | 既知退路または上方へ移動し、利用可能なら適切な装備へ切替 |
| poison、wither、starvation | REPLANまたはRECOVER | 損害速度と回復所要時間を比較し、退避、食料、milk、回復itemから副作用込みで選択 |
| server correction反復、world update停止 | REPLAN後STOP | 入力をneutral、1回だけ再同期を待ち、継続不可能なら`SERVER_DENIED_OR_DESYNC` |

Respirationなど確率的効果を先読みせず、airの実測減少率を使う。下降bubble columnは呼吸を補えても下降自体が危険なため、底面と残り猶予を検証する。potionは消費完了まで生存できる場合だけ選ぶ。水bucketはNetherなど水を維持できないdimensionでは候補にしない。

`LAVA`、`DROWNING`、`DANGEROUS_FALL`がRECOVER判定になった時点でcritical latchを立て、新しいcritical dangerが回避中に加わった場合も同様に保持する。単一tickの位置・air・接触の揺れでは解除せず、非溶岩かつ既知安全な接地/水域、呼吸回復、水または既知安全面への着地というdanger固有条件を2 ClientTick連続で満たした場合だけ解除する。contact damageはcritical latch対象ではないが、Local Observation Volumeのcurrent/swept領域で検出した時点でurgent hazardとしてRECOVERへ渡す。

### 10.4 block配置と一時避難

周囲をblockで囲う、一時遮蔽を置く、空気確保blockを置く処理は有効な場合があるが、窒息、退路封鎖、溶岩への閉じ込め、gravity block落下、他者の妨害、server拒否を招く。そのため単純な「低healthなら囲う」規則にはしない。

配置候補は次をすべて満たす場合だけ許可する。

- singleplayer、または利用者が緊急配置も許可したallowlist server
- 現在地と完成後のplayer AABBが非衝突
- 呼吸可能空間と最低1つの退路を維持
- lava、fire、fall、voidを内側へ閉じ込めない
- 既知support、通常reach、通常interaction pathを使用
- inventoryにallowlist済みの非gravity・非container・非危険blockが存在
- placement後の実際のBlockStateまたはcollision変化を確認してから成功扱い

完全な箱より、攻撃方向だけの1〜2面遮蔽を優先する。溺水時の空気確保は「この版と接続先で実際に空気を作る」と試験済みのblockだけを使い、door等の名前だけで成功を仮定しない。配置が拒否されたら連打せず、別候補へ移る。

### 10.5 回避候補の選択とbudget

候補は次の辞書順で評価する。

1. 予測致死を避ける
2. active damageとair消費を減らす
3. 既知の安定support・呼吸空間へ到達する
4. world変更数とitem消費を減らす
5. 元のGoalからの逸脱を減らす

既定recovery budgetは、最大200 active tick、移動16 block、camera累積360度、interaction 8回、block設置8個、block破壊4個とする。cameraは通常Actionと同じ角速度上限に従い、purpose=`safety`をtraceへ残す。これらはlocal configのhard range内でだけ調整でき、DSLやMCP clientから変更できない。既定policyは移動、盾、回復・耐性item、検証済み配置を許可し、緊急attackとblind breakを禁止する。

候補が失敗してもknown worsening stateで即座に立ち止まらず、budget内で次の低リスク候補へ切り替える。安全化したら元programを暗黙resumeせず、Actionを`SAFETY_RECOVERED`で終了してOFFへ戻す。全候補とbudgetが尽きたら入力を解除し、`RECOVERY_EXHAUSTED`、試した候補、最終状態を返す。EscとOFFはRECOVER中も常に最優先である。

Phase 1の実装対象は、neutral、既知nodeへの短い退避、上方向への水泳、既知非危険流体への退出、既知着地点への姿勢制御までとする。item use、block配置、breakを使う回避は、それぞれの専用GameTestを通過したPhaseで有効化する。後続実装を見越した安全分類とbudget契約はPhase 1から固定する。

## 11. マルチプレイ

client-only構成では、サーバーが本MODを許可していることを技術的に確認できない。

既定値:

- singleplayer: 利用可能
- multiplayer: 無効
- multiplayerを使う場合: ユーザーが接続先をローカルallowlistへ明示登録し、接続sessionごとにScreenからON

allowlistは許可を証明するものではなく、誤操作防止だけを目的とする。サーバー規約の確認責任はユーザーにある。

`config/mcmcp/allowed-servers.json`のschemaは次へ固定する。

~~~json
{"schema_version":1,"servers":["example.org:25565"]}
~~~

root propertyは`schema_version`と`servers`だけ、versionは1、fileは16 KiB以下、entryは最大64件の文字列とする。各entryは前後空白除去・小文字化後255文字以下かつcontrol文字なしでなければならず、現在の接続addressとportを含めた文字列の完全一致だけを許可する。wildcard、DNS展開、port補完、未知property、壊れたJSON、欠損fileはすべて不許可とする。利用にはこの一致に加えて`multiplayer_default=true`とsessionごとのScreen上ONが必要である。

MODは次を行わない。

- custom payload registration
- Minecraftゲームサーバーへのcapability probe
- server pluginの存在確認
- speed、reach、attack intervalの変更
- direct packet injection
- server拒否actionの連打

行動は通常のplayer input/action pathを通す。サーバーがactionを拒否しworld stateが変わらなければ、再試行を制限してSERVER_DENIED_OR_DESYNCで停止する。

「Vanilla相当のpacketが送られること」と「サーバールールで許可されること」は別であり、アンチチート非検出は受入条件にしない。

## 12. NeoForge実装とPrism配布

### 12.1 build

- ベース: NeoForge 26.2 ModDevGradle MDK
- minecraft_version: 26.2
- neo_version: 26.2.0.59
- Java toolchain: 25
- 言語: Java
- mappings: 対象MDKの公式mapping
- mod_id: mcmcp
- Java base package: dev.aod.mcmcp
- artifact: mcmcp-neoforge-26.2-0.1.0.jar

physical clientだけで初期化する。dedicated serverでは機能を登録しない。

### 12.2 依存

MVPでは追加runtime依存を持たない。

- HTTP: JDK 25 HttpServer
- JSON: Minecraft同梱Gson
- logging: Minecraft/NeoForgeのSLF4J
- pathfinding: Java collection上のA*

Spring、Jetty、Netty追加、SQLite、DI container、独自event busは導入しない。

15.2のNode/npxは開発時の公式conformance runnerにだけ使用し、配布jar、Prism起動、MCP実行時には不要とする。

公式MCP Java SDKは、3.x以降がMCP 2026-07-28へ対応し、対象Prismプロファイルで互換性を確認できた場合にだけ再評価する。初版では同梱しない。

### 12.3 設定

次のlocal設定を使用する。TOMLとtokenは初回起動時に生成し、`allowed-servers.json`はmultiplayerを明示許可する利用者だけが上記schemaで作成する。欠損時はfail-closedとする。

~~~text
minecraft/config/mcmcp-client.toml
minecraft/config/mcmcp/mcp-token
minecraft/config/mcmcp/allowed-servers.json
~~~

client config:

- endpoint_enabled
- port
- max_request_bytes
- hud_offset_x / hud_offset_y
- omnidirectional_visual_radius_blocks
- omnidirectional_rays_per_tick
- max_camera_degrees_per_second
- emergency_item_use
- emergency_block_placement
- emergency_block_break
- recovery_max_ticks / distance / camera_degrees / interactions / placements / breaks
- multiplayer_default

MVPではrecovery各値の設定可能な上限を200 ticks、16 blocks、360 degrees、8 interactions、8 placements、4 breaksとする。Goal上限との合算が`agent_get_action`の固定出力schema（800 ticks、48 blocks、720 degrees）を越えないことをconfig境界でも保証する。

tokenはconfig screenへ平文表示しない。ローカルclient commandまたはMods画面のbuttonから、MCP接続設定をclipboardへコピーできるようにする。

### 12.4 Prism導入

1. gradlew buildでjarを1個生成
2. Prism Launcherで「くらふとぶ！-v01.2」を編集
3. Mods画面からjarを追加、またはminecraft\modsへ配置
4. 初回起動後、HUDでMCP endpoint状態を確認
5. ローカルMCP hostへendpointとbearer tokenを登録

mmc-pack.json、既存MOD、world、server設定は書き換えない。

### 12.5 既存24 MODとの互換方針

- renderer内部に依存せず、Vanilla/NeoForgeのOUTLINE・VISUAL・COLLIDER shapeとplayer eye原点の全周sampleを使う
- modded containerとitem actionをMVPでは操作しない
- NeoForge eventを優先し、Mixin範囲を最小化
- HTTP port競合時は自動で別portへ露出せず、安全に無効化
- package名とmod_idを固有化
- 依存libraryを追加しないことでclass/module衝突を避ける

特にSodium系描画変更、Carry On系interaction変更、backpack系container変更の影響を実機smoke testで確認する。

現在の24 jarは別紙`MCMCP_Prism_互換試験ベースライン.json`へfilename、size、SHA-256付きで固定する。試験開始時に全件一致を検証し、差分があれば結果を流用せず、新しい構成として明示的にbaselineを取り直す。

## 13. 非機能要件

### 13.1 性能

- Agent処理のp95: 2 ms / ClientTick以下
- 1 tickのhard limit: 10 ms
- idle時のclient tick p95増加: MCMCPなしbaselineに対しmax(1.0 ms、10%)以下
- path expansion数をtickごとに制限
- MCP statusを毎秒10回、60秒呼んでもgame threadがHTTPを待たない
- memory内trace: Taskあたり最大256 event
- command queue: 32件

性能値は対象Prismプロファイル上で測定し、全周visualの半径・ray/tick、path expansion上限をhard range内で調整可能にする。Local Observation Volumeは安全契約を一定にするため半径4 block、最大6 transitionへ固定する。

### 13.2 停止・回避応答

- Esc、ScreenのMCP操作OFF、MCP cancelから次のClientTickまでにTaskを停止
- 20 TPS時の目標は50 ms以内、負荷試験上限は100 ms
- terminal state後にsynthetic keyがdownのまま残らない
- exception、disconnect、world変更時はfail-closed
- RECOVER判定から次のClientTickまでにGoal入力をpreempt
- focus喪失とScreen表示だけではAction stateを変更しない

### 13.3 audit

- Taskごとのメモリ内ring buffer
- 主要eventだけをSLF4Jへ構造化出力
- token、chat、看板、本、全chunk情報をlogしない
- 永続telemetryを送信しない

主要event:

- TASK_ACCEPTED
- CONTROL_ACQUIRED
- TASK_RUNNING
- USER_DISABLED
- EMERGENCY_STOP
- SAFETY_REPLAN
- SAFETY_RECOVERY_STARTED
- SAFETY_RECOVERY_FINISHED
- TASK_SUCCEEDED
- TASK_FAILED
- INPUT_RELEASED

## 14. 受入条件

### 14.1 build・Prism

- gradlew buildが成功し、利用者が配置するjarは1個
- jar内に有効なMETA-INF/neoforge.mods.tomlを含む
- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25.0.1でload
- 「くらふとぶ！-v01.2」のMods一覧へMCMCP NeoForgeが表示
- baseline 24 jarにMCMCP 1 jarを加え、mods直下が25 jar
- 既存24 MODを残したままtitle screenまで起動
- test worldへ参加・退出・再参加できる
- MCMCPのMinecraft dedicated-server側componentがなく、既存mod構成と互換な検証用serverへ通常接続できる
- endpoint停止やport競合でもMinecraft自体は起動継続
- Node、別Javaサービス、server pluginを要求しない

### 14.2 MCP transport

- OS上で127.0.0.1以外にlistenしていない
- tokenなし・誤tokenは401
- Origin付きrequestは403
- 64 KiB超過requestは413
- 20 request/秒を継続して超えるTool callは429となり、Retry-Afterを含む
- malformed JSONはゲームへ影響せずJSON-RPC error
- application/json以外のrequestは415
- JSON-RPC batchは拒否
- GETとDELETEは405、POSTだけを受理
- `MCP-Protocol-Version`欠落・不一致を拒否
- `Mcp-Method`とJSON-RPC methodの不一致を拒否
- clientInfoを省略した適合requestを受理
- 対象MCP hostからserver/discover、tools/list、tools/callが成功
- tools/listが別紙どおり5 Toolを固定順で返す
- server/discoverとtools/listがresultType、ttlMs、cacheScopeを常に含む
- 成功responseがresult._metaのserverInfoとContent-Type application/jsonを含む
- `@modelcontextprotocol/conformance@0.2.0-alpha.11`の固定Tools-only scenarioが全件成功
- tokenがlog、URI、Tool resultに含まれない

### 14.3 操作権

- OFF中のagent_start_actionはMCP_OPERATION_DISABLEDで入力を変更しない
- 有効worldのScreen buttonからONにでき、READYはAction開始、明示OFF、またはworld変更まで維持
- READY中に受理するActionは1件だけで、terminal後はOFF
- 同時2件目はTASK_BUSY
- enqueue後、ClientTick前にworld、READY、control epochが変わった場合は入力せず失敗
- READY中のEscは通常どおりchat/menuを閉じ、READYを維持
- AGENT/RECOVERING中のEscは1 ClientTick以内にEMERGENCY_STOP、queue破棄、入力解除、READY
- Screen buttonのOFFは1 ClientTick以内にUSER_DISABLED、queue破棄、OFF
- Esc以外の物理key、mouse button、scroll、mouse turnはActionを止めず、Minecraftへ影響しない
- MCMCP button hit box内の左clickだけが入力隔離を通過
- chat、inventory、menuの表示だけではActionを停止しない
- focus喪失だけではActionを停止しない
- non-paused `ChatScreen`を開いたまま、`navigate_to_known`、`face_known_position`、`wait_ticks`が完了し、Screenも閉じない
- non-paused `InventoryScreen`とmultiplayer pause menuでも同じDSLが完了し、Screenも閉じない
- chatを開いてfocusを外した状態でも、物理入力なしでActionがactive budget内に完了する
- Vanillaが実際にpause中は入力up、budget凍結、再開時にworld revision再検証
- world離脱、dimension変更、respawn、死亡は1 ClientTick以内に解除してOFF
- terminal state後、Agent所有keyがすべてup
- gameplay中は右下に状態iconだけを表示し、常設文字panelを出さない
- AGENT開始時のEsc案内は3秒で消える
- Screen表示中は右下にicon、状態文、ON/OFF buttonを表示
- AGENT/RECOVERING中はgameplay、chat、inventory、menuの外縁に2 pxの黄色枠を表示し、停止後は同じframeで消える
- iconはOFF、READY、AGENT、RECOVERING、FAULTで色と輪郭が異なる
- gameplay HUD iconとScreen buttonが対象24 MODの主要Screenでcrashせず、offset設定が反映される

### 14.4 API・状態

- schema外field、NaN、Infinity、範囲外座標を拒否
- 全成功Tool resultが宣言済みoutputSchemaに一致
- 業務上の拒否はisError=true、protocol不正はJSON-RPC errorとして区別
- 未定義のTask state遷移を起こさない
- agent_cancel_actionは冪等
- cancelから1 ClientTick以内にCANCELLED
- terminal Taskが新しい入力を発生させない
- HTTP handlerがMinecraft APIを直接呼ばない
- agent_start_actionはAction完了を待たずaction_idを返す
- agent_get_stateが最新immutable observation frameのIDと概要を返す
- agent_get_observationは任意center/radiusを受け付けず、同じframe_idのpage内容がframe保持中に変わらない
- 最大256件でpage分割し、壊れたcursorはINVALID_CURSOR、保持外frameはFRAME_EXPIRED
- page継続中のframeはleaseでpinされ、rolling frame更新後も同じcursor再送が同じpageを返す
- 完成frameのsampling_coverageは1で、各recordのorigin、observed tick、world revisionとframe_completed_tickから鮮度を再現できる
- 斜めtraversability recordがfrom/to edgeを保持し、単一cellへ潰れない
- DSLのunknown opcode、重複node id、深さ5、source node 65、展開node 257、repeat 17を入力前に拒否
- 同じ展開実行経路に複数の`face_known_position`を含むDSLを受理し、AST、node、時間、camera累積budgetだけを適用
- `if`はpolicy-filtered snapshotだけを評価し、欠損fieldはPREDICATE_UNAVAILABLE
- 任意式、while、until、再帰呼出し、chat/text predicateを拒否
- 静的costが証明不能ならPROGRAM_BUDGET_UNPROVABLE
- 通常Actionはeffective budgetを超えず、RECOVERはDSLから独立したlocal recovery budgetだけを使う
- templateとcustom programが同じvalidator、capability、budgetを通る

### 14.5 Navigation MVP

GameTest structure`mcmcp:navigation_flat`を各回resetして20/20回成功させる。floorはlocal Y=1のstone、開始poseは(2.5, 2.0, 2.5)、targetは(12, 2, 2)、難易度Peaceful、正午、晴天、entityなしで固定する。テストresourceへ固定camera pose、全周sample順、期待Known Traversability Map evidenceを保存し、その一致を確認した次のtickで同じAction DSLを投入する。

- 5〜20 blockの観測済み平坦経路
- targetから水平0.75 block以内、垂直0.25 block以内で停止
- 距離、tick、wall-clock budget内
- blockを破壊・設置しない
- attack、use、jump、sprintを押さない
- 実行中に障害物を置くと、破壊・未知迂回をせず20 tick以内にPATH_BLOCKED
- 未観測targetは移動開始前にTARGET_UNKNOWN
- budget超過と同じtickで入力解除
- 観測処理だけを200 tick実行してyaw/pitchが変化しない
- playerの開始yawに関係なく、同じ露出surface/entityが全周frameへ現れる
- stone壁裏のsurface/entityは現れず、glass越しのsurface/entityはvisual shape規則に従って現れる
- frame生成中にplayerとworld revisionが変化しても、各recordのeye/observer origin、tick、revisionが採取時点と一致する
- 4方向を順に向く明示DSLを受理し、各回を通常camera budgetへ加算する
- support確認済み・clearance不完全な1 edgeをPROBE_ALLOWEDとして低速通過し、成功後CONFIRMED
- 未観測supportには踏み出さない

### 14.6 Hidden-state non-interference

提示済み状態が同じで、壁の裏だけが異なるGameTest structure A/Bを作る。開始pose、Action DSL、難易度、時刻、天候、全周sample順、sound event、action開始tickを固定し、各試験前にstructureとKnown Traversability Mapをresetする。

~~~text
World A: 壁裏に鉄鉱石
World B: 壁裏は石
~~~

鉄鉱石が露出する前は、UUID、wall-clock時刻、JSON object key順を比較対象から除外し、次のpolicy-relevant fieldが一致しなければならない。

- agent_get_stateのworld、inventory、control、policy
- policy-filtered observation record
- Known Traversability Map
- A*のnode展開順
- synthetic inputの(tick offset、key、down/up)
- Task終了stateとreason code

さらに、`dev.aod.mcmcp.agent.runtime`と`dev.aod.mcmcp.agent.navigation`を対象に、Minecraftのworld、chunk、entity型のimport、および`getBlockState(`、`getChunk(`、`getEntities(`の参照が0件であることをCIの`rg`検査で確認する。これらのpackageが受け取れるworld情報は`AgentSnapshot`と`KnownTraversabilityMap`だけとする。Minecraft APIへ触れられるobservation packageも、任意座標探索ではなく全周visual ray、bounded entity query、Local Observation Volumeの範囲検査に限定する。world離脱後はMapが空になる。

~~~powershell
$targets = @(
  'src/main/java/dev/aod/mcmcp/agent/runtime',
  'src/main/java/dev/aod/mcmcp/agent/navigation'
)
$pattern = 'import net\.minecraft\.(client\.multiplayer\.ClientLevel|world\.level|world\.entity)|getBlockState\(|getChunk\(|getEntities\('
rg -n --glob '*.java' $pattern $targets
if ($LASTEXITCODE -eq 0) { throw 'Forbidden hidden-state dependency' }
if ($LASTEXITCODE -gt 1) { throw 'Dependency scan failed' }
~~~

### 14.7 安全・負荷

- health 2で損害なし・既知安全地点ありなら固定閾値停止せずREPLAN
- health 2でhostileから反復被弾なら1 ClientTick以内にRECOVER
- health低下中にlavaへ落ちても立ち止まらず、既知退出候補を実行
- Fire Resistance残り時間が退出所要時間より十分長い/短い両caseを分岐
- 水中で浮上可能、天井あり、上向き/下降bubble column、既知空気pocketを個別試験
- 経路3 block先のsupport消失は局所REPLANし、足元安全なら即時停止しない
- 足元消失でも1 block下が安全なら致死扱いしない
- 致死落下で既知横着地点がある場合はRECOVER
- 経路外block更新はCONTINUE
- 次のswept AABBへのlava流入はRECOVER
- recovery成功後は元ActionをresumeせずSAFETY_RECOVERED、OFF
- recovery budget超過はRECOVERY_EXHAUSTEDで全入力up
- block配置を伴う回避は窒息、退路、fluid、gravity、server拒否のnegative testを通る
- 斜め移動の包絡AABBだけに入るcorner blockを衝突扱いせず、実axis segmentのswept player AABBとcollision VoxelShapeが交差するblockだけを衝突扱いする
- `|x| < |z|`はY→Z→X、それ以外と同値はY→X→Zになり、Vanillaのresolved deltaと一致する
- 斜めの内角・外角、1軸slide、対角gap、slab、stairs、fence、wall、door、trapdoorを安全機能OFF時のVanilla結果と照合する
- `maxUpStep`以下・超過・低い天井ありの斜めstep-upを個別に照合する
- 斜めpathの未通過cornerにあるlavaは接触扱いせず、axis segmentのswept player AABBとfluid AABBが交差するlavaは終点が乾いていてもhazardへ記録する
- 流れる水・lavaの部分高さ、waterlogged block、未知modded FluidTypeの`UNKNOWN → REPLAN`を検証する
- endpoint支持面、cornerだけの偽support、edge落下、unloaded境界、entity/world borderを個別に検証する
- 跳躍、落下、遊泳中のsupport欠如を経路崩壊や即時STOPへ誤分類しない
- glass、pane、ice、leaves、water、slab、stairs、fence、door、custom modelでVISUAL・OUTLINE・COLLIDER・supportを別々に検証
- unloaded境界とambiguous custom renderはUNKNOWNで、AIRへ昇格しない
- 再生開始しなかったPlaySoundEventを観測にせず、position soundだけXYZを記録
- `minecraft:entity.zombie.ambient`、skeleton step、creeper primedはraw ID、XYZ、対応entity hintを返す
- parrot imitationはparrot hintとraw ID、generic explosionとunknown eventはnull hint、registry照合可能なmodded eventは対応hintを返す
- 同種近接soundは10 tick・2 block条件で集約し、32件上限、600 tick TTL、world切替消去を守る
- sound clueのoutput schemaを規範別紙の11 fieldだけに固定し、追加の推定評価fieldを返さない
- sound clueはLLMへ公開されるが、単独では移動、攻撃、回避、停止を開始しない
- sound eventだけでKnown Traversability Mapが更新されない
- 壁裏のtracked entityが視覚観測へ出ない
- queue満杯時はSERVER_BUSY
- MCPを停止・再起動してもMinecraftがcrashしない
- 異常終了後の次起動でキー状態やTaskが復元されない
- 既存24 MODとの60分smoke testでcrash、deadlock、watchdog停止がない
- 既存24 MOD manifestのfilename、size、SHA-256が別紙baselineと一致
- 同じ10分idle scenarioを3回測定し、client tick p95増加がmax(1.0 ms、10%)以下

## 15. テスト構成

### 15.1 通常unit test

- Action DSL JSON Schema、semantic validation、cost vector
- bounded if/repeat、predicate availability、capability validation
- Task state machine
- A*
- token比較
- JSON-RPC error mapping
- CONFIRMED / PROBE_ALLOWED / BLOCKED / STALE遷移
- 全周ray三値、shape channel、斜めaxis-segment collision、sound clue/entity hint mappingの境界

### 15.2 MCP conformance

conformance runnerは`@modelcontextprotocol/conformance@0.2.0-alpha.11`へ固定する。公式の`--requirements 2026-07-28`と`server-stateless`等は、Resources、Prompts、MRTR、runner固有diagnostic Toolを含むfixture serverを要求するため、製品固有の5 Toolだけを公開する本MODの合格判定には使用しない。MinecraftをPowerShellでは`.\gradlew.bat '-Dmcmcp.conformanceTest=true' runClient`でNeoForge development runとして起動し、次の製品非依存wire-level scenarioだけを固定実行する。

~~~powershell
$scenarios = @(
  'tools-list',
  'http-header-validation'
)
foreach ($scenario in $scenarios) {
  npx -y @modelcontextprotocol/conformance@0.2.0-alpha.11 server `
    --url http://127.0.0.1:8765/mcp `
    --scenario $scenario `
    --spec-version 2026-07-28 `
    --force `
    --output-dir "build/reports/mcp-conformance/$scenario"
  if ($LASTEXITCODE -ne 0) { throw "MCP conformance failed: $scenario" }
}
~~~

合格条件は2 scenarioともprocess exit code 0、failure 0件、expected-failures不使用とする。stateless/no-session、全Tool call、JSON Schema 2020-12、cache hint、error分離は別紙catalogを使うintegration testで補完する。Originをすべて拒否する本MODのbrowser非対応policyは、localhost Originを許可するrunnerの一般`dns-rebinding-protection` scenarioへ合わせて緩和せず、Originなし成功・Originあり403・Host制限をHTTP integration testで検証する。

runnerがAuthorization headerを設定できないため、NeoForge development runでだけ有効な`mcmcp.conformanceTest` system propertyにより認証を迂回できるようにする。このpropertyはproduction environmentでは無視し、release jar試験でpropertyを指定してもtokenなしrequestが401になることを確認する。test endpointも127.0.0.1以外へbindせず、認証以外のOrigin、Host、body、JSON、rate、concurrency制限は迂回しない。

### 15.3 NeoForge GameTest / Client test

- navigation固定scenario
- READYのEsc透過、実行中EscのREADY復帰、Screen OFF、MCP cancel
- AGENT中の物理keyboard/mouse隔離
- focus喪失、non-paused chat、inventory、multiplayer pause menuを表示したままnavigation/camera/waitを完了
- gameplay icon、実行中の黄色外縁、全主要Screenへの省スペース状態button
- world unload
- dynamic obstacle
- 10.3の危険分類とrecovery
- 全周frame、transparent/partial block、斜めcollision、unloaded、position sound/entity hint
- hidden-state A/B

NeoForgeはGameTestServerを標準run configurationとして提供する。Client inputとHUDはclient runを使って検証する。

### 15.4 target instance smoke

テスト専用に「くらふとぶ！-v01.2」を複製し、元インスタンスを変更せずに実施する。

- 24 MOD manifest照合
- MCMCPなしの10分idle baselineを3回取得
- jar追加
- 起動log確認
- title / world join
- MCP server/discoverとTool call
- singleplayer navigation
- allowlist済みtest server navigation
- stop / disconnect / restart
- OFF時の既存MOD主要画面とinventory操作
- AGENT時の入力隔離、MCMCP buttonだけのclick、HUD重なり

### 15.5 実プロファイル最終smoke

15.4合格後、利用者の了承を得て実インスタンス「くらふとぶ！-v01.2」のmodsへjarを1個だけ追加し、既存24 MODを残した状態で次を確認する。

- title screenまで起動
- test worldへの参加と退出
- server/discover、tools/list、tools/call(name=agent_get_state)
- MCMCPのMinecraft dedicated-server側componentがない既存接続先への接続

world、mmc-pack.json、instance.cfg、既存jarは変更しない。

## 16. 実装順序

1. NeoForge 26.2.0.59 skeletonと単一jar
2. Prism複製インスタンスで起動
3. loopback MCP、auth、5 Toolsのstub
4. GameGateway、Task state、READY許可、Esc、入力隔離、gameplay HUD icon、Screen button
5. Action DSL v1 parser、bounded if/repeat、static budget compiler
6. survival_omnidirectional evidence、immutable observation frame、Local Observation Volume、sound clue、Known Traversability Map
7. navigate_to_known / face_known_position / wait_ticks
8. 危険分類とPhase 1 recovery primitive
9. conformance、hidden-state、入力隔離、HUD、Prism共存試験
10. item use、block配置を伴うrecovery
11. harvest_tree
12. tend_plot
13. build_blueprint
14. acquire_item / craft / smelt
15. RedstoneSpec

前の段階で実測された問題だけを次の設計へ反映する。

## 17. 既知の制約と判断待ち

### 確定した制約

- client-onlyなので、サーバーの許可、領域保護、行動上限を強制できない
- server companionを後から足すロードマップは持たない
- 外部bridgeを前提にしない
- loopbackへ到達できないcloud-only MCP hostは利用できない
- 遮蔽と半径を守る観測と未知危険の完全回避は両立しない
- modded block、container、cropは初期非対応

### 接続時に確認する運用条件

接続先MCP hostはMCP 2026-07-28のStreamable HTTP、既存endpointへの接続、固定Authorization Bearer headerに対応し、ローカルloopbackへ到達できること。2025-11-25以前しか扱えないhost向け互換層は初版の対象外とし、実際に必要になった場合だけ別要件として追加する。

host固有の接続smokeは製品名とversionが選定された時点で実施する。host未選定でもMOD本体とprotocol conformanceの実装は開始できるが、利用環境へのMCP接続完了とは判定しない。

## 18. repository名と製品identity

repository名はユーザー指定の`mcmcp`へ確定する。GitHubのrepository名はowner単位なので作成可能だが、`Mica-Technologies/MCMCP`というForge 1.12向け既存projectがある。検索・説明上の混同は受容し、表示名、artifact、README冒頭でNeoForge client MODであることを区別する。

| 項目 | 確定値 |
|---|---|
| repository | `mcmcp` |
| 表示名 | `MCMCP NeoForge` |
| mod id | `mcmcp` |
| Java package | `dev.aod.mcmcp` |
| artifact | `mcmcp-neoforge-26.2-<version>.jar` |
| MCP serverInfo.name | `mcmcp` |
| description | `Client-only NeoForge mod with an embedded MCP server for bounded Minecraft automation.` |

既存projectのcodeやidentityを流用する意味ではない。本projectはMinecraft 26.2 / NeoForge 26.2、全周観測、Action DSL、現在のlocal player操作を対象とし、Forge 1.12向け既存MCMCPとは別実装とする。

Minecraft Usage Guidelinesに従い、Minecraftをrepositoryの主要・支配的な名前にせず、README、release、配布pageへ次と同趣旨の免責を目立つ位置に置く。

> NOT AN OFFICIAL MINECRAFT MOD. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

repository作成直前に、選択したowner配下で`mcmcp`が作成可能かを確認する。Modrinth、CurseForgeへ公開する場合は表示名の重複規則を別途確認する。

## 19. 参考資料

- [NeoForge 26.2 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-26.2-ModDevGradle)
- [NeoForge 26.2 source branch](https://github.com/neoforged/NeoForge/tree/26.2.x)
- [NeoForge 26.2.0.59 Maven artifact](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.2.0.59/)
- [Minecraft 26.1.x to 26.2 migration primer](https://docs.neoforged.net/primer/docs/26.2/)
- [NeoForge 26.2 ClientTickEvent](https://github.com/neoforged/NeoForge/blob/26.2.x/src/client/java/net/neoforged/neoforge/client/event/ClientTickEvent.java)
- [NeoForge 26.2 InputEvent](https://github.com/neoforged/NeoForge/blob/26.2.x/src/client/java/net/neoforged/neoforge/client/event/InputEvent.java)
- [NeoForge 26.2 ScreenEvent](https://github.com/neoforged/NeoForge/blob/26.2.x/src/client/java/net/neoforged/neoforge/client/event/ScreenEvent.java)
- [NeoForge 26.2 RegisterGuiLayersEvent](https://github.com/neoforged/NeoForge/blob/26.2.x/src/client/java/net/neoforged/neoforge/client/event/RegisterGuiLayersEvent.java)
- [NeoForge Key Mappings](https://docs.neoforged.net/docs/misc/keymappings/)
- [NeoForge ChunkEvent](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/level/ChunkEvent.java)
- [NeoForge Entity documentation](https://docs.neoforged.net/docs/entities/)
- [NeoForge Sounds](https://docs.neoforged.net/docs/resources/client/sounds/)
- [NeoForge 26.2 PlaySoundSourceEvent](https://github.com/neoforged/NeoForge/blob/26.2.x/src/client/java/net/neoforged/neoforge/client/event/sound/PlaySoundSourceEvent.java)
- [NeoForge 26.2 ClipContext.Block API](https://aldak.netlify.app/javadoc/26.2.x/net/minecraft/world/level/clipcontext.block)
- [NeoForge 26.2 BlockStateBase API](https://aldak.netlify.app/javadoc/26.2.x/net/minecraft/world/level/block/state/blockbehaviour.blockstatebase)
- [NeoForge 26.2 CollisionGetter API](https://aldak.netlify.app/javadoc/26.2.x/net/minecraft/world/level/collisiongetter)
- [NeoForge 26.2 Entity API](https://aldak.netlify.app/javadoc/26.2.x/net/minecraft/world/entity/entity)
- [NeoForge 26.2 AABB API](https://aldak.netlify.app/javadoc/26.2.x/net/minecraft/world/phys/aabb)
- [NeoForge 26.2 VoxelShape API](https://aldak.netlify.app/javadoc/26.2.x/net/minecraft/world/phys/shapes/voxelshape)
- [NeoForge 26.2 DamageSource API](https://aldak.netlify.app/javadoc/26.2.x/net/minecraft/world/damagesource/damagesource)
- [NeoForge 26.2 FluidState API](https://aldak.netlify.app/javadoc/26.2.x/net/minecraft/world/level/material/fluidstate)
- [Minecraft Wiki: Damage](https://minecraft.wiki/w/Damage)
- [Minecraft Wiki: Drowning](https://minecraft.wiki/w/Drowning)
- [Minecraft Wiki: Lava](https://minecraft.wiki/w/Lava)
- [Minecraft Wiki: Fire](https://minecraft.wiki/w/Fire)
- [Minecraft Wiki: Bubble Column](https://minecraft.wiki/w/Bubble_Column)
- [NeoForge Game Tests](https://docs.neoforged.net/docs/misc/gametest/)
- [Prism Launcher: Downloading Mods](https://prismlauncher.org/wiki/getting-started/download-mods/)
- [MCP 2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28)
- [MCP 2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- [MCP server/discover](https://modelcontextprotocol.io/specification/2026-07-28/server/discover)
- [MCP Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- [MCP Conformance Test Framework](https://github.com/modelcontextprotocol/conformance)
- [MCP Java SDK 2.0.x changelog](https://github.com/modelcontextprotocol/java-sdk/blob/main/CHANGELOG.md)
- [mcpfabric](https://github.com/Etoryx/mcpfabric)
- [mc_aiplayer](https://github.com/zoyluoblue/mc_aiplayer)
- [MCMCP: embedded MCP precedent](https://github.com/Mica-Technologies/MCMCP)
- [Existing CraftAgent repository](https://github.com/prskid1000/CraftAgent)
- [Existing Craftpilot repository](https://github.com/mmmfrieddough/craftpilot)
- [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)
