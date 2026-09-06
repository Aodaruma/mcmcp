# MCMCP NeoForge — Minecraft MCP Client MOD 設計・仕様書

- 文書版: 0.8
- 作成日: 2026-08-26
- 状態: 実装中、評価MCP hostはCodex CLI 0.146.1に固定
- 対象: Prism Launcher「くらふとぶ！-v01.2」
- 規範別紙: MCMCP_MCP_Tool_Catalog.json
- 試験別紙: MCMCP_Prism_互換試験ベースライン.json

## 0. 結論

本件は、次の構成で実現する。

- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25 向けのクライアント専用MOD
- 配布物は、Prism Launcherのmodsフォルダーへ入れる単一jar
- MCPサーバーはMODと同じMinecraft JVM内で起動
- MCP接続はMCP 2026-07-28を製品基準とする127.0.0.1限定のStreamable HTTP。Codex CLI 0.146.1向けに`initialize` / `2025-06-18`の限定互換経路も同じMOD内endpointで提供する
- サーバーコンパニオン、対Minecraftゲームサーバーcapability handshake、独自の対ゲームサーバー通信、外部MCP bridgeは作らない
- LLMは型付きAction DSLまたは同じDSLで書かれたtemplateを渡し、各primitiveはMOD内の決定論的ランタイムが実行
- サバイバルでは、player eyeから遮蔽されない全周visual、局所移動安全volume、実再生soundなど、明示した観測情報だけで判断する
- 全周visualはcameraを回さず取得し、position soundのevent IDとbest-effort entity hintをLLMへ公開する
- `face_known_position`の観測目的回数制限は設けず、一般のnode・時間・角速度・累積camera budgetだけを適用する
- Esc、Screen上のMCP操作OFF、world離脱を常にAgentより優先する
- chat、inventory、menuの表示とfocus喪失だけではActionを停止しない
- fresh評価turnまたはAgent実行中の物理キーボード・マウス入力は、EscとScreen上の状態ボタンを除きMinecraftへ渡さない

最初の実装は、既知地点への移動、既知地点への視点変更、有限待機を組み合わせるAction DSL v1から開始した。現在はPhase 2の伐採・小麦農業batch、Phase 3の監査済みcopy対象を1〜8件設置する`apply_known_block_plan`と同じ安全blockを1〜8件撤去する`clear_known_block_plan`、1 blockだけ安全に積み上がる`pillar_up_known`、Phase 4の標準Vanilla Potion 1段醸造`brew_known_potion_batch`、可視crafting tableで既知recipeを1〜3回作る`craft_known_recipe`、可視furnace familyでexact stack 1〜64個を精錬する`smelt_known_recipe`、現在開いているVanilla `generic_9x1`〜`generic_9x6`純storageまたは固定artifact検証済みSophisticated Backpacks通常storageからopaque参照で1 stackを移す`operate_known_menu`、床付きlanding間の完全なVanilla ladderまたは安定したscaffoldingを上下4段以内で通る`navigate_to_known`を公開する。Phase 5ではlever 1入力からredstone lamp 1個、固定配置の2個、または1 dustだけを挟む直線1出力へ同じ値を出すidentityを配置・OFF/ON/OFF観測する`apply_known_redstone_spec`までを公開する。次のPhase 4拡張では、player 2×2 crafting、Vanillaの専用workstation、対象Prism profileで必要なMOD item・workstationを、共通Menu interaction engineとversion固定の宣言profileで段階追加する。Phase 3の同一Action内置換・256 block化、2 block以上の連続pillaring、一般資源入手、可変長・曲がりを含む一般回路合成は、同じ安全境界を維持して追加する。

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
- 半径6 blockのLocal Observation VolumeとVanilla一致の斜めswept-AABB判定
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

- 初回vertical slice: 閉じたcopy/support allowlistだけが返すpolicy-visibleな完全BlockStateと設置itemを使う、stationary・place-only・1〜8 blockの`apply_known_block_plan`
- 同じ安全建築blockの完全な現行stateを使い、既存`BREAK_TO_AIR`経路で1〜8 blockを撤去し、全targetのfresh air再観測後に完了する`clear_known_block_plan`
- offsetとBlockStateのmirror / rotationはruntimeが同じMinecraft規則で変換し、LLMへ向き変換させない
- 現在surfaceまたは明示した先行entryだけをsupport proofとし、入力順のfail-fast実行とauthoritative postconditionを要求
- ローカルに登録した許可box
- 最大256変更のBlueprint
- Vanillaの移動・設置・破壊だけを使用
- 資材表、設置順、事後条件、変更履歴

### Phase 4: 探索・資源・クラフト

- 初回vertical slice: 空の可視・既知brewing standと自inventoryの標準Potion集計だけを使う、有限・1段の`brew_known_potion_batch`
- Minecraft 26.2の現行`PotionBrewing` tableと完全一致する入出力だけを通常menu操作で醸造
- 2×2 crafting、Vanilla workstation、共通Menu engineと対象profileの受入済みMOD profile（詳細は9.7）
- frontier探索
- acquire_itemのGoal分解
- 採掘、クラフト、精錬、保管
- セッションを越える合法的な地点記憶

### Phase 5: レッドストーン

- 公開vertical slice: lever 1入力に対し、redstone lampへの直接1出力、固定2出力fan-out、または1 dustだけを挟む直線1出力を扱う`apply_known_redstone_spec`
- 各lampには現在可視の不活性なUP support、leverには現在可視の`minecraft:glass` UP supportを要求し、stationaryで通常設置する
- leverのlamp以外の面隣接をLIVE current / visibleなairまたは直下glassに閉じ、live visualだけでlever / 全lampのOFF→ON→OFFを同tick集合として有限settle内に確認する
- 真理値表と入出力を持つRedstoneSpec
- 許可部品と最大footprint
- Blueprintへの変換
- 入力操作と出力観測による自動試験

各Phaseは前Phaseの受入条件を満たしてから着手する。DSLの構文と検証器はPhase 1で固定し、後続Phaseでは試験済みprimitiveだけをopcode allowlistへ追加する。任意コード実行や汎用スクリプト言語には拡張しない。未登録の高位作業は、固定Actionを追加し続けるのではなく、公開済みの低位semantic primitiveをLLMがJSON DSLとして合成し、チェックポイント付きproduction Jobが累積budget、effect ledger、再観測、有限retry、cleanupを監督する。低位操作の完全性、scoped consent、実装順は[`MCMCP_Production_Autonomy_Roadmap.md`](./MCMCP_Production_Autonomy_Roadmap.md)を規範補足とする。

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
| LocalObservationVolume | 半径6 blockの通過可能volumeとVanilla一致の斜め移動安全判定 |
| KnownTraversabilityMap | 出典と鮮度を持つ観測済み通行空間・支持面・遷移 |
| Navigator | KnownTraversabilityMap上の保守的A*と局所再計画 |
| InputArbiter | synthetic入力と物理入力の調停 |
| AgentOverlay | 右下icon、Screen状態button、短いEsc案内の表示 |
| EvaluationTurnGuard | fresh評価のrunner / stream / deadlineへ束縛した入力隔離leaseとterminal解放gate |

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

HttpServerはlisten backlog 16、daemon worker 2 threadの固定executorで動かす。virtual threadや無制限executorは使わない。製品endpointのI/O timeoutは30秒とする。`agent_get_action.wait_timeout_ms`による待機は最大25秒で、Minecraft非依存のAction state monitor上でHTTP workerを1本だけ待たせ、もう1本を状態取得・取消に残す。Minecraft client threadは待機させない。

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

公開lease APIは作らない。Screen buttonのON操作を、同一world session内で明示OFFまで維持される内部許可として扱う。MCP操作ON/OFFはAgentの変更操作を許可するgateであり、内蔵HTTP endpoint自体の起動・停止ではない。OFF中も`agent_get_state`と終了済みActionの参照は可能で、`agent_start_action`だけを拒否する。fresh評価でだけ使う6.6のevaluation-turn leaseは、この公開control stateと直交する非公開control planeであり、MCP操作ONの代わりにはならない。

~~~text
OFF
  └─ Screen右下のMCP操作ON
       v
READY（ON、時間制限なし、明示OFFまで維持）
  ├─ valid agent_start_action → AGENT
  ├─ Esc → READYのまま、VanillaのEsc動作だけ実行（evaluation-turn中を除く）
  └─ OFF / world変更 → OFF

AGENT
  ├─ 能動的危険 → RECOVERING
  ├─ Esc → 現在ActionをEMERGENCY_STOP、入力解除、READY
  └─ success / failure / cancel → 入力解除、READY

RECOVERING
  ├─ Esc → 現在ActionをEMERGENCY_STOP、入力解除、READY
  └─ 安全化 / 回避不能 / recovery budget超過 → 入力解除、READY

全状態
  └─ OFF / world・player消失 → OFF
~~~

Action成功、明示cancel、recoverable / unrecoverableを問わないAction failure、またはAGENT/RECOVERING中の物理Escでは、現在Actionを終了して全synthetic入力を解除した後、同じworld・capabilityのREADYへ戻す。Action failureの`recoverable`はMCP client向けの結果メタデータであり、local authorizationをOFFにする条件には使わない。world変更、明示OFF、endpoint fault、入力解放失敗ではOFFへ戻す。同時に受理するActionは1件だけである。

### 6.3 即時停止条件

- AGENTまたはRECOVERING中の物理Esc押下
- Screen右下のMCP操作OFF
- `agent_cancel_action`
- world unload、dimension変更、respawn、死亡、server disconnect
- player、levelまたは入力所有権の不変条件消失
- 未処理例外、内部状態破損

停止時は同じClientTickまでにAgent所有のsynthetic入力をすべて解除し、pending commandを破棄し、control epochを進めて古いqueue entryを無効化する。実行中EscはActionを`EMERGENCY_STOP`で終了してREADYへ戻し、その後にVanillaのEsc動作も通すため、ゲーム中ならpause menuを開き、Screen上なら通常どおり閉じられる。通常のREADY中のEscはMCMCPの停止処理を起こさずVanillaへそのまま渡すが、evaluation-turn leaseがactiveな間は6.6に従って評価を中断し、入力解放後にVanillaへ渡す。

次は、それ自体では停止条件にしない。

- chat、menu、inventoryなどのScreen表示
- ゲーム画面のfocus喪失
- Esc以外の物理キーボードまたはマウス入力
- healthが固定閾値を下回ったことだけ
- 経路上の支持面または地形が変わったことだけ
- 通常Actionのbudget超過時に、現在進行中の危険が存在する場合

health、支持面、流体、被攻撃などは第10章の危険度判定へ渡し、継続、再計画、緊急回避、停止を分ける。通常Actionのbudgetが尽きても既知の能動的危険が続く場合は、Goal実行だけを止め、固定のrecovery budget内で安全化を優先する。Screen表示を入力隔離やcontrolのglobal STOP条件にはしないが、個別primitiveが操作直前に要求するscreen-clear preconditionは8.5.2に従い、満たさない間は新しいattack / useをdispatchしない。

### 6.4 物理入力の隔離

evaluation-turn leaseがないOFFとREADYではMinecraft本来の入力を変更しない。AGENTとRECOVERING、およびevaluation-turn leaseがactiveなREADYでは、Minecraftウィンドウへ届いた物理キーボード・マウス入力を次の2種類だけ例外として受理し、それ以外はVanillaのgameplay、camera、Screen widgetへ渡さない。

1. Esc
2. MCMCP状態ボタンのhit box内に対する左クリック

Alt+TabなどOSが処理する操作や他アプリの入力は対象外である。物理入力を検出しただけでActionを停止する仕様は廃止する。これにより、chatを開いたままfocusを外してもActionは継続でき、誤った移動、視点、attack、use、inventory操作は混入しない。

実装はNeoForge 26.2の`InputEvent.MouseButton.Pre`、`MouseScrollingEvent`、`InteractionKeyMappingTriggered`、`ScreenEvent`、`MovementInputUpdateEvent`を優先する。`InputEvent.Key`は26.2でcancellableではないため、Client testで完全隔離できないことを確認した場合は、`KeyboardHandler#keyPress`の入口だけに狭いcancellable Mixinを置く。広範なMixin、Access Transformer、OS global hookは使わない。物理mouse turnはAGENT中だけ感度を0へ置き換え、synthetic camera rotationは別の所有経路から適用する。

入力隔離中はVanillaの物理`KeyMapping`をreleaseする。隔離解除時はAgent所有入力がないことの確認だけで完了とせず、`active -> inactive`のfalling edgeで`KeyMapping#setAll`により現在の物理keyboard状態を1回再同期する。runtime pre-tickの前後で隔離状態を照合し、同じclient tick内でevaluation-turn leaseが取得・終了した場合もreleaseまたは復帰を次のfocus / mouse-grab変化へ持ち越さない。隔離が継続するtickでは物理mappingをreleaseしたまま維持し、EscのVanilla pass-through契約は変更しない。

Agent所有の移動は物理`KeyMapping#setDown`として注入せず、各non-paused ClientTickでVanillaの物理入力収集後に、`MovementInputUpdateEvent`から最終movement inputへ`AgentInputState`を適用する。cameraはgame thread上の専用ownerから角速度制限付きdeltaを適用する。これにより、非pauseのchat、inventory、multiplayer pause menuが物理key stateをreleaseしてもAgent入力は失われない。対象版26.2.0.59でevent後に入力が上書きされる場合に限り、最終input更新点1か所へのcancellable Mixinへ置き換え、両経路を併存させない。

Vanillaがsingleplayerを実際にpauseしている間はActionをcancelせず、synthetic入力をupにして実行tickと`max_duration_ms`の計測を凍結する。simulation再開後、world revisionと安全条件を再検証してから続ける。multiplayerなどsimulationが継続しているScreenでは通常どおりAgentを進める。

### 6.5 HUDとScreen操作

ゲーム中、右下には16 × 16 pxの非interactiveな状態iconだけを表示する。文字列、座標、token、常設panelは表示しない。ON/OFF操作はScreen表示中のbuttonだけで行う。全状態でロボットの外形を共通にし、顔を状態ごとの記号へ切り替える。色だけに依存せず状態を区別でき、既存の外縁表示とScreen上の状態文も併用する。

| 状態 | icon表現 | 意味 |
|---|---|---|
| OFF | 灰色のロボット（閉じた目） | MCP変更操作を拒否 |
| READY | 橙色のロボット（開いた目） | ON、Action待機中 |
| EVALUATING | cyanのロボット（顔に「…」） | ON、evaluation-turnの推論中・入力隔離中 |
| CONSENT_PENDING | 緑色のロボット（顔に「？」） | 攻撃確認中 |
| AGENT | 青色のロボット（顔に「▶」） | DSL実行中 |
| RECOVERING | 紫色のロボット（顔に盾） | 緊急回避中 |
| FAULT | 赤色のロボット（顔に「！」） | endpointまたは内部異常 |

緊急回避中は`assets/mcmcp/textures/gui/automation_recovering.png`、確認中は`assets/mcmcp/textures/gui/automation_consent_pending.png`（いずれも16×16、透過PNG）から描画する。画像編集後のJAR再ビルド、または同じパスを持つリソースパックで差し替えられる。共通のHUD背景は画像の外側で描画する。

`EVALUATING`と`FAULT`は6.2のcontrol stateではなく、前者は`READY`へ、後者は`OFF`へ重ねて表示するUI専用presentation stateである。endpoint bind、token初期化、または内部不変条件の異常時はFAULTを優先表示し、実行中Actionがあれば`INTERNAL_ERROR`で終了して入力を解除する。Screen buttonは`MCP操作: FAULT`とし、local error codeはtooltipへ表示する。world参加やONクリックだけでは解除せず、安全なendpoint再初期化に成功するかclientを再起動した場合だけ通常のOFF表示へ戻す。endpointが応答可能なら`agent_get_state.control.mode`は`off`、直前Actionの`end_reason`は`INTERNAL_ERROR`を返す。

evaluation-turn acquire時は「推論中・入力ロック中 — Escで緊急停止」、AGENT開始時は「自動操作中 — Escで緊急停止」という短いoverlay noticeを3秒間だけ出し、その後はiconと外縁だけに戻す。

AGENTまたはRECOVERING中は、gameplayとScreenの双方でゲーム画面の外縁へ2 pxの黄色枠を常時表示する。evaluation-turn leaseがactiveでActionを実行していない推論区間は2 pxのcyan枠を表示し、Action開始と同じframeでyellowへ切り替え、Action terminal後もleaseがactiveならcyanへ戻す。通常のREADY、OFF、FAULTでは表示しない。evaluation-turnの色は公開control stateや`agent_get_state.control.mode`を増やさないUI専用presentationである。

chat、inventory、pause menuを含む任意の`Screen`表示中は、「icon + 状態文 + MCP操作 ON/OFF」の1 buttonを追加する。原則は右下だが、chatでは入力欄と候補一覧を塞がないよう右上へ配置する。

- OFFでworldとplayerが有効: `MCP操作: OFF`
- READY: `MCP操作: ON / 待機中`
- EVALUATING: `MCP操作: ON / 推論中`
- AGENT: `MCP操作: ON / 実行中`
- RECOVERING: `MCP操作: ON / 緊急回避中`
- FAULT: `MCP操作: FAULT`
- worldなし、死亡画面、接続中: 状態を表示するがONはdisabled

クリック時の操作説明とFAULTのlocal error codeはtooltipへ置き、button本文を状態だけにする。button幅は全状態の最長文ではなく、現在の状態文にiconとpaddingを加えた幅へ毎frame追従して画面占有を抑える。OFFクリックはActionを`USER_DISABLED`で終了し、入力を解除してOFFへ戻す。ボタン自身のclickだけは6.4の入力隔離を通過する。buttonはnarration textとkeyboard focusを持つが、AGENT中のkeyboard activationはEsc以外を遮断するためmouse click専用である。

ゲームHUDは`RegisterGuiLayersEvent`、Screen buttonは`ScreenEvent.Init.Post`で追加し、既存Screen classを置換しない。`Minecraft.screen == null`のframeだけgameplay iconを描画し、Screen表示中はHUD側iconを描かず、button内のiconと状態文へ置き換える。右marginと下margin（chatでは上margin）は既定8 px、既存MODと重なる場合のためoffsetだけをclient configで変更可能にする。

### 6.6 評価中のevaluation-turn lease

fresh MCP-only評価runnerは、read-only preflightとthread作成後にpreliminary readinessを確認し、T0を記録する前にBearer認証済みloopbackの内部control planeからevaluation-turn leaseを1件だけ獲得する。leaseはUUID、現在のworld session、runner process IDとprocess start、接続中のcontrol stream、有限のhard deadlineへ束縛する。獲得にはMCP操作が`READY`、world / playerが有効、Actionなし、入力ownerなしを要求し、解放済み入力を確認してからactiveを公開する。active公開後、runnerはlease header付き`agent_get_state`でauthoritative T0 readinessを再確認し、preliminary checkとの間に起きた物理入力変化をT0へ持ち越さない。評価中の公開5 Tool requestには内部lease headerを付け、activeな同一leaseでなければforwardを拒否する。

leaseがactiveな間は、モデルが推論していてActionがない区間も6.4の物理入力隔離を維持する。表示は6.5のcyan / yellowで区別する。別Windows TerminalのmonitorはMinecraft入力所有権を持たず、control streamを読み取るだけとする。

次のいずれかを検出した場合は、現在Actionをpriority stopし、pending commandを無効化し、Agent所有input・使用/破壊状態・追跡velocityと評価用物理入力隔離を解放する。入力ownerがないことを確認した後でだけlease terminalをcontrol streamへ公開する。

- 物理Esc
- Screen上のMCP操作OFF
- world unload、dimension変更、respawn、死亡、server disconnect
- client shutdown
- runner process終了
- control stream切断
- lease hard deadline

物理Escで終わった評価runは失敗とする。ただし入力解放に成功し、同じworldでMCP操作ONが有効なら、Actionを`EMERGENCY_STOP`として通常どおり`READY`へ戻し、UIのON leaseは維持する。UI OFF、world境界、shutdownは従来どおり`OFF`、入力解放を確認できない場合は安全faultとして`OFF`へlockする。runner process終了、stream切断、deadlineも評価を失敗させるが、同じworldと有効なUI許可が残り、入力解放を確認できた場合は`READY`を維持する。正常なturn completionでも、runnerは同じ停止・解放確認gateを明示的に通してからleaseをreleaseし、visible child processを終了する。

### チャット中の継続実行と待機中の手動入力

非pauseのChatScreenはワールド操作を妨げない画面として、入力保持・移動・破壊・設置・設備操作前の共通判定で許可する。チャット本文は読まず送信しない。pause、overlay、その他の画面、health、hazard、対象照準・到達範囲・権限・時間予算は引き続き検査する。コンテナ操作が始まった後のexact Menu / Screen所有権は別の安全条件として維持し、チャットをコンテナの代用にしない。ローカル攻撃承認画面を開くときは既存画面との競合を防ぐ。

Actionがない通常tickは入力操作を行わない。terminal時の入力解放と未完了の解放retryは維持するが、待機中に毎tick `stopUsingItem` / `releaseUsingItem` を呼んではならない。利用者が手動で行う弓の引き絞り、飲食、採掘をMCMCPの待機処理で中断しない。

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
- current ClientLevelで受理したposition sound eventと第7.5節のentity hint
- damage eventで通知された原因、直接原因、発生XYZ
- ユーザーが明示入力した地点、農地、建築box
- 同じworld session内で上記から得た記憶

`client_tick`と`world_revision`はworld session内の単調増加longとして`agent_get_state.world`にも公開する。revisionはblock state更新、chunk load/unload、world境界変更の処理後に増やし、entityの通常移動やsound再生だけでは増やさない。world unload、respawn、dimension変更では観測・Mapを消去した上で新sessionを0から開始する。LLMとruntimeはrecordのtick/revisionを現在値と比較して鮮度を判断する。

global `world_revision`とは別に、部分的な全周scanを破棄する`visual_revision`を内部で持つ。全block mutationは前者と監査ledgerを進めるが、後者を進めるのはcollision、遮蔽、support、fluid、hazardへ影響するLOCAL mutationとchunk/all失効だけである。wheat age、air↔wheat、farmland moistureのようなnavigation-neutral更新は部分scanを破棄しない。surface evidenceの鮮度下限は「直近LOCAL mutationのglobal revision」「同じBlockPosの直近mutation revision」「boundedな位置別ledgerからevictした最大revision」の最大値とし、対象と無関係なneutral mutationだけでは既知面を失効させない。recordのrevisionがこの下限未満または現在revisionより未来なら拒否する。

更新が多い環境で全周frameの完成からActionの受付・予約・実行開始までに表面が失効した場合、配送済み静的表面だけを内部planning viewで再観測する。現在のeyeから以前のray hitへ通常の全周観測と同じray tracerを使い、現在のfog/radius、load、描画判定、遮蔽を適用する。位置・面・block・公開BlockState・placement item・shapeが一致した表面にだけ実再観測tick/revisionを付ける。再観測に失敗した表面は古いrevisionのまま残し、静的な向き変更の根拠としての従来の用途を維持する。mutation・approachの鮮度下限を通過する根拠としては使用できない。対象は配送メモリ上限2,048面以内で各面1 ray、各rayの訪問cell上限128を維持する。公開frameとそのcursorは変更せず、配送期限も延長しない。受付snapshotと予約は観測収集後のpre-tickで作成し、rendererのfogとentity tickの一致を保つ。内部frameの完了tickが進んだ場合、音の元の観測tickとrevisionを保持してageだけを増加させ、600 ticksを超えた音は除去する。新しい対象やentity、ラベル、sound、traversabilityをこの経路で取得・延命せず、以降の鮮度・姿勢・reach・server ACKとmenu所有権の検証は従来通り行う。

### 7.3 Omnidirectional Visual Observation

rendererのfog値はlevel・camera entity・entity tickの完全一致を要求する。低FPS等で現在tickのsampleがない場合、距離1ブロックの霧として扱わず、visual scanと配送済み表面の内部再観測だけを待機する。局所安全観測と音は継続し、旧frame/recordのtick・revision・配送期限は延長しない。欠測を跨いだclient tick数もcatch-up期限へ加算するため、期限後の最初の新鮮sampleでは部分scanを破棄し、最大2,048 rayで再観測する。描画が完全停止している間は新しいvisual情報を取得できない。実際にrendererが返した短いfog距離は引き続き適用する。

chest/barrelの同じ可視面では、最初のrayと各隅方向に最も近い実rayを内部で最大5件保持し、frame完成時の可視entity boundsを避ける実rayを優先して1件だけ配送する。候補の位置・eye・tick・revisionは元の観測値のままとし、幾何学的な新しい点を観測として生成しない。全候補がinteractionに遮られていてもblockの実visual情報は残す。container/craftingのplannerは配送済みray候補の中から可視entity boundsに遮られない一点を選び、その一点で既存camera上限と後続姿勢を計算する。候補がなければTARGET_UNKNOWNとして新しい観測や再配置を要求し、liveの通常crosshairと全安全gateの検証を省略しない。照準到達後40 client ticksでexact target hitを確認できなければCONTAINER_AIM_OCCLUDEDで有限終了する。

entity候補は、現在の通常block interaction range内を先に、残りの枠でその外側を収集する。両方のqueryで現在のfog/radiusを上限にし、合計129候補（公開128件＋打切り検出1件）を追加前にも検証する。NeoForgeのentity part追加経路も同じ上限へ含める。公開前の不可視除外と通常LOSを維持し、AABBの一部だけが範囲内でもfog外の確認点へrayを飛ばさない。

角度のfloat化とVanillaの三角関数による照準の微小なずれを避けるため、entity AABBを選択判定時だけ0.01 block広げ、照準面の縁から0.001 block以内の候補を使わない。chestの公開stateがnullでも、通常形状で外縁になり得る座標の細い帯だけを保守的に避ける。4隅方向のランキングは外縁から0.02 block内側を基準にするが、その位置に新しいrayを生成せず、観測した実rayだけを保持する。失敗時の公開evidenceは従来の`container_aim_occluded`を保持し、通常crosshairの固定分類`container_crosshair=entity / block_other / miss / unavailable / world_border`を1件だけ追加する。座標・entity ID・未知のadapter文字列は診断へ追加しない。

観測原点はthird-person cameraではなくplayer eye positionとし、水平360度・上下180度をworld軸固定のdeterministic equal-area ray集合で観測する。方向集合は2,048方向へ固定し、既定は1 ClientTickあたり256方向、8 active tickで1 frameを更新する。ray/tickは64〜512のlocal performance設定内で調整でき、frame所要tickは`ceil(2048 / rays_per_tick)`となる。半径は`min(configured radius, 32 block, current fog distance, loaded boundary)`で、`sampling_coverage=1.0`は予定した全方向を更新済みという意味であり、連続球面の完全走査を意味しない。

visual revision変更では部分rayを破棄するが、再開始を含む未完成tick数は保持する。通常scan周期の2倍（既定16 active tick）に達した場合、そのtickのcurrent player eye・fog・world revisionで2,048方向を一括再観測して完成させる。失効rayを再利用せず、通常の遮蔽・半径・record上限を維持する。catch-upの上限は1 tickにつき2,048 rayで、完了・world reset・dimension変更・tick巻き戻りでは未完成counterをリセットする。局所経路、hazard、entity、音もこの完成frameへ新しい値を合成する。通常ray/tick設定は平常時の分割量であり、catch-upの単一tick負荷は別途実環境で確認する。

frameは複数tickのtemporal compositeであり、単一時刻・単一原点の球面snapshotとは表現しない。各visual recordへ、そのrayまたはentity line-of-sightを採った時点の`eye_origin`、`observed_tick`、`world_revision`を付ける。Local Observation Volume由来recordには`observer_position`、sound clueには最終観測時の`world_revision`を付ける。frame responseの`frame_completed_tick`から各recordの鮮度を計算できるようにする。

観測はcamera yaw/pitch、FOV、Screen、focusに依存せず、Minecraft入力やcamera回転量を一切発生させない。背後でもeye位置から見通せるsurface/entityは取得できるが、opaque wallの裏は`UNKNOWN`のままとする。

rayは次を別channelで評価する。

| channel | 用途 |
|---|---|
| OUTLINE | interaction対象面 |
| VISUAL | 視覚遮蔽、visible surface、透過後の有限segment |
| COLLIDER | Local Observation Volumeの衝突形状。visual判定の代用にはしない |

glassのように視覚を通すが衝突するblock、slab、stairs、fence、trapdoor、snow、waterlogged blockを`air/solid`二値へ潰さない。visible surfaceには視覚で判別可能なblock ID、位置、面、shape classを記録する。透過面はその面を記録してrayを継続するが、1 frameのvisual surfaceは最大8,192件、unknown boundaryは最大4,096件、全recordは最大16,384件に固定する。surface上限へ達したrayは最初の未収録位置を`AMBIGUOUS_RENDER`境界に落とし、既知として続行しない。custom renderer、alpha semantics、未ロード境界、shape内部開始、例外も`UNKNOWN`とする。

近傍entityは半径内のbounded query後、eye位置からAABBの複数sample点へのVISUAL line-of-sightでfilterする。1点以上が遮蔽されていなければ正確なEntityType、AABB、XYZ、velocityをvisible recordへ出せる。さらに、この可視判定を通過した非playerだけについて24文字のopaque `entity_ref`を発行する。refはserver側でworld session、dimension、EntityType、内部UUIDへ束縛し、最新の可視観測から100 client tickで解決不能になる。playerでは常にnullとし、raw UUIDは一切公開しない。ref単独は現在可視性・reach・操作許可を証明せず、将来のconsumerもdispatch直前に同じ実entityのalive、type、line-of-sight、通常reachを再検証する。`minecraft:item`のときだけ、実際に描画されるnon-empty ItemStackのregistry item IDを任意field `displayed_item`として併記する。これは落下物の見た目に対するsemantic labelであり、stack count、data component、UUID、owner、pickup delay、age、NBTは公開しない。emptyまたはregistry不明のdisplay stackはidentityを推測せず、そのentity recordを省略して`visible_entities_truncated=true`とする。非ItemEntityへ`displayed_item`を付けることも拒否する。inventory、AI target、壁裏entityは公開しない。sparse rayが小さいentityを偶然外すことは、このentity専用line-of-sightで補う。

可視な`minecraft:item_frame` / `minecraft:glow_item_frame`がnon-empty itemを表示し、その支持blockが同じcompleted frameでpolicy-visibleな単一の木製chest、copper chestまたはbarrelである場合だけ、`visible_entity.container_label={item,container_position,container_block,attachment_face}`を返せる。`displayed_item`は落下ItemEntity専用のまま流用せず、count、component、NBTも公開しない。double chestは額縁1個から論理container全体との対応を一意に証明できないため、この初期sliceではlabelを返さない。label itemはexact registry IDであり、カテゴリやtagへの推測は行わない。

落下物は`collect_visible_item(displayed_item,target)`で1つずつ回収できる。LLMは最新frameの`entity_type=minecraft:item`、`displayed_item`、連続値XYZを丸めずnodeへコピーする。MODはそのwitnessに近い既知かつ安全なTraversability終点NavCellを解析的に選び、既知経路と毎tickのswept-AABB safety gateを通って接近する。pickup候補はVanillaのplayer AABB拡張判定をNavCell上へ有界に近似して絞り、到達可能な候補のうち実行時間が最小の経路を選ぶ。終点では実player AABBを`inflate(1.0, 0.5, 1.0)`した領域とfreshなitem AABBの交差を改めて必須にするため、抽象cellの高さだけで取得可能と断定しない。落下中のentity XYZからYを機械的にfloorしてNavCellを捏造しない。成功条件は対象item IDのinventory絶対個数が当該node occurrence開始時より増えることで、entityの消失だけでは成功にしない。経路中もfreshなvisible witnessと予定pickup cellの交差を再確認する。提出した連続座標から0.75 block以内に同じ`displayed_item`のfresh matching witnessが残る一方、旧pickup cellだけが失効した場合は、入力を即時解放し、旧到着状態とpickup cellを捨て、当該node occurrenceのinventory baselineを保持したまま、最新frame・既知経路・pickup AABB・局所安全性から内部で有界に再bindする。公開recordはentity UUIDを持たないため、別個体やmerge後の同種itemを区別した同一性は主張しない。有効なoccurrence / Action期限は再計画で延長せず、期限内に安全な経路を再証明できない場合、witnessが消滅した場合、0.75 block外へ移動した場合は`PATH_BLOCKED`で終了してLLMへ戻す。到着後は手投げitemの40 tick pickup delayも包含する60 tickだけ確認する。entityの通常移動は`world_revision`を更新しないため、1回のvisual scan周期より古いframeをitem追跡の継続証拠にせず、未知領域や危険領域へ落下物を追わない。

ray結果は`HIT / MISS / UNKNOWN`の三値とする。`MISS`が証明するのは検証済み有限segmentだけで、周辺cell、曲がり角、終端の裏側を既知にしない。

### 7.4 Local Observation Volumeと斜めswept-AABB

全周visualとは別に、運動安全用としてcurrent player AABBを中心とするEuclidean半径6 block、最大128 transitionのLocal Observation Volumeを毎tick維持する。同期するgame thread上の仮想transition評価は近傍優先BFSで最大512件へ固定し、予算外のcellは推測せず未知のまま残す。player AABBが実際に通過可能な隣接transitionだけを展開し、solid、閉じたdoor、通れない隙間、unloaded、`UNKNOWN`で展開を止める。広域air flood-fillにはしない。

斜め移動では、current AABBと`AABB.move(intendedDelta)`を包む直方体に触れたblockをすべて衝突扱いしてはいけない。その包絡AABBは候補VoxelShapeを集めるbroad phaseだけに使い、矩形の角にあるが実際の移動軌跡と交差しないblockは除外する。

collision解決の規範値はVanilla 26.2のresolverが返す`resolvedDelta`とする。実装は`Entity#move`内の`collide(Vec3)`呼出を、bot制御中のlocal playerかつ対象版のplayer movement用`MoverType`だけ狭いMixinExtras `@WrapOperation`で包み、`intendedDelta`と`resolvedDelta`を同じgame threadで記録する。`resolvedDelta`は衝突解決済み候補であり、許可後の実移動証拠にはtick前後のplayer位置差分を使う。独自のpoint ray、単純な対角直線、固定substepをsolid collisionの真値にしない。これにより斜めのaxis解決、corner slide、world border、entity collision、step-up、slab、stairs、fence等をVanilla結果へ一致させる。MixinExtrasはNeoForge同梱分を使い、新しいruntime dependencyを追加しない。

hypothetical transitionは同じVoxelShapeとVanillaのaxis順で保守的に評価し、実移動または接触で確認するまで`PROBE_ALLOWED`を越えて昇格させない。axis順は`intendedDelta`について`|x| < |z|`ならY→Z→X、それ以外はY→X→Zとし、`|x| = |z|`はXを先にして決定的にする。各axis segmentの長さには`resolvedDelta`の対応成分を使い、そのsegment開始AABBを`expandTowards(axisDelta)`した領域だけを実通過領域へ加える。最後に全segmentを1個の包絡箱へ潰さない。

斜めpathのfluidは各axis segmentのswept player AABBと`FluidState#getAABB`相当の実高さ・形状との交差で接触を判定し、接触した`FluidState#getFluidType()`で危険度を分類する。包絡矩形の未通過cornerにあるfluidは接触扱いせず、途中segmentで触れたfluidはendpointが乾いていても記録する。未知のmodded FluidTypeは`UNKNOWN → REPLAN`とし、一律STOPにしない。非流体blockのinside判定は別にblock側のinside collision shapeとVanilla通過結果を使う。

supportは最終AABB直下1e-6 blockの薄いslabを`findSupportingBlock`へ渡し、交差する支持blockが存在するかだけを確認する。返る代表BlockPosは移動前playerとの距離で選ばれ得るため、予測supportのidentityや面積としてMapへ保存しない。supportがなければ下方向collisionから実落差を求める。通常歩行の許容落差を越える場合はAgent由来成分だけをneutralにしてREPLANし、重力、knockback、piston等の外力をゼロにしない。step-up候補は`maxUpStep`と頭上clearanceを含むVanilla結果へ従う。複数tick先を一括simulationせず、1回のVanilla moveごとに再観測する。

各movement heartbeat直前には、現在の実AABB、camera yaw、発行予定keyからworld座標系の正規化deltaを再構成して早期検証する。最終gateは`Entity.move(SELF, intendedDelta)`の`maybeBackOffFromEdge`直後・private `collide(Vec3)`直前に置き、慣性、knockback、jump、step-upを含むVanillaの実deltaを同じresolverでpreviewして、残distance budgetと局所安全条件を再検証する。proofはplayer identity、level identity、world revision、1 player tickへ束縛し、直前のreconciliation revisionが変化していれば入力を拒否する。実移動traceにも発行時revisionを保持し、次の観測までにrevisionが変わったtraceは`CONTACT`へ昇格させない。

Agent由来の加速・jumpはtick間で別台帳へ保持し、Vanillaのcollision、stuck reset、block speed factor、ground friction、air dragと同じ変換だけをその成分へ適用する。serverのvelocity全置換packetでは旧成分を破棄し、explosion等の加算外力では保持する。未証明、再計画、primitive完了、cancel、OFF、Escでは、その時点で追跡できるAgent成分だけを実velocityから差し引き、外力は残す。通常navigationが水、溶岩、騎乗、elytra、creative flightへ入った場合、またはcollision endpointの支持blockがbounce restitutionを持つ場合は、未実装のfluid/flying/restitution変換を推測せず移動前にneutralizeしてREPLANする。整数NavCell中心や経路corridorだけを、斜め入力の許可根拠にはしない。

Volumeから外へ出せるのはsupport、clearance、transition、fluid、suffocation、hazard、loaded/unknownの派生値だけである。raw block ID、ore、container、block entity、構造名は捨てる。候補VoxelShapeを集める包絡broad phaseにcellが入っただけでは、BLOCKED、fluid接触、support、HAZARDへ昇格させない。

### 7.5 position soundとentity hint

`PlayLevelSoundEvent.AtPosition`をLOWEST priorityで受け、cancelされておらず、levelがcurrent `ClientLevel`と同一であるposition soundだけを採用する。イベントの`Holder<SoundEvent>`、level、audio objectは保持せず、raw event ID、category、絶対XYZだけを即座にimmutable queue valueへ写す。audio source生成より前のlevel eventを使うためmaster volume 0やheadless audio deviceの有無に依存しない一方、実際にユーザーへ聞こえたことの証明とはしない。`AtEntity`は釣りのsplash検出に不要なので対象外とする。raw `sound_event`、category、dimension、絶対XYZ、first/last observed tick、age、occurrences、provenanceに加え、event IDから熟練者相当の`entity_hint`をbest-effortで生成し、LLMへ公開する。

Vanillaの`minecraft:entity.<candidate>.*`は、candidateがclientのEntityType registryに存在すればそのresource locationを`entity_hint`へ入れる。parrot imitationなら発音主体である`minecraft:parrot`、generic/shared/unmappedならnullとする。modded eventも同じ命名規約でregistry照合できる場合だけhintを付ける。raw `sound_event`は変換せず併記するため、LLMは`parrot.imitate.zombie`等の追加意味を自力で判断できる。

| sound_event例 | entity_hint |
|---|---|
| minecraft:entity.zombie.ambient | minecraft:zombie |
| minecraft:entity.skeleton.step | minecraft:skeleton |
| minecraft:entity.creeper.primed | minecraft:creeper |
| minecraft:entity.parrot.imitate.zombie | minecraft:parrot |
| minecraft:entity.generic.explode | null |

clueは最大32件、最終観測から600 active ClientTick保持する。同じdimension/event/categoryで、10 tick以内かつEuclidean距離2 block以内の音は1件へ集約する。複数候補があれば距離が最短、`last_observed_tick`が最新、作成順が最古の順で1件を決め、`occurrences`と最新XYZ/tickを更新する。`occurrences`は受理したlevel sound event数であり、可聴再生回数やentity数の証明ではない。上限超過で未期限切れclueを捨てた場合、捨てたclueが本来600 tickで失効する時点までframe summaryの`recent_sound_clues_truncated`をtrueにする。world unload、respawn、dimension変更で全消去する。immutable frame内の`age_ticks`はframe完成tickで固定し、page取得時刻では増加させない。

追加の推定評価fieldは公開しない。`entity_hint`はevent IDの正規化補助であり、実entityの存在証明ではない。`/playsound`やMODでも同じeventを再生できるため、実在・個体数・現在位置の判断はraw ID、鮮度、視覚、damage等を合わせてLLMが行う。

LLMはraw event ID、entity hint、鮮度を視覚・damage情報と合わせて判断できる。ただしsound単独では、entity UUID、個体数、現在位置、通路、洞窟、block、支持面を確定せず、Known Traversability Map、既知target、Local Observation Volumeを更新しない。`navigate_to_sound`や`attack_sound`は作らず、soundだけでMODが移動、攻撃、RECOVER、STOPを開始しない。音源XYZを通常Actionへ使う場合も、別の証拠で既知になったtarget/pathが必要である。Phase 1 DSL predicateにはsoundを追加しない。

`AtEntity`、UI、music、cancel済みevent、非current levelのeventにはworld XYZ clueを作らない。subtitle本文、raw audio、resource-pack file pathも公開しない。

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
- `LOCAL_VOLUME`: 半径6 block内で検証したsupport、clearance、transition、fluid
- `CONTACT`: 実衝突、実移動、成功したinteraction
- `SOUND`: Map更新禁止

状態は次の4値とする。

| 状態 | 意味 | 使用可否 |
|---|---|---|
| CONFIRMED | locomotion別のsupport / contact、clearance、transitionが有効 | 通常経路に使用 |
| PROBE_ALLOWED | 地上supportまたは完全なladder / scaffolding接触を確認済みで、transitionの一部が未確定 | 低速の1 micro-stepだけ許可し、actual resolverで再検証 |
| BLOCKED | actual collision、危険流体、支持不能を確認 | 使用禁止 |
| STALE | revisionまたは鮮度が失効 | 再観測まで通常使用禁止 |

全周visual rayだけでplayer AABB全体のclearanceを確定しない。`CONFIRMED` transitionには`LOCAL_VOLUME`または`CONTACT`証拠を必要とする。edgeは内部の`GROUND / LADDER / SCAFFOLDING` locomotionを保持し、床supportなしを許すのは完全な`minecraft:ladder`、または乾いて安定した`minecraft:scaffolding`との接触、clearance、非fluid、非hazardを同時に証明したtransitだけとする。中間段は内部経路には保持するが、床supportのあるlandingだけを公開目的地にする。地形変更では影響cell/edgeだけをSTALEにし、現在AABBが危険でなければ停止せず局所再計画する。Mapはworld session内のメモリだけに保持する。

### 7.9 限界

全周観測はcameraを回さず、人間が同じ位置で見回せば得られる情報を短時間でまとめて取得する意図的なassistである。2048 sampleは連続球面の完全走査ではなく、透明・custom renderingにも`UNKNOWN`が残る。client MODは同期済みchunkを技術的には読めるため、Policyとnon-interference testで不使用を検証するが、悪意ある改変に対する外部証明はできない。

Local Observation Volume外の未知危険、opaque wall裏、未ロード領域は事前に分からないため、完全無事故は保証しない。

## 8. MCP endpoint

### 8.1 transport

- 公開Endpoint: http://127.0.0.1:8765/mcp
- Transport: Streamable HTTP
- 製品基準: MCP 2026-07-28
- Codex互換: MCP 2025-06-18形式の`initialize`、`notifications/initialized`、`tools/list`、`tools/call`のみ
- 通信形式: stateless / POST-only / JSON response（`notifications/initialized`のみ202 empty）
- 同時に実行できるTask: 1件
- stdio: 非対応
- 公開EndpointのGET / DELETE、protocol session、長時間SSE: 非対応
- LAN bind、0.0.0.0: 非対応

Minecraftはすでに起動しているプロセスなので、MCP clientがsubprocessを起動するstdioは適さない。Streamable HTTPを使う。

MCP 2026-07-28は各requestが自己完結するstateless仕様であり、GET streamとprotocol-level sessionを廃止している。MVPはTools server profileだけを使い、各POSTへ単一JSON responseを返す。Codex互換経路もGET、SSE、session IDを使わず、公開Toolを5個から増やさない。

JDK 25標準のHttpServerとMinecraft同梱Gsonでclean-room実装する。Spring、Node、Servlet containerは追加しない。公式MCP Java SDK 2.0.1はMCP 2025-11-25世代で、現行stateless仕様へ未対応のため採用しない。SDK 3.x以降が現行仕様へ対応した時点でのみ再評価する。

### 8.2 security

- プロファイルの初回起動時に256-bit bearer tokenをSecureRandomで生成し、以後は同じowner-onlyファイルを再利用する。通常起動ではrotateしない
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
- world未参加時もserver/discover / initialize、tools/list、tools/call(name=agent_get_state)は応答
- worldがなければ操作系callはNO_WORLD
- client終了時にserverとexecutorをclose
- endpoint例外はgame threadへ伝播させない

クラウド上だけで動くMCP clientは127.0.0.1へ接続できない。利用には、同じPCで動作しStreamable HTTPへ接続できるMCP hostが必要である。外部tunnelやbridgeは今回の除外対象とする。

### 8.4 request contract

認証、loopback / Host / Origin、Content-Type、body上限、JSON depth、rate / concurrencyの検証は2026製品経路とCodex互換経路で共通にする。どちらもbodyは単一のJSON-RPC 2.0 objectであり、batch、GET、SSE、protocol sessionは受理しない。

2026-07-28製品経路のすべてのPOSTで次を検証する。

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

2026-07-28製品経路で成功する全JSON-RPC responseの`result._meta`へ`io.modelcontextprotocol/serverInfo`（name=`mcmcp`、version=`0.1.0`）を付ける。Codex互換経路はlegacy clientが解釈できる標準fieldだけへ射影し、`initialize`では`protocolVersion` / `capabilities` / `serverInfo`、`tools/list`では`tools`、`tools/call`では`content` / 任意の`structuredContent` / `isError`だけを返す。両経路のHTTP JSON responseはContent-Typeを`application/json`、文字encodingをUTF-8とする。

Codex CLI 0.146.1互換経路は、実wire captureと同じ次の並びに限定する。

1. `initialize`: IDあり、paramsは`protocolVersion`、object型`capabilities`、`name` / `version`（`title`は任意）の`clientInfo`だけとし、protocol versionは`2025-06-18`に固定する。`MCP-Protocol-Version`、`Mcp-Method`、`Mcp-Name`は受理しない。`protocolVersion`、Tools capability、serverInfoを標準initialize resultで返す。
2. `notifications/initialized`: IDなし、paramsは省略または空object、`MCP-Protocol-Version: 2025-06-18`、custom MCP headerなし。HTTP 202、empty bodyを返す。
3. `tools/list`: IDあり、`MCP-Protocol-Version: 2025-06-18`、custom MCP headerなし。固定5 Toolの標準resultを返す。`params._meta.progressToken`は許可するが、任意のpaginationやTool追加には使わない。
4. `tools/call`: IDあり、同protocol header、custom MCP headerなし。`params.name`は固定5 Toolのいずれかとする。`params.arguments`は省略時に空objectとして扱い、存在する場合はobjectかつ別紙schema適合を必須とする。

互換経路の`params._meta`では`progressToken`に加え、Codexが送る`callId` / `itemId` / `threadId`（空白だけでない128文字以下のstring）と`x-codex-turn-metadata`（object）を許可する。これらは通信の付加情報として破棄し、runtimeへ渡さず、responseやlogへ反射しない。内部のsandbox / approval等の申告は操作権限として扱わない。未知の直下fieldと型違反は引き続き拒否し、既存のbody / JSON上限も適用する。

`initialize`に2026 headerを付ける、`server/discover`に2025 versionを付ける、compatibility methodへ`Mcp-Method` / `Mcp-Name`を混ぜる、notificationにIDを付けるなどの経路混同はfail closedにする。互換経路でもBearer、Origin、Host、body、rate-limitに例外を作らず、`Mcp-Session-Id`は発行・受理しない。

MVPで実装するmethod:

- server/discover
- initialize
- notifications/initialized
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

Resources、Prompts、Tasks extension、Subscriptions、Sampling、Elicitation、SSEは実装しない。Codexが`tools/list`へ付けるprogress tokenは受理するが、progress notificationは送信しない。

#### 8.4.1 fresh評価専用の内部control plane

`/mcp/internal/evaluation-turn`はfresh MCP-only評価にだけ使う非公開endpointである。公開MCP endpointと同じ`127.0.0.1` bind、Host / Origin検証、Bearer認証、body / JSON上限を適用し、認証迂回、proxy、redirectを許可しない。POSTはrunner process ID、lease UUID、有限の最大時間を検証して6.6のleaseを獲得し、connection closeを検出できるevent-driven control streamを返す。DELETEは同じleaseを明示終了する。未知method、別lease、過大deadline、dead process、同時2件目はfail closedにする。

評価中に公開`/mcp`へforwardするrequestには`Mcmcp-Evaluation-Lease` headerを1件だけ付ける。HTTP受付時のactive lease IDまたはlease不在をcall contextへ束縛し、Minecraft client threadのwork開始直前とdelivery confirm / abandonでもlive guardへ再照合する。これにより、header検証後のacquire / Esc / releaseと遅延requestの競合をfail closedにする。header値はresponse、log、monitor、artifactへ生で残さない。このendpointとheaderはMCP method / Tool / resourceではなく、`server/discover`、`initialize`、固定5 Toolのcatalog、`tools/list`、dynamic Tool schemaへ追加しない。公開endpointのPOST-only契約と、内部control planeのPOST / DELETE / stream契約を混同しない。

### 8.5 Tools

| Tool | 変更 | 内容 |
|---|---:|---|
| agent_get_state | No | player、inventory集計、policy、DSL capability、Agent状態、最新観測frame概要を取得 |
| agent_get_observation | No | 最新の全周visual、局所traversability、hazard、sound clueをframe単位でpage取得 |
| agent_start_action | Yes | READY状態で検証済みAction DSL v1を1件開始 |
| agent_get_action | No | Action、現在node、resource counter、回避、失敗、traceを取得。任意でterminalまで最大25秒待機。include_container_results=trueで検査済みコンテナの全品目をページ取得 |
| agent_cancel_action | Yes | actionを冪等にcancel |

raw key、raw mouse、packet、任意commandを操作するToolは公開しない。`agent_start_action`がLLM生成DSLの検証・実行口を兼ねるため、template専用ToolやDSL実行Toolを追加しない。`agent_get_observation`は読み取り専用で、OFF中も使用できる。

`tools/list`は上表の順序で固定し、各ToolへJSON Schema 2020-12のinputSchemaとoutputSchemaを付ける。2026製品経路では単一pageの`resultType: "complete"`として返し、必須cache hintは`ttlMs: 0`、`cacheScope: "private"`とする。Codex互換経路では同じ5 Tool配列だけを標準`tools` fieldへ射影する。Tool一覧は実行中に変えず、`listChanged`はfalseとする。

Toolの規範的なname、description、inputSchema、outputSchemaは別紙`MCMCP_MCP_Tool_Catalog.json`とする。Java実装、`tools/list`、schema unit testは同じcatalog内容から生成または照合し、別々の手書き定義を持たない。

2026製品経路の全Tool成功応答は`resultType: "complete"`、`isError: false`、outputSchemaに一致する`structuredContent`を返す。Codex互換経路では`resultType`と`_meta`を除き、同じ`structuredContent`と`isError`を返す。両経路とも同じJSONを直列化したTextContentを`content`へ1件入れる。業務上の拒否や実行失敗は`isError: true`とし、success用outputSchemaとの混同を避けるため`structuredContent`を付けず、`content`へ`code`、`message`、`recoverable`を持つJSON文字列を返す。未知Toolや壊れたrequestはJSON-RPC errorとする。以下の応答例は成功時`structuredContent`の中身を示す。

#### 8.5.1 Observation frame

`agent_get_state.observation`は、大量の観測recordそのものではなく、`latest_frame_id`、設定観測半径、全方位対応、oldest/newest tick、`sampling_coverage=1`、返却可能なkind別件数、sound切り捨て有無だけを返す。`record_counts.visible_surface`はray face総数ではなく、後述の代表面圧縮後に返却できるunique block position数である。方向ごとの実効終端は`unknown_boundary`で示し、単一の実効半径へ丸めない。world未参加時と最初の完成frame生成前はnullとする。

全周visualは既定8 active ClientTick、設定変更時は`ceil(2048 / rays_per_tick)` tickで1 immutable frameを完成させる。完成前のframeを公開せず、内部rolling保持は最新2 frameとする。これとは別に、`agent_get_state`がLLMへ告知した`latest_frame_id`を最大16件、合計65,536 record以下のLRU handleとして保持する。同じIDの再告知またはそのIDによる初回page取得でidle期限を更新し、最終accessから60秒、handle件数またはrecord予算超過時のLRU evictionで失効する。

`cursor=null`の初回pageが続きpageを必要とする場合だけ、そのframeを別のpagination leaseへpinする。未完了leaseは同時最大2件で、上限中の3件目は`SERVER_BUSY`を返す。`next_cursor=null`の最終pageを生成したleaseは即座に未完了枠を解放するが、同じcursorの再送へ同じpageを返すため、完了leaseを最終access順のLRU最大2件だけ保持する。3件目の完了時は最終accessが最も古い完了leaseと全cursorを破棄し、以後の再送は`INVALID_CURSOR`とする。未完了・完了とも最終accessから60秒、初回accessから最大5分で失効し、時間は`System.nanoTime`で測る。これにより保持量を固定上限へ抑えながら、frame生成速度とLLMの推論待ちを分離し、読み切ったqueryが次のpaginationを不必要に阻害しない。announced handle、rolling frame、pagination leaseのいずれにも保持されないIDは`FRAME_EXPIRED`を返す。world unload、respawn、dimension変更で全frame、announced handle、lease、cursorを破棄する。

`agent_get_observation`入力:

- `frame_id`: `agent_get_state`が返したID
- `kinds`: `visible_surface / visible_entity / traversability / hazard / unknown_boundary / sound_clue`の1〜6種
- `filter`（任意）: `block_ids / entity_types / displayed_items / crop_mature / position_bounds`のうち1項目以上。すでにpolicy許可されたrecordを除外するdelivery projectionであり、観測範囲や認可範囲は拡張しない。record kindに適用可能な条件同士はANDとする
- `position_bounds`: `{dimension,min_x,min_y,min_z,max_x,max_y,max_z}`の単一inclusive整数block-coordinate box。各軸で`min <= max`を要求し、任意center/radiusや複数領域は受け付けない。anchorは`visible_surface.position`、`visible_entity / hazard / unknown_boundary / sound_clue`の`floor(position)`、`traversability.navigation_target`とする
- `cursor`: 初回null、続きは直前の`next_cursor`
- `limit`: 1〜256件

返却recordは第7章の許可条件に従い、responseには`frame_completed_tick`を含める。1回のroot queryは1目的を原則とし、作物・container・gate・建築copy sourceは`visible_surface`、落下物は`visible_entity`、移動は`traversability`を要求する。作物収穫は`block_ids=[minecraft:wheat], crop_mature=true`、小麦drop回収は`entity_types=[minecraft:item], displayed_items=[minecraft:wheat,minecraft:wheat_seeds]`のprojectionを利用できる。`visible_surface`はblock positionごとに1件へ圧縮する。植付けsupportとなるfarmlandはUPを優先し、それ以外は実ray hitが近い面を代表にする。surfaceの返却順は`crop_mature=true`、`crop_mature=false`、非作物の3群とし、各群では観測距離が近いものを先にする。複数kindを同時要求した場合は、`visible_entity / traversability / hazard / visible_surface / sound_clue / unknown_boundary`の固定順で各kindから1件ずつround-robinし、1種類がpageを占有しないよう公平にinterleaveする。

すべての`visible_surface`は、従来の`block`に加えてrequired nullableな`state`と`placement_item`を返す。`state={block,properties}`を公開するのは、閉じた建築copy allowlistと既存support用の`minecraft:dirt` / `minecraft:grass_block` / `minecraft:obsidian`だけとし、それ以外は、見た目から判別できないleavesの`distance / persistent`やbeehiveの中間`honey_level`等を渡さないため`state=null`とする。非nullの`state.properties`は当該registered blockが定義するpropertyを省略しない完全表現とし、propertyなしblockは空object、`block == state.block`とする。`placement_item`は、NBTなし・通常BlockItem設置・閉じた安全allowlist・完全state再現を満たすcopy sourceだけitem resource locationを返し、それ以外は`null`とし、`placement_item != null`なら必ず`state != null`とする。CropBlockの`visible_surface`だけは、成長段階の数値列挙を増やさず、収穫判断に必要な`crop_mature: boolean`を追加する。非作物surfaceではこのfield自体を返さない。生成したpageは内部pending receipt（最大16件、60秒）へ一旦置き、HTTP response write成功後のdelivery confirmで初めて、そのpageに実際に含まれた静的`visible_surface`だけを最大2,048件、最大60秒のbounded storeへ昇格する。write失敗、dispatch取消、timeout、world境界ではpendingをabandonする。entity、item、traversability、hazard、sound、unknown boundary、未返却pageは延長しない。保持surfaceを使う場合も、通常のworld/session/dimension、visual / target revision、observer pose、reach、commit、JIT、targeted raycast、server acknowledgementをすべて再検証し、world境界ではstoreを全消去する。

traversabilityは連続値の`from / to` edge、target support、transition clearance、fluidに加え、`to`が属する整数feet-spaceを`navigation_target`として返し、斜めtransitionも曖昧にしない。Vanilla ladder / scaffoldingでは床付きlandingだけをrecordとして公開し、支持床のない中間段はA*用の内部edgeに留める。LLMはnavigationに`navigation_target`だけを無変換コピーし、連続値`from / to`やclimbable block座標を丸めたり変換したりしない。cursorは`SecureRandom`で生成した128 bit以上のopaqueなBase64URL tokenとし、server-side lease内のframe、kind集合、filter、offsetへ束縛する。任意center、任意radius、任意chunk、任意entity IDをqueryする入力は設けない。壊れた・未知・期限切れcursor、別frame/kind/filterへの使い回しは`INVALID_CURSOR`とする。同じ有効cursorの再送は同じpageを返し、失われたHTTP responseを再試行できる。`next_cursor=null`でpage終了である。

全周観測はcamera yaw/pitch、入力、Action camera budgetを変更しない。LLMが明示的に`face_known_position`を使うことは妨げず、その回数は通常のAST、実行node、時間、camera累積budgetだけで制限する。

#### 8.5.2 Action、program、primitive

- Action: `agent_start_action`で作られる1回の実行instance
- program: LLMが生成できる型付きJSON AST
- primitive: MODが決定論的に実行する有限のsemantic opcode
- template: 同じDSLで記述した検証済みprogram例。特権や別実行器を持たない

固定の高位Actionだけを選ぶ方式にはしない。LLMは許可済みprimitive、有限`if`、固定回数`repeat`を組み合わせられる。ただし、LLMが未知のopcodeを発明して実行することはできない。新しいMinecraft能力は、MOD側にprimitive、結果検証、安全試験を追加し、catalogのopcode allowlistへ載せたreleaseから使用可能になる。

programは通常のJSONであるため、LLMは`agent_get_action`から最大131,072文字のcanonical sourceとSHA-256を取得し、本文を複製・編集して別Actionとして提出できる。sourceは監査用で再実行権限ではない。opaque refを含む場合、clone templateでは該当fieldと対応するrecipe fingerprintを`null`化し、`ready_for_agent_start_action=false`、全fieldを`refresh_required`として返す。履歴取得はMinecraft client threadへ触れないためrefのlive validityを主張せず、再観測・再取得して置換すべきJSON Pointer、取得Tool、取得元pathを構造化する。再投入は新しいToolを設けず、必ず既存`agent_start_action`のschema、ref解決、JIT検証を通す。

Action実行中のmutationはterminal後に再構成せず、server由来の観測・ACK地点で上限64件の`effects`へappendする。各entryは単調な`seq`、node、kind、subject、sanitizedな`observed_before / observed_after`、`confirmed | qualified | unknown`、client tick、world revisionを持つ。未確認dispatchはafter-stateを捏造せず空objectと`unknown`を記録する。非terminalの`partial`は`null`とし、terminalでは最初のterminal intent時の割込みnode、残りnode上限、確認済みeffectの有無、再観測要否を返す。raw menu slot、packet payload、credential、secretはeffectへ入れない。現行の確実な対象はconstruction place / breakと、Vanilla container transferのclose / reopen full readbackである。worst-case cost、checkpointへの累積、consentを含むproduction Job情報は後続拡張とする。

`agent_get_state.policy.action_dsl.available_operations`はsealed `ActionDsl.Node`全型と1対1のmanifestであり、opcode、契約version、必要capability、ref fieldを返す。`control.granted_capabilities`との差分は`locally_missing_capabilities`、宣言先は`MISSING_CAPABILITY` guidanceで示す。`reference_descriptors`は`operation_ref`、`recipe_ref`、`placement_state_ref`の発行Tool、取得元、consumer、失効境界を示すが、refの自動更新やraw slot・hidden stateからの再構成は行わない。

LLMは、後続nodeが現在の証拠または明示対応するdependency proofですべて受付可能な範囲を1 Actionへまとめる。現在証拠が揃う作物を2〜8件処理する場合は単体nodeの反復よりmutation batchを優先し、成長待機が必要ならplant node群の直後に代表1座標の`wait_until`を同じprogramへ置く。mutationがdropや露出surfaceなど新しい証拠を作る場合は一旦Actionを終え、再観測後に次のActionを開始する。`apply_known_block_plan`だけは、同じnode内の先行entryの変換後targetを明示IDでsupportに指定でき、その閉じた依存以外の新規surfaceを推定しない。`navigate_to_known.target`は支持blockや連続値`from / to`ではなく、current `traversability.navigation_target`を無変換コピーする。visible blockへ接近したいが安全なfeet-spaceを特定できない場合は、`approach_known_surface`へ`visible_surface.position / block`を無変換コピーする。runtimeはKnown Traversability Map上で通常interaction reach内の最短候補を選ぶが、接近後の可視性やmutationを先取りして保証しないため、同Actionを終えて新しいeye originから再観測する。

Action DSL v1の制御構造:

- program bodyは順次実行
- `if`はnodeへ入った時点のpolicy-filtered `AgentSnapshot`を1回評価
- `repeat`はJSON内の固定`count`だけを使用し、1〜16回
- primitive失敗はAction全体を失敗
- 汎用while/until、再帰呼出し、並列実行、変数、任意式、catch、finally、on_cancelはなし
- `wait_until`は閉じた条件と固定`max_ticks`を持つ有限待機だけを許可
- Safety Governor、Esc、OFF、cancelをDSLから捕捉・無効化できない

現在許可するprimitive:

| opcode | capability | 内容 |
|---|---|---|
| navigate_to_known | movement | Known Traversability Mapで現在証明された地上feet-space、または完全なVanilla ladder / scaffoldingで結ばれた床付きlandingへ移動 |
| approach_known_surface | movement | 配達済みの可視surfaceへ、Known Traversability Map上の通常interaction reach内のfeet-spaceまで接近 |
| approach_known_placement | movement | 後続する1〜8件のstationary階段plan全体について、変換後facing、UP support ray、reach、settlement誤差を同時に満たす共通stand cellへ移動 |
| face_known_position | camera | 既知座標へ角速度制限付きで向く |
| face_known_block_face | camera | 配達済みsurfaceの指定faceに束縛したray witnessへ角速度制限付きで向く |
| wait_ticks | なし | 1〜15,000 active tickの有限待機。安全gateとAction全体deadlineは継続する |
| wait_until | なし | 開始時にpolicy-visibleなwheat surfaceだった明示座標を認可し、その座標のlive成熟を最大1〜15,000 active tick待機 |
| break_known_face | camera, block_break | 宣言した可視・既知のoak / birch幹1個を、指定したVanilla axeで通常入力から破壊 |
| break_known_block | camera, block_break | currentな可視面からコピーした完全BlockStateを、監査済みblock/tool/drop組合せだけで破壊し、ACK・air・期待dropのinventory増加を確認 |
| operate_known_cobblestone_generator | block_break | currentなexact cobblestone faceとiron pickaxeへ固定し、絶対inventory目標まで最大64 cycleを反復。各cycleをACK＋airでcheckpointし、再生成待ちではattackを解放 |
| till_known_block | camera, block_interact | 可視・既知のdirt / grass_block / dirt_path 1個を、指定したVanilla hoeの通常useでfarmlandへ変換 |
| till_known_batch | camera, block_interact | 1〜8個の相異なる可視・既知blockを、共通の`expected_block`とVanilla hoeで入力順に耕す |
| plant_known_wheat | camera, block_place | 可視・既知のfarmland直上のairへwheat_seedsを通常useで植え、age=0を確認 |
| plant_known_wheat_batch | camera, block_place | 1〜8組の相異なる`target` / `support`を入力順に検証し、各farmlandへwheat_seedsを植える |
| harvest_known_wheat | camera, block_break | 可視・既知かつ実行時age=7のwheat 1個だけを通常破壊し、airを確認 |
| harvest_known_wheat_batch | camera, block_break | 1〜8個の相異なる可視・既知かつ成熟済みwheatを入力順に収穫する |
| apply_known_block_plan | camera, block_place | 完全な可視source stateをruntimeでmirror / rotationし、現在supportまたは明示先行dependency上へ1〜8 blockを入力順に通常設置する |
| clear_known_block_plan | camera, block_break | 現在返却済みの完全stateが一致する安全建築blockを1〜8件、既存BREAK_TO_AIR経路で撤去し、freshなair再観測後に完了する |
| pillar_up_known | movement, camera, block_place | centering直前に配達済みのexactな安全UP supportを保持し、inline identityまたはsession-local `placement_state_ref`から解決した単一full blockを、player直下のlive完全state再検証後に1個だけ設置してY+1へ着地する |
| apply_known_redstone_spec | camera, block_interact, block_place | 固定lever→lamp 1出力、2出力fan-out、または1 dustの直線identityを設置し、live visualでOFF / ON / OFFを試験する。wire版はlamp / dust / leverの可視glass supportとdustの直線shape・power 0 / 15 / 0も完全一致させる |
| open_known_fence_gate | camera, block_interact | 可視・既知の閉じたoak fence gate 1個だけを空手の通常useで開き、open=trueを確認 |
| open_known_passage | camera, block_interact | 可視・既知の木製door / trapdoor / fence gate 1個を通常useで開く。doorは上下2 halfのauthoritative open=trueを確認 |
| inspect_known_container | camera, inventory_transfer | 可視・既知かつreach内の明示allowlist対象Vanilla chest / barrelを通常useで開き、server full-content由来のitem別集計をAction traceへ返す |
| take_known_container_stack | camera, inventory_transfer | 同じcontainerから指定itemを最大14 whole stacks・896個まで移し、各server ACKと最後1回のfull readbackでplayerの絶対個数を確認 |
| store_known_container_stack | camera, inventory_transfer | playerの指定itemを同じcontainerへ最大14 whole stacks・896個まで移し、各server ACKと最後1回のfull readbackでcontainerの絶対個数を確認 |
| remove_visible_frame_item | camera, entity_attack | 配送済みの正面額縁と表示itemをJIT再確認し、空手の通常攻撃1回で表示除去をserver確認。drop回収は別Action |
| insert_visible_frame_item | camera, item_use | 配送済みの正面空額縁へhotbar itemを1個挿入し、server表示ACKと選択slot1個減少を確認 |
| craft_known_recipe | camera, inventory_transfer | recipe queryの短寿命opaque参照を再検証し、可視・既知crafting tableで1〜3回、完成品を1回分ずつ回収して絶対inventory目標を確認 |
| smelt_known_recipe | camera, inventory_transfer | recipe queryの短寿命opaque参照を再検証し、可視・既知のfurnace / blast furnace / smokerでexact stack 1〜64個を精錬して絶対inventory目標を確認 |
| brew_known_potion_batch | camera, inventory_transfer | 空の可視・既知brewing standで、宣言した標準Vanilla Potion 1〜3本を現行recipe tableの既知の1段変換だけ醸造 |
| operate_known_menu | inventory_transfer | 現在開いている受入済みMenu profileのsingle-use `operation_ref`を再検証して1操作を実行 |
| collect_visible_item | movement | 最新frameの可視item種別と連続値XYZをwitnessに、既知の安全なpickup cellへ移動し、inventory絶対個数の増加を確認 |
| collect_visible_item_batch | movement | 2〜8件の可視item witnessをlisted orderで同じ安全検証経路へ展開し、失敗時は未開始suffixを実行しない |

`break_known_face`は指定faceのblock中央へ固定照準せず、そのfaceを実際に観測したray hitへ解析的に照準し、開始直前にも同じfaceのtargeted raycastを要求する。

`break_known_block`も同じdelivery-backed ray、有限attack lease、Vanilla prediction ACK、authoritative air経路を再利用するが、block IDだけでなく`visible_surface.state`からコピーした完全な`expected_state`をpacket隣接で再確認する。V1の安全表はoak/birch log＋同種drop＋Vanilla axe、およびcobblestone＋iron pickaxe＋cobblestoneだけである。成功には`expected_drop`のserver-synchronized inventoryが開始時より増え、かつ`minimum_inventory_count`へ到達することも必要とする。ACK前に中断した可能性のある破壊はeffect ledgerへ`unknown`、ACKとauthoritative airを確認した破壊は、drop postcondition未達でAction自体が失敗しても`confirmed`として記録する。world mutationなので`repeat`内を拒否し、staleな可視証拠を次の破壊へ再利用しない。

`operate_known_cobblestone_generator`は、一般的なraw attack holdではなく、既存`StationaryBreakRoutine`と`AttackInputLease`を再利用するtop-level専用の長時間Action sliceである。入力はexact target / face / 完全なcobblestone state、`minecraft:iron_pickaxe`、cobblestoneの絶対inventory目標、`max_breaks`、`regeneration_wait_ticks`、`max_operation_duration_ticks`に閉じる。通常期限は3600〜6000 ticksを推奨し、明示上限は36000 ticks（約30分）とする。各cycleは短いleaseでのみattackし、server ACKとauthoritative air後に入力を解放してcheckpoint/effectを確定する。air・再生成待ちはattack-upで、再生成後はexact state / face / reach / toolをJIT再検証してから次leaseへ進む。毎tickのworld/session、OFF/Esc、health、threat、screen/control context、固定位置・向き・slot gateのいずれかが変われば入力を解放してterminalにする。永続Job Store、未知generator検出、raw mouse opcodeはこのsliceに含めない。

semantic action、stationary break、block plan、Phase 5 world adapterで共有するuniversal safety gateは、OS window focusとVanillaのmouse grabを許可条件へ含めない。評価中に別terminalへfocusを移した場合やmouse captureが一時的に外れた場合でも、それだけでpreflight / JITを失敗させない。一方、Minecraftの実pause、予期しないScreen / overlay、Survival mode、生存とhealth・被弾・炎上、policy-visibleな近傍threat、primitiveごとの位置・向き・slot・使用状態を含むstationary条件、serverのposition / rotation / motion / inventory / block mutation reconciliationは従来どおり検証し、操作直前の再検証を省略しない。Screenの不一致はこのmutation dispatchを許可しない条件であり、それだけでcontrolをOFFにするglobal stopとは区別する。universal safetyの変化は`CONTROL_CONTEXT_CHANGED`または`mutation_safety_changed`、対象block自体の不一致は`mutation_precondition_changed`として分離し、入力値を診断へ反射しない。

現在のpolicy-visibleな近傍threat判定はruntime側の実行条件であり、LLMだけが次のActionを控えるadvisoryではない。該当時は現在primitiveまたはActionを失敗・再計画へ進め、Agent入力を解放する。mob trap等の期待された敵対mob環境へ対応する際は、この条件全体をOFFにせず、`visible_hostile_presence`だけをユーザー同意で限定解除できる中央`ThreatPolicy`へ移行する。

productionの同意単位は一体・一攻撃ではなく、場所と有限の運転条件へ束縛したzone-scoped attack leaseとする。解除権限はLLMが指定するbooleanではない。MCP 2026-07-28 form elicitation対応clientでは`input_required`に対するユーザーのacceptを用い、非対応clientだけローカル専用UIの物理primary clickが発行するopaque `consent_ref`へfallbackする。次の全fieldをscope / canonical hashへ含める。

- world session、dimension
- Grant時の実player位置とsupportからRuntimeが導出するtightなplayer station bounds、およびkill-zone bounds。LLM入力から任意のstationを採用しない
- mob type allowlist、trusted Runtimeがmain-handのexact stackから導出した宣言済みattack-side-effect profile、およびitem ID、enchantments、攻撃効果に関係する不変componentsの内部fingerprint
- policy hash、`operate_kill_zone` Action、および所有Jobがあればそのcanonical hash
- 未回答requestとfallback Grant後の開始猶予は3600 active client tick（約3分）。OFF、Esc、world変更等では期限前でも失効
- Action開始後の最大攻撃数、最小攻撃間隔、運転期限（最長約30分）

production初期sliceでは同意を複数Actionが提示できるbearer leaseにしない。MCP経路ではcurrent requestのclient capabilityを毎回確認し、Runtime生成のランダムchallengeをworld session、exact canonical policy / scope、channel、3分期限へ束縛する。HTTP `requestState`はchallengeとpolicy hashをprocess-local HMACで封印し、`inputResponses`は要求した一キー、`accept|decline|cancel`、accept時の明示booleanだけを許す。accepted retryはActionをreserveするが、この時点では承認をconsumeしない。response配送確認後、execution直前にfresh scopeが一致した場合だけsingle-consumeする。非対応clientでは物理Grantから3分以内に同じActionをfresh `consent_ref`付きで再送する。world、policy、zone、types、item profile、count、interval、運転期限、Jobのいずれかが変わればrejectする。Action内部だけで後からspawnした複数mobを最大N回攻撃し、一体ごとの再同意やterminal後の再利用は許可しない。継続運転では期限を通常3分以上またはポーション等の作業窓へ合わせ、最大回数達成とhard safety gateは早期終了条件とする。

attack-side-effect profileはitem IDから推測せず、trusted Runtimeがexact stackを宣言済みprofile tableまたはversion固定adapterへ照合して導出する。未知のMOD武器またはdata-driven profileはadapter追加まで拒否する。main-handのraw NBT / componentsと内部fingerprintはwire / UIへ公開せず、UIにはsanitizedなitem IDと攻撃effect profileへ束縛済みであることだけを表示する。耐久damageはfingerprint対象から除き、攻撃による増加とMending / repairによる減少をともに許可する。同じitem IDと攻撃effect fingerprintのstackへ交換しても安全scopeは変化しない。攻撃effect fingerprintの変化、武器の破損・消失を検出した時点でActionとleaseを終了する。

各攻撃で選ぶ短寿命`entity_ref`はlease scope / hashへ含めない。Runtimeはattack commit直前にfreshなpolicy-visible観測からrefを解決し、同じ実entityのalive、type、全AABBのkill zone内包、LOS、Vanilla reach、crosshair hitを検査して、誤射防止とeffect ledgerのsubject相関だけに用いる。ref失効、target移動、別entityへのcrosshair変更時は攻撃せず、fresh refを取得して再計画する。

敵の足元1 blockまたは半blockだけを見せるkill chamberは安全なfixtureの有力形であるが、形状の見た目だけで安全認定しない。station / zone boundsの数値だけでは足りず、両者の間に正のcontact marginがあり、Grant時に認可したsupport、barrier、開口部、collision shapeが実際のload済みblockとして維持されていることを各dispatchのcommit fenceでJIT検査する。同じfenceでplayerがRuntime導出station bounds内に固定されていること、main-hand effect profile、残りattack count、最小間隔、期限、target全AABB / LOS / reach / crosshairも再検査する。耐久damageの増減はこの検査を失敗させない。攻撃枠はこの最終検査と同じcommit fenceで原子的にreserveしてからdispatchし、reserve後の例外、ACK不達、effect `unknown`でも返却しない。これにより不確実なdispatchの自動再試行が回数上限を迂回することを防ぐ。

playerはtarget type allowlistへ追加できず、すべての攻撃で恒久的に禁止する。zone内のallowlist対象以外のhostileだけでなく、allowlist型でも全AABBがzone外にあるmobをhard hazardとして扱う。接触可能性、projectile、炎上、lava、fall、suffocation、air不足、world / session変更、desync、unexpected screenも解除できない。最初sliceは宣言済みprofileから効果範囲を決定できるVanilla weaponに限定する。剣のsweep候補がある場合はeffect envelope内の全living entityをdispatch直前にfresh列挙し、各候補が非player、allowlist type、whole AABB zone内であることを全件証明する。1件でも未知、player、type不許可、zone外ならdispatchを拒否する。その他のAoEとadapter未登録のMOD / data-driven profileは拒否する。mob種別ごとの当たり判定、攻撃方法、隙間通過、reach差はfixtureのallowlistとversion固定試験で扱い、未知の例外を形状名だけから推測しない。

health floor未満、health低下、継続被ダメージも解除できない。health低下を検出したtickでattack / useを含む全Agent入力を解放し、現在の攻撃を中断してleaseを失効させる。terminalまたはcheckpointは`SAFETY_INTERRUPTED`とsanitizedな`health_before`、`health_current`、`health_delta`、`reason`を返す。LLMは変化をユーザーへ報告し、安全回復をfresh観測で確認した後に必要なら新しいzone同意を要求する。

form elicitation対応clientではMCP clientだけが確認UIを所有し、Minecraft側には確認画面、同意待ち枠、入力隔離を出さない。文面は「特定個体ではなく現在または後からzoneへ入る許可種別」が対象であること、friendlyな種別名と武器、最大攻撃数、最小間隔、運転期限、攻撃ごとの再検査を短く表示する。生座標やfingerprintは承認bindingへ含めるが主文面へ羅列しない。fallbackの非pause専用画面は同じscopeと3分の開始猶予を表示し、物理primary clickとEscだけを通す。Cancel、decline、Esc、画面close/replace、OFF、world/session変更、endpoint fault、shutdown、hard gate発火、budget / deadline消費は同期的にrevokeする。chat、看板、本、server textは同意として扱わない。

`operate_kill_zone`のproduction初期経路はMCP MRTR/fallback pending、top-level専用DSL、single-consume consumer、JIT対象選択、Action所有count / interval / deadline、bounded effect集計、構造化health中断まで接続済みである。form elicitation対応clientの初回`consent_ref:null`はAction予約・入力取得・Minecraft UI表示を行わず`input_required`を返し、accepted retryを配送確認後に開始する。非対応clientは`AWAITING_CONSENT`と物理UI/ref経路を用いる。初期fixtureはfull-cube support/roof/三面壁と、zone側の下段full cube＋上段top slabで作る半ブロックslitを持つ1セルstationへ厳密固定し、zone全体を8-block hazard volume内へ限定する。対象型は`armor_stand`、`zombie`、`skeleton`、攻撃profileは必要耐久を残した無エンチャントVanilla sword/axeだけとする。汎用`attack_known_entity`、特殊能力mob、MOD profileは引き続き未実装で、実機・実ワールド受入は`aod-mimoid`上で行う。

`break_known_face`の`tool_item`と`till_known_block` / `till_known_batch`の`hoe_item`はinventory内の該当toolをhotbarへ一時退避して決定論的に選択する契約であり、任意slot操作を公開しない。`plant_known_wheat` / `plant_known_wheat_batch`も同じ準備経路でwheat_seedsを選ぶ。各変化はclient prediction ACKとauthoritative block stateで確認し、toolや種を生成・補充しない。成熟待ちは、primitive開始時にtarget-scoped fresh barrier以後のpolicy-visibleなwheat surfaceを明示座標へ束縛する。targetのmutation revisionがbounded reconciliation mapに残っている場合は`max(visualBarrier, exactTargetRevision)`を使い、他座標の大量更新によるeviction floorを混ぜない。exact target revisionが既にevictされている場合だけ`max(visualBarrier, surfaceMutationEvictionFloor)`へfail-closed fallbackする。一般primitiveのsurface barrier契約は変更しない。JIT認可にはworld/session/dimension/exact targetに加え、その時点の`visualBarrierWorldRevision`、player位置、observer eyeを固定する。待機中にvisual barrierが変化した場合、またはplayer位置/eyeが固定epsilon（1/1024 block）を超えて変化した場合は、live BlockStateを読む前に`PATH_BLOCKED`で終了する。wheat AGE更新などnavigation-neutralなexact-target mutationはvisual barrierを上げないため待機を継続できる。束縛がcurrentな間だけ、その認可済みでload済みの1座標をlive BlockStateで確認し、wheat age=7なら観測frameの更新を待たず完了、age<7ならpendingとする。非wheatへの置換、unload、session / dimension / target変更は早期terminalとし、live stateの値や近傍情報はresponseへ公開しない。単独`wait_until`の初期admissionにも同じvisible wheatを要求する。先行する認可済みplantが全control pathで同じtargetを生成すると静的証明できる閉じたprogramだけは初期解析で未生成cropを許すが、wait開始時の1-node JIT bindでは例外なく新しいvisible wheatを再認可する。timeout時は入力を発生させずActionを終了する。raw attack/useや任意座標操作へ一般化しない。

`wait_until`が採用する`visible_surface`はrecordの`eye_origin`を保持し、initial admission、commit fence、JIT bindのすべてでcurrent observer eyeとの差を1/1024 block以内に制限する。以前のobserver位置から得たstale frameは、target record自体がfresh revisionでも認可しない。`CropWaitAuthorization`のobserver eyeにはcurrent値を代入せず、採用witnessの`eye_origin`そのものを保存するため、待機中の比較元をすり替えられない。先行plantにより初期未生成cropを許す静的dependency proofはこの例外を弱めず、wait開始時のJITでは必ず同じorigin契約を再証明する。

`collect_visible_item_batch`は2〜8件をlisted orderのまま保持する第一級の有限batch nodeである。batch開始時にitem種別ごとのplayer inventory絶対個数baselineを1回だけ固定する。各entryは通常の`collect_visible_item`と同じfresh visible entity、連続値XYZ、既知安全pickup cell、移動中再検証を要求する。先行entryへの移動中に後続entryのfresh policy-visible AABBとplayer pickup areaの実接触を確認し、その後に対応itemのinventory絶対個数増加を確認できた場合だけ、当該後続entryを`incidentally_collected`としてcreditできる。単なるwitness消失、merge、移動、近接や推定では成功にしない。listed orderの途中で接触・差分proof、経路、budgetのいずれかが不足した場合はAction全体をfail-fastで終了し、未開始entryをskip・置換・再順序化しない。

`approach_known_placement`は、後続するstationaryな階段planに必要な作業姿勢をLLMの座標推測なしで得るmovement-only primitiveである。後続planと同じ`anchor` / `transform` / 1〜8件の`entries`を受けるが、初回sliceはsession-local `placement_state_ref`、現在完全stateのUP support、`dependency_entry_id=null`、乾いたbottom halfのoak / cobblestone stairだけを許可する。runtimeは同じmirror / rotation後の`facing`を解決し、全entryについてKnown Traversability Mapの経路、通常reach、support ray、停止時settlement誤差、配置時の水平向きを同時に満たす共通stand cellを、最短距離、worst reach、NavCellの順で決定論的に選ぶ。照準、設置、support evidence延長は行わず、単独top-level Actionとしてterminal後に再観測を要求する。budgetは通常navigationと同じ最大30,000 ms、600 ticks、32 distance blocksで、camera / interaction / break / placeは0とする。

`apply_known_block_plan`はPhase 3の初回vertical sliceであり、wire shapeを`{id,op,anchor,transform:{rotation,mirror},entries:[{id,offset,placement_state_ref,support:{position,face,expected_state,dependency_entry_id}}]}`へ閉じる。移行互換として各entryは`placement_state_ref`または旧`source_state`+`item`のexact one-ofを受ける。entryは1〜8件、offset各軸は-8〜8、entry IDと変換後targetはnode内で一意とする。`anchor`とsupportはdimension-qualified block座標で、変換後targetは`anchor + transform(offset)`だけから決定する。`mirror=none|x|z`を先に適用し、`x`はMinecraft `FRONT_BACK`と同じeast/west反転、`z`は`LEFT_RIGHT`と同じnorth/south反転とする。その後`rotation=0|90|180|270`のY軸時計回り回転を適用する。offsetとrefが解決した完全stateは同じtransformを通し、方向propertyをLLMへ変換させない。

唯一のmulti-cell例外は、閉じた未通電の`minecraft:oak_door` lower halfである。1 entryの通常useが生成するupper halfもAction boundsと事前air観測へ含め、同一prediction sequenceに対するlower / upper別々のserver-verified stateが完全一致した場合だけ成功する。upper halfは別entryにせず、door entryは`max_blocks_placed`を2消費する。doorはsupport、pillar、clearには使わない。

`clear_known_block_plan`は同じ`anchor` / `transform`と1〜8件の`{id,offset,expected_before}`だけを受け取る。targetの返却済み完全stateとconstruction policyをplanner・packet直前・heartbeatで再検証し、既存`BREAK_TO_AIR`経路で入力順に撤去する。成功条件は全targetのfreshなair再観測であり、置換はそのterminal後に再観測を挟んだ別Actionの`apply_known_block_plan`とする。

`placement_state_ref`は、MCP responseの成功書き込みが確認された`placement_item != null`の可視surfaceだけからstate+item identityへ発行する推測困難なopaque値である。成功書き込み後のconfirmation処理は完了を待ってからHTTP exchangeを閉じ、直後のAction受付とref有効化が競合しないようにする。最大512 identityを決定的にbounded保持し、座標surfaceの60秒TTLでは失効せず、world-session遷移で全消去する。refは見本座標の再訪を不要にするだけで、target、既存support、ray、player pose、hazardは短期証拠とpacket直前の再検証を維持する。移行用inline形式では完全`visible_surface.state`を`source_state`へ、同recordの`placement_item`を`item`へ無変換コピーする。runtimeはどちらもregistered BlockState定義に対してpropertyの欠落・余分・不正値を入力前に拒否し、MinecraftのBlockState mirror / rotation実装で完全stateを一意に変換する。`apply_known_block_plan`受付時のcompilerは有効なrefを解決してordinary=1 / oak-door=2のplacement footprintを確定し、未解決refには安全側の2 cell上限を使って過小評価を防ぎ、budget不足またはplannerの`TARGET_UNKNOWN`で拒否する。`pillar_up_known`も同じrefをplanner受付時とruntime request生成直前に二重解決するが、1 placementの通常full collision blockだけを受け付け、oak doorを含むmulti-cellとslab / stairs / attachment等のpartial shapeは拒否する。BlockEntity / NBT、fluid、gravity block、container、portal、command block、通常BlockItem設置で完全stateを再現できないblockは`placement_item=null`とし、refも発行しない。

`support.expected_state`と`support.dependency_entry_id`は両方をfieldとして必須にし、exactly-oneだけ非nullとする。現在blockをsupportにするentryは、`state != null`である最新policy-visible surfaceの完全stateを`expected_state`へコピーし、dependencyをnullにする。先行設置をsupportにするentryはexpected stateをnullにし、入力順で先行するentry IDだけをdependencyへ指定する。この場合`support.position`はその先行entryの変換後targetと完全一致しなければならない。どちらも`support.position`から`face`方向へ1 block隣が当該entry targetであることを静的検証する。未開始・後続・外部ID、暗黙の近傍探索、未観測supportは認めない。

このsliceはAction開始から終了までstationaryで、移動、既存blockの破壊・置換、順序変更、rollbackを行わない。未実行entryが既に変換後の完全なexpected-after stateなら、再開済みentryとして入力もplacement budgetも消費せず採用できる。ただし同じframeだけで成功にせず、全entryを後続のfresh current frameで完全一致確認してから完了する。それ以外のair precondition不一致は入力前に終了する。残るentryのtarget air、support proof、可視性、reach、targeted raycast、inventory絶対個数、universal safety、world/session/revisionを受付時と各dispatch直前に再検証し、通常use ACKとauthoritativeな変換後完全BlockState一致を成功条件にする。packet準備でmain inventoryからselected hotbarへSWAPした場合は、そのserver同期と総数不変readbackを待ってから設置消費のrevision / count baselineを取り直す。消費待機中に総数が不変の同期だけを受けてもmismatchへ固定せず、正確な-1を期限まで待つ。途中失敗では未開始suffixを実行せず、完了済みmutationと使用itemをtraceへ残す。各support aimは観測rayの端点ではなく宣言faceの中心を基準とし、受付headingからyaw絶対差とpitch絶対差の合計40度以内に制限する。clear planも配達済み代表faceの中心を同じ基準に使い、plannerは実行時と同じ上限を証明する。adapterはentryごとに受付headingへ戻すため、worst-caseは1 entryあたり15,000 ms、300 active tick、camera 80度、1 placement、distance / interaction / breakは0と固定し、8 entryで120,000 ms、2,400 tick、camera 640度、8 placementsとする。world mutationであるため`repeat`内には置けない。

3種のmutation batchは`targets`を1〜8件に制限し、重複targetを拒否する。`till_known_batch`は位置配列と共通`expected_block` / `hoe_item`、`plant_known_wheat_batch`は`{target,support}`配列と共通`seed_item=minecraft:wheat_seeds`、`harvest_known_wheat_batch`は位置配列を受け取る。受付時に全対象の現在のsurface、block state、reachを入力順に確認し、`TARGET_UNKNOWN`なら最初に不足した入力順indexをmessageの`target[index]`として返す。これは提出済み配列のindexだけであり、hidden座標や未公開stateを追加開示しない。plannerとruntimeはcamera cost最小化やray関係を理由に入力順を変更しない。入力順の累積camera costと各primitive costを既存のAction上限内で事前証明できない場合は、入力発生前に拒否する。

実行時は固定した順序をblind replayせず、各対象の直前に最新pose、surface、reach、world revision、期待block / 成熟状態、exact targeted raycast、残budgetを再証明する。fresh evidenceがまだ揃わない間は入力をneutralにして最大40 active tickだけ再観測し、それでも証明できない場合、live raycastまたはauthoritative postconditionが不一致の場合、あるいは対象単位の残budgetが不足する場合はAction全体をfail-fastで終了する。失敗対象をskipしたり、後続対象へ進んだり、実行時に別対象へ置換・並べ替えたりしない。完了した前段のworld mutationと消費budgetは巻き戻さずtraceへ残す。`till_known_batch`の実dispatch成立後に足元blockがfarmlandへ変わった際の厳密な垂直1/16 block下降だけは、2 tick以内、同じXZ、live farmland、movement executor非稼働、移動入力neutralの全条件が成立した場合に限り入力distanceへ算入せず、`PASSIVE_MOTION farmland_settling`として別途監査する。水平成分、1/16超の落下、movement入力中の下降は通常どおりdistanceを消費する。

初期joint planは、playerがtill target上に立ち得る場合、そのtarget以後の抽象poseへ最大1/16 blockの`yErrorBelow`を伝播する。したがって後続targetのfresh reproofで実際のeye Yが1/16低下しても、そのcamera tick / duration / degreesはbatchの初期`occurrenceLimit`内に保守的に包含される。settlingをinput distanceから分離しても、720度のglobal camera上限やtarget単位のoccurrence budgetを緩和しない。

さらにjoint worst-caseは各targetへfresh reproof最大40 tick / 2,000 msを加算する。target開始時には、すでに消費したcounterに、当該targetのfresh mutation costと未実行suffix全targetの初期証明済みcost（各40 tick reproofを含む）を加え、global budgetとbatch occurrence limitの双方に収まる場合だけdispatchへ進む。これにより途中までworldを変更した後でsuffix不足が判明することを防ぐ。batch内でsemantic exact aimが失敗した場合は同一targetを再試行せず`PATH_BLOCKED`でfail-fastとし、既存の最大3回aim再試行は単体mutation primitiveだけに維持する。

current targetのfresh reproofでfaceまたはaim pointが受付時から変化した場合は、旧planned pose基準のsuffix costを流用しない。fresh current aimの終端抽象pose（till settling誤差を含む）から固定suffix順序を再走査し、各suffix targetのcamera costと40 tick reproof reserveを再計算した値で上記のglobal / occurrence両判定を行う。再計算後のsuffixが収まらなければcurrent targetをdispatchする前に終了する。

`open_known_passage.expected_block`は12種の木系door / trapdoor / fence gateを明示列挙し、ironとcopperを許可しない。doorはクリック対象のhalfだけでなく、同一block、facing、hinge、powered、openが整合する相方halfをdispatch前に固定する。primary prediction ACK、primaryのauthoritative state、dispatch後のcompanion block mutation、companionの完全stateがすべて一致した場合だけ成功する。pressure plate式自動doorはこのopcodeを使わず、plate上と反対側へ続く`navigate_to_known`を別々のprimitiveとして実行し、world revision更新後のVanilla VoxelShapeから後続経路を再計画する。

container primitiveは別のMCP Toolやlegacy routineを公開せず、同じAction supervisorから既存のscreen ownership / full-content同期adapterを駆動する。`inspect_known_container`はslot番号、NBT/component本文、menu内部状態を返さず、最大27種類の`item=count`だけを`NODE_EVIDENCE` traceへ返す。`take_known_container_stack`はcontainer→player、`store_known_container_stack`はplayer→containerへdirectionを固定し、`default_components_only`または耐久済みtoolにも使える`item_id_any_components`だけを許可する。任意の`max_stacks`は1〜14（省略時1）、`max_transfer_count`は1〜896（省略時64×max_stacks）とし、このActionで移す量を制限する。初回openと最後のreadback openに加え、whole-stack QUICK_MOVEを最大max_stacks回、計2+max_stacks interactionだけ予約する。source slotとitem/componentsの計画は最初のserver同期snapshotで固定し、補充されたstackを追加しない。各clickの前に全slot・成分・容量・残る個数予算を検証し、freshなserver slot差分を確認してから同じmenuで次のclickへ進む。内部実行は400+60×(max_stacks−1) ticks、Action予約は600+60×(max_stacks−1) ticksとその50倍msとし、200 ticksのdispatch・JIT・release余白を維持する。最後は同じcontainerを1回だけ開き直して全slotを照合する。途中停止時はserver確認済みprefixをCONFIRMED、最後の未確認clickだけをUNKNOWNとして分け、blind retryしない。絶対個数目標に届かなければ確認済み部分移送を記録して失敗し、再観測後に再計画させる。OS focusとmouse grabは要求しないが、pause、overlay、予期しないScreen、world/session変化、可視threat、cursor残留、screen ownership不一致はfail closedとする。Agent所有の正規container Screenだけは処理stageに応じて許可する。

container blockの明示allowlistは`minecraft:chest`、`minecraft:barrel`、およびMinecraft 26.2の8種のcopper chest（`copper_chest` / `exposed_copper_chest` / `weathered_copper_chest` / `oxidized_copper_chest`と各waxed版）だけである。trapped chest、ender chest、shulker box、MOD containerを拒否し、runtime tagやdatapackでこの集合を拡張しない。chest familyは単体を9×3、doubleを9×6として扱うが、block IDと全BlockStateの完全一致を要求する。oxidation / wax状態、facing、waterlogged、single / left / rightの変化、copper golem等によるslot変化はfail closedとし、blind retryせず再観測させる。

上記3 primitiveは任意の`routing_label={entity_ref,item}`を受け取る。値は同じcurrent observationの`visible_entity.entity_ref`と`container_label.item`からコピーする。labelはcontainer操作の権限ではなく経路選択witnessであり、plannerは配達済みframeのsame ref / item / attachment target / blockを要求する。runtimeはadmission、各normal-use open直前、各QUICK_MOVE直前にsame entityの可視性・LOS・item・直接付着先・container identityをJIT再照合し、変化時はクリックせず再計画へ返す。移送クリック前にはserver snapshotとlive menu全slotの一致に加え、移送元whole stack全量が移送先slotsへ収まることを証明し、QUICK_MOVEによる部分移送を許可しない。

安全な手持ちはMAIN_HANDだけを使用する。exactなVanilla crafting table / chest / barrel、非sneaking、通常reach・crosshairを前提に、NeoForgeがblock処理前に呼ぶ`doesSneakBypassUse`の実装元が`IItemExtension`既定false、`onItemUseFirst`が同既定PASSのままの非空hotbarを優先する。空きhotbarは、空MAIN_HANDで評価されるoffhand側の`doesSneakBypassUse`もcustom overrideでない場合だけfallbackにする。MAIN_HANDのいずれかのcustom hook、および空MAIN_HAND時のoffhand custom sneak-bypass hookは拒否する。対象blockの`useWithoutItem`がconsuming resultを返すため`ItemStack.useOn`には到達せず、この限定文脈では通常のBlockItem、bucket、着火具、道具、stack componentsだけを理由に除外しない。containerからの`QUICK_MOVE`が最後の空きhotbarを丸石等で埋めても、同じMAIN_HANDを追加interactionなしで同一Actionの読み戻しと次のcontainer開封へ使用できる。空きMAIN_HANDへ移す対象自体がこの安全条件を満たさないtakeは、最初の移送click前にfail closedとする。offhandをinteraction handには使わない。候補がないcontainer失敗では`inventory_safe_open_hand_required` / `inventory_safe_open_hand_unavailable`に加え、固定診断`safe_open_hand=no_side_effect_free_main_hand`と`remedy=prepare_plain_material_or_safe_mining_tool_in_hotbar`を返す。通常の丸石、鉄インゴット、ピッケル等をhotbarへ1枠用意すればよく、持ち物全体を空にする必要はない。

NeoForgeのglobal `RightClickBlock` / `UseItemOnBlockEvent` handlerが追加する独自副作用は、Item hookの実装元検査では証明できない互換性境界とする。変更されたinteraction経路を安全とは仮定せず、prediction、screen ownership、server同期、full-content readbackの検証を省略しない。

container openのprediction bridgeはClientLevel constructorの必須Mixinで登録する。同じClientLevel内のplayer clone / respawnではactive attemptだけを閉じてchannelと互換性判定を保持し、level unload、logout、`ClientLevel.disconnect`、shutdownでlevel channelを閉じる。開封前にbridgeを利用できない場合は`container_open_prediction_unavailable`と、固定診断`prediction_bridge=unregistered` / `disabled` / `lifecycle_closed` / `attempt_limit`のいずれかだけを公開する。内部例外文、検出version、private adapter detailは返さない。

安全な手持ちの要求は開封前のAIMINGとnormal-use送信直前に適用する。OPENING中はすでに送信済みなので、同期・自動補充による手持ちの中身の変更では読み戻しを打ち切らない。session、画面所有権、選択slot・view所有権、reach、cursor、server full-contentの検証は維持する。送り元減少と送り先増加が一致しない場合は`container_ambiguous`と固定診断`container_transfer_readback_mismatch`を返す。実際のfull-contentから確認できた前後の個数はUNKNOWN effectへ残し、転送量の確定値は作らない。未観測の初期0をafterへ出さず、観測した0は保持し、次の転送前に読み戻しフラグをresetする。外側inventoryが自動補充された場合にも、未観測backpack内部の消費量を推測しない。effectのsource/destination個数上限は大チェスト54×64の3,456とし、takeのplayer絶対goal上限は2,304、storeのcontainer絶対goal上限は3,456とする。実際のmenu容量（単体chest/barrelは27slot）と各itemのstack上限は別途検証する。1 batchの移送上限は896であり、全量の保存と選択外slot/componentsの一致を必須にする。

`craft_known_recipe`はwire shapeを`{id,op,recipe_ref,recipe_fingerprint,goal:{item,stack_policy,minimum_inventory_count},station:{kind,target,expected_state},max_crafts}`へ閉じる。`recipe_ref`と`recipe_fingerprint`は同じ最新`agent_get_state` recipe query結果からコピーし、world sessionとcatalog revisionへ束縛したままAction開始時と各craft前に再解決する。`station.kind`は`crafting_table`、stateは`minecraft:crafting_table`かつ空properties、goal policyは`default_components_only`、絶対個数は1〜2,304、`max_crafts`は1〜3に固定する。実行は初回open 1回と各craftのrecipe placement・cursor-invariantなresult QUICK_MOVE・readback openで進める。静的budgetは互換性と安全余裕のため従来どおり`1 + 4 * max_crafts` interaction、最大400 active tickを予約し、Action budgetには最低30,000 ms、600 tick、camera 360度を要求する。完成品は1回分ずつ回収し、click前のserver-confirmed empty cursorを維持したまま、空grid/result、close/reopen full-content、絶対inventoryのexact deltaを確認する。slot番号やmenu内部状態は公開せず、曖昧な更新をblind retryしない。

`smelt_known_recipe`はwire shapeを`{id,op,recipe_ref,recipe_fingerprint,goal:{item,stack_policy,minimum_inventory_count},station:{kind,target,expected_state},fuel:{item,stack_policy},max_smelts}`へ閉じる。stationは`furnace | blast_furnace | smoker`、goalとfuelのpolicyは`default_components_only`、`max_smelts`は1〜64とし、材料source stackの全量と一致させる。recipe display kind / required screen / station family、cook時間200 tick以下、完全BlockState、空menu、exact材料stack、全量完了に十分な燃料stack、QUICK_MOVE経路を入力前に再検証する。照準点はblock中心へ置換せず、planner受付に使った配達済みvisible-surface ray hitを内部requestへ保持する。plannerのcost・片道270度判定・受付時viewへの復帰と、adapterのexecution-start preflight・初回open・各readback openは同じ点を使い、dispatch直前にはlive exact-target hit、station state、通常reachを再検証する。材料・燃料は全stackを一度ずつ投入し、完了後に残燃料とresultを回収する。load後と最終回収後のclose/reopen full-content/data readbackによりstation空、cursor空、材料全量消費、算出済み燃料消費、result exact deltaを確定する。raw slot / GUI座標は公開せず、top-level最終nodeだけに許可する。worst-caseは`2,200 + 200 * max_smelts` active tick、その50倍のms、camera 540度、interaction 7回、distance / break / place 0とする。

`brew_known_potion_batch`はwire shapeを`{id,op,target,expected_block,input:{item,potion,count},ingredient_item,fuel_item,expected_output:{item,potion,count}}`へ閉じる。`expected_block`は`minecraft:brewing_stand`、`fuel_item`は`minecraft:blaze_powder`に固定し、入力と出力は同じ`count` 1〜3を要求する。itemは標準3形式の`minecraft:potion / splash_potion / lingering_potion`、potionとingredientはcatalogの閉じたenumだけを受理する。入出力はcustom name / color / effects等の追加componentのない標準stackと完全一致し、その宣言遷移がMinecraft 26.2の現行`PotionBrewing.mix`と完全一致する場合だけ実行する。

醸造adapterのcamera leaseはAction入場時に確定した`max_camera_degrees_per_second / 20`を保持し、初回照準、readback照準、醸造node受付時viewへの復帰へ同じ0.75〜18度/client tick上限を適用する。照準点は醸造台のblock中心へ置換せず、planner受付に使った配達済みvisible-surface ray hitを内部requestへ保持する。plannerはその点までのworst-case片道`|yaw|+|pitch|`が270度を超える抽象poseをmutation前に拒否し、必要なら同じ可視targetへの`face_known_position`を直前へ要求する。adapterもbegin時のlive poseから同じ点への片道上限を再検証し、初回openと各readback openではlive exact-target hit、stand state、通常reachを維持したまま同じ点へ向く。不一致なら通常use前に固定diagnosticでreplanする。最低速度での最大270度復帰とmenu/cursor ACKを含む有限release期限を確保する。元のLocalPlayerとlevel identityもleaseへ固定し、respawn / level replacement後の別playerへ旧yaw、pitch、selected slotを書き込まない。

全allowlisted遷移はcatalogの`$defs.brewingIngredient.description`へ明記し、schemaを推測で反復失敗させない。Minecraft 26.2の`Builder.addStartMix`は各材料に対し、water→mundaneとawkward→対応効果の2遷移を登録する。例えばbreeze rodは`awkward -> wind_charged`だけでなく`water -> mundane`も許可し、現行`PotionBrewing.mix`との全件unit testで固定する。

Action開始時にbrewing standの5 item slotすべてが空であり、internal brew timeが0、fuel counterがVanilla範囲の0〜20であることをserver full-content/dataで確認する。値そのものは公開せず、不一致は固定diagnosticでreplanする。途中状態のresume、Action replay、既存stand内itemの利用は公開しない。fuel counterが1以上なら既存の1 useを使い、0なら通常menu操作でblaze powderを1個搬入する。宣言したPotionとingredientも有界操作で搬入し、`ingredient_item`もblaze powderのrecipeではprecharged時に1個、未充填時に別途fuel 1個を足した合計2個を必要とする。完了後は宣言outputの完全component一致、ingredientとちょうど1 fuel useの消費、必要時だけinventory fuel 1個の消費、player inventoryへの出力回収、standの5 item slot再空状態をclose/reopenを含むauthoritative readbackで確認する。slot番号、stand内容、brew progress、fuel dataはTool resultへ出力しない。失敗時は入力を解放して未開始suffixを実行せず、自動resumeやblind retryをしない。

成功、失敗、inconclusiveの最初のterminal intentは、Agent-owned screen、server cursor、view、selected slotの解放確認までadapter内部に保持する。tick駆動の正常なrelease進行を同tickのrelease faultとして扱わず、物理入力隔離とAction所有を維持したまま次client tickで再試行し、通常Action終端とEscでは公開terminal後に`READY`へ戻す。normal use送信後かつownership確定前のcancelはexpected-open authorityを即時IDLEへ落とさず、open予約期限までcancel tombstoneとして保持する。一致する遅延OpenScreenは通常のfull-content証拠まで拘束してから閉じ、未到着なら期限後にauthorityを退役させる。Agent-owned screenが一度もmaterializeしていない段階の無関係なpause / inventory screenは閉じず、screen不一致によるmutation拒否とglobal input-release faultを区別する。

このnodeはtop-level bodyの最終nodeだけに置き、`if` / `repeat`内とその後ろのsuffixを静的に拒否する。worst-caseは70,000 ms、1,400 active tick、270度以下の片道照準と醸造node受付時view復元を合わせたcamera最大540度、interaction 16回、distance / break / place 0に固定する。直前の`face_known_position`が必要なActionでは、そのnode自身のcostを別途加算する。menu所有中はrecoveryのgameplay interactionをdispatchせず、現行recovery primitiveはmovement / jumpだけでinteraction usageが0のため、公開`progress.interactions`とActionの`max_interactions`はともに16で閉じる。

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
    "max_camera_degrees": 720,
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

compilerはtree全体について、node数、有限制御構造、capability、`interactions / breaks / places`の最大回数、`wait_ticks / wait_until`の最大時間を静的に証明する。sequenceは和、`if`は各成分のbranch最大値、`repeat`は固定回数倍とする。overflow、上限を証明できないprogram、request budgetまたはlocal hard limitを越えるprogramは入力を発生させず拒否する。

world状態、経路、照準に依存するprimitiveは、Action受付時に最初の実行nodeだけを現在snapshotへbindし、以後は各node開始直前にfreshなKnown Traversability Map、Observation Frame、player poseでJIT計画する。前nodeのworld mutation後に次nodeの証拠がまだ更新されていない場合は、入力をneutralに保った最大40 active tickのreobservation window内でだけ再試行する。

mutation batchは例外的に、node受付時に1〜8対象すべてを共同camera計画して全体costと固定順序を証明するが、各targetの操作権限まで先取りしない。個々のtargetは実行直前のfresh reproofに合格した場合だけbindされるため、受付時witnessの失効を後続操作の根拠にしない。

block mutationのaim pointはfull cubeの仮想中心ではなく、360度観測rayが実VoxelShapeに命中したXYZをwire非公開の内部証拠として使う。その観測時eye originが現在poseと許容誤差内で一致し、命中点がinteraction reach内で、world revision、block、face、必要な成熟状態も一致する場合だけbindする。計画時に選んだblock、face、aim pointはそのlogical occurrenceへ固定し、実行層が別faceや別supportを探索し直してはならない。終点でVanilla互換raycastを1回検証し、不一致ならuse / attackを送らずfresh observationへ戻る。同じlogical occurrence内で既に消費したtick / camera予算は戻さない。

実行時はprogram全体とlogical primitive occurrenceの両方について、各node開始前と各ClientTickで実counterを再検証する。repeatで同じnodeを再度実行しても開始counterはoccurrenceごとに一度だけ固定し、replanで予算を補充しない。`max_duration_ms`は`System.nanoTime`基準のhard deadlineとして各出力前に検査し、pause時間だけを除外するため、client stall時も入力を出さず`BUDGET_EXCEEDED`で終了できる。

templateは`agent_start_action.inputSchema.examples`に掲載し、実装repositoryにも次のJSONを置く。

- [`navigate_to_known.json`](action-templates/navigate_to_known.json): 1地点への移動
- [`collect_visible_drop.json`](action-templates/collect_visible_drop.json): 最新frameで識別した落下物を、連続値XYZから選んだ既知の安全なpickup cellで回収する
- [`collect_visible_drops_batch.json`](action-templates/collect_visible_drops_batch.json): 2〜8件の最新可視落下物を提出順のまま回収する
- [`approach_and_face.json`](action-templates/approach_and_face.json): 移動、health分岐、視点変更または待機
- [`known_route.json`](action-templates/known_route.json): 既知区間を固定回数だけ往復する
- [`break_known_oak_column.json`](action-templates/break_known_oak_column.json): 地上から届く、現在可視な3段oak幹を下から順に破壊する
- [`wheat_cycle.json`](action-templates/wheat_cycle.json): 2区画をmutation batchで耕し、植え、有限成熟待機後に収穫する
- [`open_known_passage.json`](action-templates/open_known_passage.json): 可視な木製通路を開く
- [`inspect_known_container.json`](action-templates/inspect_known_container.json): 明示allowlist対象のVanilla chest / barrelのserver同期済み内容を確認する
- [`take_wheat_seeds_stack.json`](action-templates/take_wheat_seeds_stack.json): wheat seedsをwhole stack 1回だけ取得する
- [`copy_known_oak_beam.json`](action-templates/copy_known_oak_beam.json): 完全なoak log stateを90度回転し、現在supportと先行entry dependencyで2 blockの水平梁を設置する
- [`brew_awkward_potions.json`](action-templates/brew_awkward_potions.json): 片道cameraが270度以内のheadingから、空の既知brewing standでwater potion 3本をawkward potionへ1段醸造する。超える場合は直前に`face_known_position`とその追加budgetを置く

templateもcustom programと同じvalidator、capability、budget、READY許可、安全条件を通る。

#### 8.5.3 受付と応答

受付条件:

- worldとplayerが存在
- READY許可が有効
- 実行中Taskがない
- AST、predicate、capability、static budgetが有効
- 最初に実行するtargetと必要経路が現在のKnown Traversability Mapで使用可能
- 2 node目以降は各node開始直前のfresh observationで同じ条件を再検証
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

入力は`action_id`必須、`wait_timeout_ms`任意（0..25,000、既定0）とする。省略または0なら即時snapshotを返す。正値ならActionがterminalになるか指定時間が経過するまで、同期済みAction stateだけをHTTP worker上で待つ。時間切れはerrorにせず、その時点の非terminal snapshotを同じoutput shapeで返す。Action tickだけではwaiterを起こさず、`succeeded`、`failed`、`cancelled`への遷移で起こす。このlong pollは同時1件に限定し、2件目は`SERVER_BUSY`を返すため、取消・状態確認用のHTTP workerを残す。Minecraft API、client thread、game tickを待機blockしない。

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

`progress`のschema上限は通常Actionと、そのActionをpreemptしたrecoveryの累積上限である。したがってdistanceは32 + 16 = 48 block、cameraは720 + 360 = 1,080度、tickは15,000 + 200 = 15,200となる。break / placeは通常Action最大8とrecoveryの4 / 8を合算して、公開counterの上限をbreak 12 / place 16とする。interactionは`brew_known_potion_batch`がActionの16全枚を使うが、このnodeはAction末尾でmenuを所有し、その間はrecovery gameplay interactionをdispatchしない。現行recoveryはmovement / jumpだけでinteraction usage 0であるため、Action budgetと公開counterのinteraction上限をともに16に固定する。この排他条件を崩すrecovery interactionを将来追加する場合は、先にcatalog、DSL hard limit、progress schemaを再設計する。同dimension内のserver correction、teleport、knockbackなど外力で実測値がこの固定契約を越えた場合、公開counterはschema上限へ飽和させると同時に内部overflow latchを立て、Actionをbudget超過として終了する。飽和値を「上限内」と誤認したり、契約外の値を返したりはしない。

agent_get_stateの返却対象:

- health、absorption、hunger、air、fire、submerged、位置、向き、dimension
- current client tickとworld revision
- 自inventoryのitem別集計
- 自inventoryの標準Potionを`item + potion`で集計したtop-level `standard_potions`。標準componentと完全一致する1本stackだけを数え、custom stackと不可能な複数本stackは除外
- OFF / READY / AGENT / RECOVERING状態、game pause
- 有効policyとhard limit
- DSL version、構造上限、現在許可されたcapability
- 最新immutable observation frameのID、範囲、鮮度、coverage、kind別件数
- 現在または直近action_idと終了理由

生chunk、遮蔽されたentity、chat、看板、本、world seed、tokenはTool resultへ含めない。例外としてautomation-ownedな明示allowlist対象Vanilla chest / barrelを通常useで開いた直後のserver full-content packetから作るbounded item集計だけは、そのActionのtraceへ一時的に返せる。許可された観測recordだけを`agent_get_observation`で最大256件ずつ返す。Action traceはagent_get_actionで最大256件まで返す。既に行った移動、破壊、設置、攻撃、item消費はtransactionではなく、cancel時に自動rollbackしない。不可逆primitiveは実行直前にも観測、capability、budgetを再検証する。

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

認証・HTTP制限違反はTool dispatch前にHTTP error、MCP request構造の違反はJSON-RPC error、domain errorは`isError: true`のTool resultとして返す。2026製品経路だけはこれに`resultType: "complete"`と`_meta`も付け、Codex互換経路ではlegacy射影から除く。

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

catalog schema違反はcatalog順に最大4件を1つのbounded messageへ集約し、1回の修正で回復できるようにする。path、required/type/enum等の診断材料はcatalogからだけ導出し、未知property名、提出値、秘密は反射しない。`PROGRAM_BUDGET_UNPROVABLE`も不足した`budget.max_*` component名をすべて固定順で返すが、提出値やworld座標は返さない。Tool errorの公開shapeは引き続きexact `{code,message,recoverable}`とする。

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

Action DSLが宣言できる`max_interactions`のhard upper boundは16とする。通常nodeは各自のより小さい静的costに従い、`brew_known_potion_batch`だけが閉じたmenu protocolのため16を予約する。

実行器は指定budgetとローカルhard limitの小さい方を採用する。超過しそうなGoal primitiveは実行しない。能動的危険がなければそのtickで`BUDGET_EXCEEDED`として入力を解除し、同じworld・capabilityのREADYへ戻す。危険が進行中ならGoalを破棄して第10章の固定recovery budgetだけを使用する。

program全体のeffective budgetに加え、各logical primitive occurrenceにもcompile済みcost boundを適用する。距離とcameraの実行器へ渡す残量は両者の小さい方とし、tick、duration、interaction、break、placeも各tickで双方を検査する。`wait_ticks`も同じ対象であり、replanやprobeによってoccurrence上限を更新しない。

camera costは解析的なyaw/pitch誤差に加え、Vanillaの`player.turn`が0.15 scaleとfloat回転へ量子化する際の上限0.25度をcamera primitiveごとに事前予約する。幾何学上0度に近いcontainer照準やbatch suffixでも、このreserveをglobal / occurrence双方へ同じ値で含め、実行後に初めて予算不足となることを防ぐ。

### 9.2 navigate_to_known — MVP

`target`はKnown Traversability Mapのcurrent edgeに現れるplayer feet-spaceであり、床や土などの支持blockではない。LLMは`agent_get_observation`の`traversability.navigation_target`を無変換コピーし、連続値`from / to`をfloor / roundしたり、visible surfaceの座標から立ち位置を推測したりしない。

対応:

- `max_distance_blocks`の公開上限は32 block。A*は検索時に未知な開始cell内の実poseと誤差のため`1.5 × √6`（約3.67 block）を先取りし、残る約28.33 blockを`1.5 × centerline edge length + 垂直edgeごとに1.5`で消費する。このため平坦なcardinal経路は最大18 edgeで、垂直edgeを含む経路はさらに短い。A*がこの上限内で返すfreshな経路は、開始poseが規定envelope内なら、単独primitiveのdistance componentについて公開最大budget 32で静的受付可能でなければならない
- 同一dimension
- CONFIRMEDまたは条件を満たすPROBE_ALLOWED edge
- 同一高さの通常歩行と、既知edge上のslab、stairs
- 現在の局所観測入口から上下4 rung以内で連続する完全な`minecraft:ladder`と、床付きlanding間の昇降
- 現在の局所観測入口から上下4段以内で連続する、乾いて安定済みの`minecraft:scaffolding`と床付きlanding間の昇降
- forward、back、strafe、視点調整

非対応:

- block破壊・設置
- doorやcontainer操作
- 水泳、boat、elytra
- 2 block以上の連続pillaring、ladder / scaffoldingの新設・欠損段越え（1 Action 1 blockの`pillar_up_known`だけは対応）
- gap jump、parkour
- sprint、combat
- frontier探索
- full-block 1段分のstep-up / step-down edge自動生成

ladderは上昇時だけJUMP入力を使い、下降時はSHIFTを使わない。scaffoldingは上昇時にJUMP、下降時だけSHIFTを使う。中間段はA*の内部transitに限り、床付きlandingだけを`navigation_target`として公開する。各micro-stepでblock種、ladder取付、scaffoldingの`distance < 7`・`canSurvive`・非waterlogged、clearance、窒息、fluidを再検証し、欠損または低天井等では入力をneutralにしてfail closedとする。

経路が変化した場合は影響edgeだけをSTALEにし、現在AABBが安全なら再検証と局所再計画を行う。未知supportへは出ず、既知graphとPROBE_ALLOWEDだけで代替経路がない場合に`PATH_BLOCKED`とする。現在AABBが危険なら第10章のRECOVERINGへ昇格する。

full-block高低差edgeを能動生成する処理は未実装であり、上記ladderの閉じたedge生成とは区別する。必要edgeを推測・合成せず、target自体が未知なら`TARGET_UNKNOWN`、targetは既知でも接続edgeがなければ`NO_KNOWN_PATH`として入力前にfail-closedとする。

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
- 幹破壊template単体は取得item数を観測値として報告できるが、drop由来や回収完了を保証しない。後続の`collect_visible_item` nodeは、freshな可視dropごとにinventory絶対個数増加まで確認できる

初回sliceはAction受付時に全対象面が現在可視である単純な直立幹だけを扱う。同一targetの重複と`repeat`内の破壊を静的拒否し、隠れた幹をchunk走査して探索しない。破壊で新たに露出した面の遅延再観測と苗木の植林は後続sliceとする。drop回収も幹破壊template内へ暗黙追加せず、最新frameを再取得して明示的な`collect_visible_item` Actionを後続実行する。

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

高難易度建築copyの正式評価で、以下のvertical sliceだけでは任意規模・高所の施工を完結できないことを確認した。Phase 3の完成条件と採用ロードマップは[高難易度建築コピー失敗レビューと採用ロードマップ](./experiments/03_building/2026-09-03_hard-building-review-and-roadmap.md)に従う。特に、観測して覚えた`placement_state_ref`と実行直前のtarget/support証拠を分離し、既存のA*・BlockState transform・通常設置・server ackを再利用するcheckpoint式construction jobを追加する。LLMは目標、source/destination、transform、材料・作業領域を決め、runtimeは作業姿勢、有限phase、所有足場、隣接stateの検証順、cleanupを担当する。

現在公開するvertical slice:

- `visible_surface.state / placement_item`はrequired nullable field。完全stateは閉じたcopy/support allowlistだけに公開し、`placement_item != null`なら`state != null`
- `apply_known_block_plan`はstationary・place-onlyの1〜8 entry
- `clear_known_block_plan`はstationary・break-onlyの1〜8 entryで、置換は再観測後の別Actionに分ける
- source相対offsetと方向stateを同じmirror / rotationでruntime変換
- 現在policy-visibleなsupport、または入力順で先行するentryだけへ依存可能
- NBTなしで通常設置できるallowlisted blockだけ
- survivalではinventory内のitemだけ
- fill / setblockを使わず、通常設置操作とauthoritative postconditionだけを使用

~~~json
{
  "id": "copy_slice",
  "op": "apply_known_block_plan",
  "anchor": {"dimension": "minecraft:overworld", "x": 20, "y": 65, "z": 20},
  "transform": {"rotation": 0, "mirror": "none"},
  "entries": [
    {
      "id": "entry_0",
      "offset": {"x": 0, "y": 0, "z": 0},
      "source_state": {
        "block": "minecraft:oak_log",
        "properties": {"axis": "y"}
      },
      "item": "minecraft:oak_log",
      "support": {
        "position": {"dimension": "minecraft:overworld", "x": 20, "y": 64, "z": 20},
        "face": "up",
        "expected_state": {"block": "minecraft:stone", "properties": {}},
        "dependency_entry_id": null
      }
    }
  ]
}
~~~

これは固定建築macroではない。LLMが可視sourceからstateとitemをコピーし、任意の有限entry列を公開DSLで構成する一方、target座標と向きstateのtransform、support dependency、設置入力、ACK / postconditionはruntimeが決定論的に処理する。自動rollbackは保証せず、失敗時は入力を止め、変更済みentryをtraceとして返す。

Phase 3完成時に追加する上限:

- 最大box: 9 × 9 × 9
- 最大変更: 256 block
- NBTなしの通常full block
- fluid、gravity block、container、portal、command blockは不可
- 許可box内だけ
- survivalではinventory内のitemだけ
- creativeでもfill/setblockを使わず、通常設置操作だけ

### 9.6 brew_known_potion_batch — Phase 4

対象は現在のpolicy-visible surfaceで確認した、通常interaction reach内の`minecraft:brewing_stand`に限る。自inventoryの`standard_potions` recordからinputを選び、そのitem+potion集計count以下の1〜3本を宣言し、catalogが列挙する既知の1段recipeと完全一致するoutputを宣言する。必要なPotion、ingredient、blaze powderは自inventory内の実itemだけを使い、生成や補充はしない。

受付時と通常use直前に対象、pose、reach、surface revision、universal safety、menu非所有を再検証する。planner受付時とadapter begin時には、block中心ではなく受付に使った配達済みvisible-surface ray hitへの片道`|yaw|+|pitch|`が270度以下であることを独立に証明し、初回openと各readback openも同じ点を使いながらlive exact-target hitを要求する。上限を超える場合はmutationを始めず、同じ可視targetへの`face_known_position`を直前に置くようreplanする。open後はserver full-contentで5 item slot空、brew time 0、fuel counter 0〜20を確認し、同期済みmenu dataに対して有界でPotion、ingredient、および未充填時だけfuelを移送する。brew progressは外部へ公開せず、serverが送ったcontent / dataの変化を最大1,400 active tickの内部state machineで待つ。完了後の回収とreadbackで宣言output、消費、fuel counterの1 use減少、余剩を確認し、menuを閉じてscreen ownershipとsynthetic inputの解放が確認できてからterminal resultを公開する。中断時はその時点のauthoritative inventoryを維持し、空でないstandを別Actionで再開して自動続行しない。

### 9.7 crafting・精錬・workstation・MOD互換 — Phase 4

現在の公開Action DSLは、既存のrecipe / container / screen同期基盤を直接再利用したcrafting-table限定の`craft_known_recipe`、furnace familyでexact stack 1〜64個を処理する`smelt_known_recipe`、共通Menu kernelの`operate_known_menu`を含む。最後のものは、ユーザーが現在開いているexactなVanilla `generic_9x1`〜`generic_9x6`純storage、または`Sophisticated Backpacks 3.25.90 + Sophisticated Core 1.4.99`の通常`backpack`画面だけを受理し、stateが発行したsingle-use `operation_ref`で通常最大数以下の1 stack全量をplayer inventoryへQUICK_MOVEする。player 2×2、専用workstation、backpack内upgrade / craft / smeltは未実装であり、製品機能として利用可能とは扱わない。

公開Toolは5件のままとし、クラフトやworkstationごとのMCP Tool、raw slot番号・画面座標・key/mouse・packetを追加しない。recipe検索は既存のquery / output / resolve契約を固定5 Toolのread pathへ委譲し、第二の検索文法を作らない。`recipe_ref`とfingerprintはworld sessionとrecipe catalog revisionへ束縛し、`craft_known_recipe`開始時と各craft前に再解決する。実行は`agent_start_action`の閉じたsemantic opcodeだけを使う。

- 公開済み`craft_known_recipe` / 既存`craft_items`: 現在は`crafting_table`だけ。次に`player_2x2`へ拡張
- 公開済み`smelt_known_recipe` / 内部`smelt_items`: `furnace | blast_furnace | smoker`、1 Actionでexact stack 1〜64個
- 公開済み`operate_known_menu`: `minecraft:generic_9x1-pure-storage@26.2`〜`minecraft:generic_9x6-pure-storage@26.2`、storage→playerの1 stack全量だけ
- 公開済みMOD profile: `sophisticatedbackpacks:backpack-pure-storage@3.25.90+core-1.4.99+mc26.2`。両active jarのversion / SHA-256とMenu / Screen / method contractが完全一致し、upgrade tabとextra slotが閉じた通常storageからplayerへの1 stack全量だけ

司書厳選のread-only first sliceでは、ユーザーまたは既存経路が現在開いているVanilla `MerchantScreen`だけを対象にする。world session開始時からmerchant-offers packetをimmutableに記録し、`agent_get_state`時点のScreen instance、player menu、world session、container ID、直近OpenScreen packet revisionがlatest merchant packetと完全一致する場合だけ、optional `merchant_offers`を返す。公開内容はitem ID / count、uses / max uses / out-of-stock、merchant level / XP、エンチャント本の登録済みstored enchantment ID / levelに閉じる。raw slot、Data Component / NBT、lore、表示文字列、未解決enchantment IDは返さない。画面を開く、任意click、取引実行、職業ブロックの破壊・再設置、reroll反復はこのsliceに含めない。

Vanilla inventory文法だけでは、独自widget、ghost slot、fluid / energy表示、canvas内control、MOD固有のclient callbackを持つ画面を扱えない。このため、screen ownership、同期、参照解決、操作配送、postcondition、cleanupを一元化する共通Menu interaction engineをPhase 4の基盤とする。ただし、LLMが`click_slot(17)`や`click_at(142, 38)`を渡す万能remote-controlにはしない。現在実装済みの最小kernelは、exact profile / Screen / container / state / packet revision / 全slot snapshotへ束縛した`operation_ref`だけを公開する。次の拡張では、同じread pathから必要になった種類の短寿命opaque参照を追加する。

- `menu_ref`: world session、同一Screen instance、container ID、menu type / class、state ID、slot数、profile hashへ束縛
- `element_ref`: profileが許可したslot group、widget、text field、canvas hit region、progress / cost fieldへ束縛
- `stack_ref`: server同期済みの完全なItemStackとsource、count、Data Component fingerprint、menu / inventory revisionへ束縛
- `operation_ref`: profileが許可した`transfer`、`activate`、`enter_bounded_text`等の1操作と、その期待遷移・resource上限へ束縛

現在の`operate_known_menu`は`{id,op,operation_ref}`へ閉じ、Action内でtop-level最終nodeとして1回だけ受ける。refの内部recordはruntimeだけが保持し、LLMへraw slot、座標、component / NBT、callback class、packet payloadを返さない。操作直前にrefを再解決し、session、同一Screen identity、container ID、menu type、state ID、slot数、profile hash、packet revision、全source snapshotのいずれかが変われば配送せずreplanする。dispatch後はfresh server packetを待ち、source empty、他storage slotとMOD profileの全protected slot不変、player slotの完全multisetとcomponent-exact個数を確認し、cursor emptyのまま画面を閉じてから成功にする。複数operationのtransactionは、実タスクで必要になるまで追加しない。

Menu profileは、対象MOD名、version、active jar SHA-256、menu / Screen class、slot shape、許可操作、入力保存則、成功条件を記述する小さな組込み宣言dataとする。最初のMOD profileは外部loaderを作らず、Sophisticated Backpacks 1 buildだけを組込み、NeoForgeが実際にロードした両jarを起動時に検証する。Menu classの公開getterでstorage / inaccessible / open-upgrade / extra-slotを分類し、playerの36 slot以外は全てprotectedとして扱う。未知version / hash / class / methodではprofileを無効化し、production中の自動推測やpixel操作へfallbackしない。

recipeとitem IDは`minecraft:`へ限定せず、clientへ通常同期され、registryに存在するMOD namespaceも受理できる。ただしrecipe manager、server内部state、JEI等の別MOD内部cacheをhidden-state経路として読まない。同一item IDでもData Componentが異なる道具、enchanted book、template、upgrade済みMOD item等は、現在のstorage sliceでは`operation_ref`内部に完全な`ItemStack`を保持し、公開component / NBTや新しい`stack_ref`を追加せずcomponent-exactに照合する。custom ingredient、crafting remainder、container item、tool damage、経験値消費は、対応profileが全入出力の保存則と事後条件を定義したrecipeだけを受理する。

player 2×2 craftingはblockを通常useしないため、開始時にScreenとcursorがclearであることを要求し、runtime自身が同じ`InventoryScreen` instanceだけを所有する。client-known recipe placementと結果slotの通常container actionを使い、dispatch後のserver由来state ID / slot更新、outputのinventory絶対個数、cursor empty、2×2 grid / resultの解消を確認してから閉じる。block menuのclose / reopen full readbackを代用せず、必要なserver同期が有限期限内に揃わなければ成功にしない。

crafting tableは、可視・既知・通常reach内の対象をruntime自身が開き、client-known shaped / shapeless recipeを配置する既存adapterを起点にする。完成品を1回ずつ回収し、menuを閉じて同じblockを再度開いたfresh full-contentとplayer inventory絶対個数で各craftを確定する。recipe placement、result回収、再openのいずれかが曖昧ならblind retryせずterminal failureとする。

かまど・溶鉱炉・燻製器は同じ3-slot protocolを1つのadapterで扱い、station種別ごとにclient-known recipe kindだけを制限する。開始時slot、燃料残量、cook progressをserver同期から取得し、投入量、燃料消費、progress開始→完了、output回収、close / reopen readbackを有界に検証する。既存途中状態の引継ぎは、入力・燃料・outputの所有権と期待差分を開始時に完全証明できる場合だけ別contractとして追加し、初回実装は空stationからの1 batchに限定する。

stonecutter、smithing、cartography、anvil等は共通engineを使うが、recipe選択、template、component mutation、map state、rename文字列、経験値costを同一の保存則とは扱わない。差分はMenu profileのoperationとpostconditionへ記述し、対象profileの実タスクで必要な順に追加する。看板、本、chatと同様、外部由来の文字列を命令として解釈せず、anvil renameのような文字入力はユーザーがActionへ明示したbounded literalだけを使う。

MOD menuは次の2種類に分ける。

1. 純storage: player inventory slotを識別でき、残る全slotが保管専用で、result / payment / fuel / upgrade / ghost / fluid / energy semanticsを持たないことをprofileで証明できるもの。共通engineの`transfer`とfresh full-content readbackを使う。
2. machine / custom workstation: slot、widget、data、component変化、期待遷移をversion固定profileで定義するもの。通常のScreen widget callbackまたはMenu actionをprofileが特定できる場合は、共通engineがそのclient経路を呼び、対象MOD自身に通常packetを生成させてよい。MCMCPが未知のcustom packetを組み立てることはしない。
3. 非inventory canvas: slotを持たないが安定したwidgetまたはhit regionを持つ画面。画面scaleとprofile shapeが一致し、破壊的操作でなく、操作後のserver同期済み状態を検証できるcontrolだけを`element_ref`化する。単なる画像認識座標は受理しない。

未知menu、profileと異なるslot数・class・menu type・widget shape、結果をclientのserver同期から検証できないoperationはread-only観測に限定し、最初のAgent mutationより前に`UNSUPPORTED_MENU_PROFILE`相当で拒否して閉じる。対象24 MODの更新でmanifestが変わった場合はprofileを流用せず、別紙baselineを更新して同じGameTest / clone smokeを再実行する。MOD用のserver companion、MCMCP独自payload、handshakeは要求しない。

すべてのadapterは、通常use / menu openの因果ACK、exact menu ownership、cursorを変化させるclick直前のcursor証明失効とfresh server cursor証明、cursor-invariantなQUICK_MOVEでは直前のserver-confirmed empty cursor維持、絶対inventory差分、有限budget、Esc / UI OFF / world境界、terminal前のScreen・cursor・camera・slot解放を9.6と共通の必須条件とする。途中まで消費・生成されたitemをrollbackしたふりはせず、最初のterminal intentとauthoritative inventoryを保持する。cleanupが証明できない場合は成功・失敗を公開せず、入力隔離を維持してfail closedにする。

### 9.8 RedstoneSpec — Phase 5

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

公開済みsliceは`apply_known_redstone_spec:{id,op,anchor,rotation,components:[{id,role,block}],truth_table:[{inputs:{input},outputs:{...}}],footprint:{x,y,z},timing:{settle_ticks}}`へ閉じる。`components`は`input/input/minecraft:lever`と`output/output/minecraft:redstone_lamp`を基礎に、fan-out時だけ`output_2/output/minecraft:redstone_lamp`、直線wire時だけ`wire/wire/minecraft:redstone_wire`を加える。直接1出力は`2x1x1`、fan-outと直線wireは`3x1x1`、rotationは`0 / 90 / 180 / 270`、settleは1〜20 tickだけを受理する。`anchor`は最初のlamp targetであり、直接版とfan-outのleverはrotation方向`+1`、fan-outの2個目lampは`+2`、wire版のdustは`+1`、leverは`+2`とする。LLMにblock座標とnavigation座標を相互変換させず、runtimeがこの固定offsetだけを適用する。

plannerは直接版とfan-outでは各lamp直下の現在policy-visibleな不活性UP面とlever直下の現在policy-visibleな`minecraft:glass` UP面を、wire版ではlamp / dust / lever直下の3つの現在policy-visibleなglass UP面を要求する。wire版は3 componentの周囲1 blockを固定glass / air envelopeとして最初の配置前にLIVE current / visible観測する。targetとsupportはinteraction reach内、同じworld/session/revisionでなければならず、movement 0、break 0で固定する。実行開始前に自inventoryのlampを出力数分、leverを1個、wire版ではredstoneを1個確認する。wire版はlamp設置→lever設置→dust設置後、lever / dust / lampを同じclient tickでOFF観測し、lever操作→ON観測→再操作→OFF観測する。設置と操作は既存semantic action / universal safety / server reconciliationを再利用し、hidden power state、server MOD、command、raw input、任意packetは使わない。

静的worst-caseは`100 * (component_count + 2) + 3 * settle_ticks` active tick、その50倍ms、camera 720度、interaction 2、placement `component_count`、distance / break 0とする。途中失敗では未開始suffixを実行せず、完了済みmutationとbudgetを監査traceへ残し、terminal公開前に既存の入力解放契約を通す。wire版はlampを`anchor`、dustをrotation方向`+1`、leverを`+2`へ固定し、3つの可視glass UP support、直線shape、`power=0→15→0`を要求する。可変長・曲がり・wire付きfan-out、NOT、repeater、一般Blueprint変換、任意回路合成はこのopcodeで利用可能とは扱わない。

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

候補が失敗してもknown worsening stateで即座に立ち止まらず、budget内で次の低リスク候補へ切り替える。安全化したら元programを暗黙resumeせず、Actionを`SAFETY_RECOVERED`で終了してREADYへ戻す。全候補とbudgetが尽きたら入力を解除し、`RECOVERY_EXHAUSTED`、試した候補、最終状態を返す。EscとOFFはRECOVER中も常に最優先である。

Phase 1の実装対象は、neutral、既知nodeへの短い退避、上方向への水泳、既知非危険流体への退出、既知着地点への姿勢制御までとする。item use、block配置、breakを使う回避は、それぞれの専用GameTestを通過したPhaseで有効化する。後続実装を見越した安全分類とbudget契約はPhase 1から固定する。

## 11. マルチプレイ

client-only構成では、サーバーが本MODを許可していることを技術的に確認できない。

既定値:

- singleplayer: 利用可能
- multiplayer: 無効
- multiplayerを使う場合: Screen上の警告で現在の接続先を明示確認してローカルallowlistへ保存し、接続sessionごとにScreenからON

allowlistは許可を証明するものではなく、誤操作防止だけを目的とする。サーバー規約の確認責任はユーザーにある。

`config/mcmcp/allowed-servers.json`のschemaは次へ固定する。

~~~json
{"schema_version":1,"servers":["example.org:25565"]}
~~~

root propertyは`schema_version`と`servers`だけ、versionは1、fileは16 KiB以下、entryは最大64件の文字列とする。各entryは前後空白除去・小文字化後255文字以下かつcontrol文字なしでなければならず、現在の接続addressとportを含めた文字列の完全一致だけを許可する。wildcard、DNS展開、port補完、未知property、壊れたJSON、欠損fileはすべて不許可とする。欠損fileはScreen上の物理確認からだけ作成し、利用にはこの完全一致とsessionごとのScreen上ONが必要である。

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

次のlocal設定を使用する。TOMLとtokenは初回起動時に生成し、`allowed-servers.json`はmultiplayer警告を物理操作で承認した場合だけMODが上記schemaで作成する。欠損時はfail-closedとする。

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

MVPではrecovery各値の設定可能な上限を200 ticks、16 blocks、360 degrees、8 interactions、8 placements、4 breaksとする。Goal上限との合算が`agent_get_action`の固定出力schema（12,200 ticks、48 blocks、1,080 degrees、12 breaks、16 placements）を越えないことをconfig境界で保証する。interactionはAction上限16に対し、現行recovery executorがinteractionをdispatchせず使用量0である不変条件を別途検査し、公開counterも16へ閉じる。

tokenはconfig screen、clipboard、Codex / Claude Code設定へ平文表示・複製しない。ワールド内のEscメニューから明示確認付きの自動設定を行い、各client公式のdynamic header helperにowner-only token fileを接続する。既存設定は初回だけbackupし、管理外の同名entryは上書きしない。

### 12.4 Prism導入

1. gradlew buildでjarを1個生成
2. Prism Launcherで「くらふとぶ！-v01.2」を編集
3. Mods画面からjarを追加、またはminecraft\modsへ配置
4. 初回起動後、HUDでMCP endpoint状態を確認
5. ローカルMCP hostへendpointとbearer tokenを登録

mmc-pack.json、既存MOD、world、server設定は書き換えない。

### 12.5 既存24 MODとの互換方針

- renderer内部に依存せず、Vanilla/NeoForgeのOUTLINE・VISUAL・COLLIDER shapeとplayer eye原点の全周sampleを使う
- MOD menu・item・recipeは9.7の共通Menu interaction engine、opaque参照、version固定profile契約に従う
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

性能値は対象Prismプロファイル上で測定し、全周visualの半径・ray/tick、path expansion上限をhard range内で調整可能にする。Local Observation Volumeは安全契約を一定にするため半径6 block、最大128 transition、毎tick最大512仮想評価へ固定する。構成上の合法な最大record数がframe上限16,384以下であることをcontract testで固定する。

### 13.2 停止・回避応答

- Esc、ScreenのMCP操作OFF、MCP cancelから次のClientTickまでにTaskを停止
- 20 TPS時の目標は50 ms以内、負荷試験上限は100 ms
- Agent所有input・使用/破壊状態・追跡velocityをすべて解放してからterminal stateを公開し、HTTP waiterがterminalを観測した時点でsynthetic keyがdownのまま残らない。全解放を最大3回試しても成否未確認ならMCP操作をOFFへlockし、最初のterminal intentとREADY未公開状態を保持する。次ClientTick先頭でも再解放し、成功後は同一terminalだけを公開するが、安全fault後の自動再armはせず手動ONまでOFFを保つ
- exception、disconnect、world変更時はfail-closed
- RECOVER判定から次のClientTickまでにGoal入力をpreempt
- focus喪失だけではAction stateを変更しない。Screen表示はglobal STOP / OFF条件にせず、個別primitiveのscreen-clear gateとして扱う

### 13.3 audit

- Taskごとのメモリ内ring buffer
- 主要eventだけをSLF4Jへ構造化出力
- token、chat、看板、本、全chunk情報をlogしない
- evaluation lease UUID、runnerへ渡すheader、raw private chain-of-thought、reasoning delta、raw Tool引数・結果をlogしない。評価artifactにはlease IDのhash、acquire / terminal時刻、固定reason、`inputs_released`、`input_owner_none`、`all_actions_terminal`、`process_identity_bound`の独立Boolean proofだけを残す
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
- EVALUATION_TURN_ACQUIRED
- EVALUATION_TURN_TERMINATED

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
- 公開`/mcp`のGETとDELETEは405、POSTだけを受理
- 2026経路の`MCP-Protocol-Version`欠落・不一致を拒否
- 2026経路の`Mcp-Method`とJSON-RPC methodの不一致を拒否
- clientInfoを省略した適合requestを受理
- Codex CLI 0.146.1の実captureと同じ`initialize` / `2025-06-18` → `notifications/initialized` / HTTP 202 → `tools/list` → `tools/call`が成功
- 2025/2026 method、version、custom headerを混ぜたrequestはfail closedになる
- 2026経路でserver/discover、tools/list、tools/callが成功
- tools/listが別紙どおり5 Toolを固定順で返す
- 2026経路のserver/discoverとtools/listがresultType、ttlMs、cacheScopeを常に含む
- 2026経路の成功responseがresult._metaのserverInfoを含み、両経路のJSON responseがContent-Type application/jsonを含む
- `@modelcontextprotocol/conformance@0.2.0-alpha.11`の固定Tools-only scenarioが全件成功
- tokenがlog、URI、Tool resultに含まれない
- 内部evaluation-turn endpointもloopback / Host / Origin / Bearer / body上限を維持し、未認証、dead runner、別lease、同時2件目を拒否する
- evaluation-turn endpoint / headerが`server/discover`、`tools/list`、catalog、dynamic Tool schemaへ現れず、公開Toolが固定5件のままである

### 14.3 操作権

- OFF中のagent_start_actionはMCP_OPERATION_DISABLEDで入力を変更しない
- 有効worldのScreen buttonからONにでき、READYはAction開始、明示OFF、またはworld変更まで維持
- READY中に同時受理するActionは1件だけで、成功・明示cancel・recoverable failure後はREADYへ戻る
- 同時2件目はTASK_BUSY
- enqueue後、ClientTick前にworld、READY、control epochが変わった場合は入力せず失敗
- READY中のEscは通常どおりchat/menuを閉じ、READYを維持
- AGENT/RECOVERING中のEscは1 ClientTick以内にEMERGENCY_STOP、queue破棄、入力解除、READY
- evaluation-turn中はActionがない推論区間でも物理入力を隔離し、Escでrun失敗、入力解除、lease terminal、READY復帰となる
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
- evaluation-turn acquire時の推論・入力lock案内は3秒で消える
- AGENT開始時のEsc案内は3秒で消える
- Screen表示中は右下にicon、状態文、ON/OFF buttonを表示
- AGENT/RECOVERING中はgameplay、chat、inventory、menuの外縁に2 pxの黄色枠を表示し、停止後は同じframeで消える
- evaluation-turnの推論区間は2 pxのcyan枠、Action / recovery中はyellow枠となり、正常release、Esc、UI OFF、world境界、shutdown、runner終了、stream切断、deadlineの入力解放後に枠が消える
- evaluation-turn terminalを公開した時点でinput ownerがなく全Actionがterminalであり、通常完了とEscではMCP操作ONのREADYを維持する
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
- agent_get_stateがtop-level `standard_potions` を`[{item,potion,count}]`で返し、自inventoryの標準component完全一致の1本stackだけをitem+potionで集計し、custom Potion、不可能な複数本stack、stand内容を含めない
- agent_get_stateのoptional `merchant_offers`は現在のVanilla MerchantScreen、player menu、world session、container ID、open packet revisionがlatest merchant packetと一致する場合だけ現れ、typed取引factsと解決済みstored enchantment ID / levelだけを返し、raw slot / component / NBT / lore / text / 未解決IDを含めない
- agent_get_stateで告知したframe IDはidle 60秒、最大16件のLRU上限内で保持され、上限超過とworld境界で確実に失効する
- agent_get_observationは任意center/radiusを受け付けず、同じframe_idのpage内容がframe保持中に変わらない
- 最大256件でpage分割し、壊れたcursorはINVALID_CURSOR、保持外frameはFRAME_EXPIRED
- page継続中のframeはleaseでpinされ、rolling frame更新後も同じcursor再送が同じpageを返す
- visible_surfaceはunique block positionごとの代表面へ圧縮され、mature crop、immature crop、その他の順、各群内は近距離順となる。複数kindはround-robinで公平に混在する
- visible_surfaceはrequired nullableな`state / placement_item`を常に返し、完全stateは閉じたcopy/support allowlistだけ、対象外はnull、非null stateでは`block == state.block`、`placement_item != null`では`state != null`となる
- summaryのvisible_surface件数は代表面圧縮後の返却可能件数と一致する
- 返却済み静的surfaceだけが最大60秒再利用され、未返却pageとentity / item / hazard / traversability / soundは延長されない。再利用時もrevision、pose、reach、commit/JIT、ray fenceをすべて通る
- 完成frameのsampling_coverageは1で、各recordのorigin、observed tick、world revisionとframe_completed_tickから鮮度を再現できる
- 斜めtraversability recordがfrom/to edgeを保持し、単一cellへ潰れない
- DSLのunknown opcode、重複node id、深さ5、source node 65、展開node 257、repeat 17を入力前に拒否
- 同じ展開実行経路に複数の`face_known_position`を含むDSLを受理し、AST、node、時間、camera累積budgetだけを適用
- `if`はpolicy-filtered snapshotだけを評価し、欠損fieldはPREDICATE_UNAVAILABLE
- 任意式、while、until、再帰呼出し、chat/text predicateを拒否
- 静的costが証明不能ならPROGRAM_BUDGET_UNPROVABLE
- mutation batchのTARGET_UNKNOWNは最初に不足した提出配列indexをtarget[index]で返し、hidden座標やstateを追加しない
- `apply_known_block_plan`は1〜8 entry、offset各軸±8、entry ID / 変換後target一意、support unionの両nullable field明示、先行dependency一致を入力前に検証する
- block planの`none/x/z` mirror後CW rotationが既存BlockPlan変換と一致し、offsetと完全BlockStateへ同じtransformを適用する
- block planは入力順を維持し、1 entryあたり15,000 ms / 300 ticks / camera 80度 / 1 placement、distance / interaction / break 0の静的costから逸脱しない
- `apply_known_redstone_spec`はlever入力1件に対し、lamp出力1件、lamp出力2件、またはlamp出力1件と1 dustの直線のいずれかだけを受理する。各出力が入力と一致する真理値表2行、footprint 2x1x1または3x1x1、rotation 4種、settle 1〜20はschema / validator / compilerで一致させる
- Redstone plannerは各lamp直下のcurrent visible inert UP supportとlever直下のcurrent visible glass UP supportを要求し、stationary requestの全target・aim・boundsへ同じrotationを変換する
- Redstone実行は最初の配置前に固定fixtureのglass / airをLIVE再確認し、全component設置、入出力集合のOFF / ON / OFF確認を入力順に行う。直線wire版はdust shapeと`power=0→15→0`も同一tickで確認し、`100 * (component_count + 2) + 3 * settle_ticks` tick / 2 interaction / `component_count` placementから逸脱しない
- Vanilla ladder / scaffoldingは観測入口の上下4段以内だけを内部edge化し、支持床のあるlandingだけを公開目的地にする。scaffolding下降だけSHIFTを使い、欠損段、低天井、fluid、水没、支持喪失を拒否し、上下ともterminal時にmovement inputを解放する
- `brew_known_potion_batch`は標準Potionの既知の1段recipe、1〜3本同数、blaze powder固定、開始時5 stand item slot空、internal brew time 0、fuel counter 0〜20を入力前に検証し、prechargedならinventory fuel投入を省略する
- `craft_known_recipe`は同じrecipe query結果の24文字opaque refとSHA-256 fingerprint、crafting tableのexact state、component-exact絶対inventory目標1〜2,304、`max_crafts` 1〜3だけをschema / validator / runtimeで一致して受理する
- crafting nodeは最大400 active tick、最低30,000 ms / 600 ticks / camera 360度 / `1 + 4 * max_crafts` interactionsを予約し、各完成品を1回分ずつcursor-safeに回収してclose/reopen full-contentのexact deltaで確定する
- 醸造nodeはtop-level末尾のみで`if` / `repeat`内とsuffixを拒否し、plannerとbegin直前preflightで片道camera 270度以下を証明し、70,000 ms / 1,400 ticks / camera最大540度（照準＋醸造node受付時view復元）/ 16 interactionsを静的に予約し、resume / replay / blind retryを行わない
- 公開`progress.interactions`の上限16は、醸造中のrecovery interaction非dispatchと現行recovery usage 0の不変条件を含めて検証される
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
- wheat age、air↔wheat、farmland moisture等のnavigation-neutral mutationがglobal world revisionを進めても部分frameを破棄せず、combined-wheat fixture overrideの512 rays/tickなら4 ClientTick以内に完成する（製品既定は256 rays/tick・8 active tick）。collision・遮蔽・support・fluid・hazardへ影響するLOCAL mutation、chunk更新、全失効はvisual revisionを進め、部分frameを破棄する
- surface recordは対象BlockPosの直近mutation、直近LOCAL mutation、位置別ledger eviction floorの最大revision以上かつ現在revision以下だけを採用する。無関係なneutral mutationでは継続利用でき、同じ対象のneutral mutation、任意LOCAL mutation、未来revisionでは拒否する
- visible item witnessは最後のLOCAL mutationに対応するglobal revision以上、現在revision以下、かつ1 scan周期以内の場合だけneutral mutationを跨いで利用できる。LOCAL mutationより古いentity recordと未来revisionは拒否する
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
- recovery成功後は元ActionをresumeせずSAFETY_RECOVERED、READY
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
- current ClientLevelの非cancel `PlayLevelSoundEvent.AtPosition`だけをXYZ付きで記録し、master volume 0とheadless audio deviceでも同じclueを得る
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

test件数そのものを品質指標にしない。新しいassertionが既存testと同じproduction分岐、fixture、failure mappingを確認するだけなら、最も近いcontract testへ同じscenarioとして追加し、別の`@Test`を増やさない。Javaが生成するrecord accessor、公開契約でない定数、同じ条件の言い換えだけを確認するtestは置かない。一方、認証・入力解放・world境界・concurrency・fail-closed診断の異なる分岐は、短くても独立した回帰testを維持する。test整理ではassertion数だけを保つのではなく、削除前後で通過するproduction分岐と失敗時の検出点が同じことを確認する。

- Action DSL JSON Schema、semantic validation、cost vector
- bounded if/repeat、predicate availability、capability validation
- client-known recipeのsession / revision束縛、MOD namespace、`stack_ref`によるData Component同一性、untrusted text分離、unsupported recipeのfail-closed判定
- Menu profileのschema / hash、`menu_ref` / `element_ref` / `stack_ref` / `operation_ref`のrevision束縛、許可action、保存則、postcondition、unknown menuのpre-mutation拒否
- 閉じた標準Potion同定、custom component除外、catalogのPotion / ingredient enumとpolicyの一致
- Minecraft 26.2の実`PotionBrewing.mix`に対する全allowlisted 1段recipe、container変換、breeze rodの回帰照合
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
- semantic / block mutationのuniversal safetyがwindow focus / mouse grabを参照せず、pause / Screen / Survival / health / threat / stationary / reconciliationを維持するcontract test
- evaluation-turnのacquire / normal release / Esc / UI OFF / world境界 / shutdown / runner終了 / stream切断 / deadline、推論中の物理入力隔離、cyan↔yellow外縁をclient testする
- player 2×2、crafting table、furnace familyの通常完了と各click後中断で、cursor / grid / input / fuel / output、絶対inventory差分、screen ownershipがterminal前に確定する
- 受入済みprofileのslot / widget / canvas操作が対象MOD本来のclient経路を通り、未知・shape不一致・非allowlistのMOD menuはAgent mutationとMCMCP生成custom packetを1件も送らず閉じる
- gameplay icon、推論中のcyan外縁、実行中の黄色外縁、全主要Screenへの省スペース状態button
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
- 9.7で追加したMOD profileごとに、baselineと完全一致するcloneで1つの成功例、各中断境界、未知version拒否を確認

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
14. 共通Menu interaction engine、4種のopaque ref、version固定profile、既存`craft_items`のhardeningと現行DSL接続
15. player 2×2、空のVanilla furnace family、対象profileの純storageと非slot GUI各1件
16. acquire_itemへのcraft / smelt合成と、実タスクで必要なworkstation / MOD profile
17. RedstoneSpec

前の段階で実測された問題だけを次の設計へ反映する。

## 17. 既知の制約と判断待ち

### 確定した制約

- client-onlyなので、サーバーの許可、領域保護、行動上限を強制できない
- server companionを後から足すロードマップは持たない
- 外部bridgeを前提にしない
- loopbackへ到達できないcloud-only MCP hostは利用できない
- 遮蔽と半径を守る観測と未知危険の完全回避は両立しない
- modded block、container、cropは現在の公開surfaceでは原則非対応。Phase 4以降も対象profileでversion固定し、受入済みMenu profileと必要最小限の固有adapterだけを追加する
- 移動は2 block以上を連続実行するpillaring、建築は同一Action内置換、Menuは上記backpack以外のMOD GUI profile、回路は可変長・曲がり・wire付きfan-out・任意回路合成が未対応

### 接続時に確認する運用条件

接続先MCP hostは既存Streamable HTTP endpointへ固定Authorization Bearer headerを付け、ローカルloopbackへ到達できること。製品基準はMCP 2026-07-28とする。実運用で固定するCodex CLI 0.146.1に限り、実capture済みの`initialize` / `2025-06-18`互換経路を併設する。それ以外のlegacy host向け汎用downgradeは行わない。

fresh MCP-only実験は製品runtimeと分け、script内定数へpinした`codex-cli 0.146.1 app-server --stdio --strict-config`のexperimental `dynamicTools`へ、MCP 2026-07-28 `tools/list`から得た固定5 Tool schemaだけを渡す。canonical catalogのfile/surface hashとlive resultをexact比較した後、評価runnerは`item/tool/call`をliteral `127.0.0.1` endpointへ1対1 forwardする。Bearerはrunner内だけでAuthorization headerへ使い、proxy/redirectを禁止してCodex childへ渡さない。この評価専用bridgeは、MOD内MCP serverだけで完結する製品要件を変更せず、永続MCP config未登録時にユーザーが許可したdirect fallbackとして、モデルへ余分なbuilt-in/MCP Toolを見せないための隔離hostである。

評価threadは毎回credential/config fileを持たないclean isolated `CODEX_HOME` / cwd、ephemeral、read-only、approval never、environmentなしで開始する。親runnerはcanonical `~/.codex/auth.json`からaccess token/account IDだけをメモリへ取り込み、JWT lifetimeをstartup/login/T0で検査して、artifactへ記録しない`account/login/start`から`cli_auth_credentials_store=ephemeral`へ注入する。元authは複製・hardlink・更新しない。production promptは厳格allowlistの`full-cycle`（全区画を耕す・播種する・全収穫と再播種を反復して小麦64個以上）または`short-regression`（短い依頼からの文脈推定）の一方を選び、`turn/start`のtext input 1件だけとする。主受入は`full-cycle`であり、shortの成功だけでcompletionを代替しない。MCMCP以外のshell、computer-use、browser/web、sub-agent、skill、app/plugin等をCLI featureとthread configの両方で無効化する。正当な`isError=true` domain resultはモデルへ保持して返し、transport/protocol/secret failureと区別する。詳細な固定値、T0、30分上限、両token scan、raw JSONL/bridge相互監査は`docs/experiments/MCMCP_fresh_MCP-only_評価protocol.md`を規範とする。

fresh評価はT0前に6.6のevaluation-turn leaseを獲得し、turn中の全forwardをそのleaseへ束縛する。別Windows Terminalの読み取り専用monitorは`Start-McmcpFreshEvalMonitor.ps1`から起動し、public commentary / preamble、completed reasoning summary、固定Tool / Action進行だけを表示する。Codexが公開した本文は座標等を含め意味的に加工せず転送し、raw private chain-of-thought、reasoning delta、raw Tool引数・結果はevent選択しない。実credential完全一致とTerminal制御文字だけを遮断する。画面へ表示したprefix除去後の安全な各行は、時刻・種別labelを含む同じ本文・同じ順序の`live-monitor.log`としてartifactへ保存する。このlogはMinecraft、MCP、runnerへの入力には使わず、既存trace / bridge auditによる`summary=detailed`、raw / summary deltaのopt-outと不在、monitor prefix / event allowlist / 制御文字guard、表示とlogの完全一致self-testで境界を証明する。runner / monitorは周期pollingせず、control stream、app-server JSONL、child process終了をevent-drivenに待ち、runner終了時は`-NoExit`なしのvisible childも終了する。

Codex CLIのversionを更新する場合は、先にlegacy wire handshake、app-server UNSTABLE APIのgenerated schema、external token login、dynamic Tool lifecycle、hardening config、固定5 Toolの呼出しを再取得し、compatibility contractとfresh MCP-only評価を更新する。再検証なしに別versionを合格扱いしない。

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
- [NeoForge 26.2 PlayLevelSoundEvent](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/PlayLevelSoundEvent.java)
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
- [Codex MCP configuration](https://developers.openai.com/codex/mcp/)
- [MCP Java SDK 2.0.x changelog](https://github.com/modelcontextprotocol/java-sdk/blob/main/CHANGELOG.md)
- [mcpfabric](https://github.com/Etoryx/mcpfabric)
- [mc_aiplayer](https://github.com/zoyluoblue/mc_aiplayer)
- [MCMCP: embedded MCP precedent](https://github.com/Mica-Technologies/MCMCP)
- [Existing CraftAgent repository](https://github.com/prskid1000/CraftAgent)
- [Existing Craftpilot repository](https://github.com/mmmfrieddough/craftpilot)
- [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)

### チャットから期待したコンテナへの画面遷移

NeoForge 26.2の画面切替は新画面のOpeningの後に旧画面のClosingを通知する。通常操作を許可する非pause ChatScreenから、正確なOpenScreen packetに対応したmenuへ切り替わる場合だけ、EXPECTING_FULL_CONTENTへ進んだ同じclient tick内の旧chatのClosingを1回許可する。所有権は同じsession・container ID・menu typeのfull-content packet確認後に限る。期待前・別tick・重複のchat閉鎖、所有済みコンテナの予期しない閉鎖、別menuのopenは引き続き停止する。
