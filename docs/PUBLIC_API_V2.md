# MCMCP公開API v2：改訂案

2026-09-27。利用者の追加フィードバックを反映した実装用の設計案。基準は`main`の`f7d4bf4`。この文書の新APIは実装途中または未実装であり、現在の公開MCP仕様とは区別する。

## 目的と責任

LLMは行き先、作業範囲、条件、反復の意図を伝える。MODは必要な局所観測、経路、道具選択、照準、ゲーム入力、server応答の確認を引き受ける。利用者が未観測の座標や範囲を指定してよいが、MODはその場所の隠れたworld状態を先読みしない。現在地から進み、読み込まれて見えるようになった部分を判断して続行する。

公開JSON Action DSLは、制限付きの小さなスクリプトと短い基本行動ツールへ**置換**する。移行中の内部再利用は許すが、両方式の公開維持を完成形にしない。旧`main`の`AGENTS.md`にあった固定5ツール、raw入力禁止、任意コード禁止は、このPRで新しい契約へ更新する。

## state・観測

| ツール | 既定で返す内容 | 明示要求／別ツール |
|---|---|---|
| `agent_get_state` | プレイヤーのdimension、位置・向き、体力・空腹・状態異常、手元のhotbar 9枠と選択枠。各枠のitem ID、個数、使用可否など公開可能な事実 | 全inventory、装備、レシピ、所持品集計はsection指定。session ID、MCP制御、最新frame/action IDは含めない |
| `agent_get_mcp_status`（新） | MCP control mode、game pause、READY期限（該当時）、world session ID、実行中action ID、最新frame ID、公開schema version | 詳しいpolicy／利用可能命令は`agent_get_state`の明示sectionで当面維持 |
| `agent_get_observation` | 引数なしで最新の観測を**一回で取得**。既定では近くのblock、entity、危険、通行候補を短く返す。要件ごとに範囲・種類・件数を指定可能 | 既知frameのページング、見えた面の詳しい証拠、音等は明示要求。毎回`get_state`でframe IDを取る必要をなくす |

観測は`visible_surface`ごとの長いrecordから、位置で束ねた`block` recordへ変える。見えた面は`faces:"UN"`のように1字コード（U/D/N/S/W/E）を並べるか、必要時に短い配列を返す。利用側が面の集合を解析する必要がない行動では表示自体を省き、MODは内部の実rayを保管する。座標のdimensionは応答共通部に置き、各recordに重複させない。tick/revisionは共通部へ寄せ、異なるものだけrecordに差分を残す。公開できない詳細stateは`null`のままにする。

`unknown_boundary`の行ごとの既定出力も、理由別の件数一覧も返さない。代わりに応答へ短い`complete:false`（探索範囲が全て判明したという意味ではない）と`truncated`／`next_cursor`だけを持たせる。**結果にない座標はunknownであり、airではない**。探索の方向を決める必要がある時だけ境界・遮蔽を要求できる。目視したairや通行可能cellを求める要求は、その事実を明示して返す。

## 行動ツールを一組として公開

次の公開面を一組で設計・公開する。説明量が重くなる場合は`agent_do({kind:...})`型の統合を比較できるが、初期案はLLMが見つけやすい名前付きツールを同時に公開する。各ツールのSchemaは短くし、詳細な使い方は必要時だけ取得する。

| ツール | 主な指定 | MODが行うこと |
|---|---|---|
| `agent_move` | 座標、方向＋距離、または「対象に届く」「範囲内へ」などの到達条件。必要なら経路中の破壊・設置を許可する条件 | 段階的な探索・移動、障害の局所観測、許可された範囲内で必要な破壊・設置、到達確認 |
| `agent_break_block` | 単一座標／直方体範囲、任意のblock条件・除外条件・最大個数 | 近づく、見えるようになったblockを確認する、適切な道具を探して選択する、通常操作で破壊する、結果を確認する |
| `agent_place_block` | 単一座標／範囲、block/item、任意の望むstate・配置条件 | 手持ち確保、支持面・方向・接続の検討、移動・設置・照合。state未指定なら既定の設置状態を受け入れる |
| `agent_interact` | block／entity／itemと目的。座標／範囲や種類の条件は任意 | 対象を見つける、接近、通常使用、画面やstateの変化を確認する |
| `agent_inventory` | `inspect`／`transfer`／`drop`、item条件、数量、移動元・先 | hotbar／inventory／対応menuの同期、移送・投棄、数量の確認 |
| `agent_click` | 左／右／中、対象／現在照準、回数、画面文脈 | 必要な入力と効果の確認。中クリックはゲームモード別に意味を扱う |
| `agent_input_sequence` | 同時・順次のsteps、押下tick、間隔、反復数、停止条件 | 複数入力の単一所有、有限実行と解放 |
| `agent_run_script` | 下記の制限言語のsourceと総予算 | 関数・変数・分岐・反復で複数の基本行動を組み立てて進める |

これらの一呼出しは共通の`action_id`を返す。短い処理は同じ応答へ完了結果を載せられる。長い処理は`running`と`action_id`を返す。`agent_get_action({action_id})`は**その一件**の進行、成功／失敗、確定した変更、途中までの結果を読む。`agent_cancel_action({action_id})`は**その一件**の停止を要求する。たとえば`agent_move`が坑道を掘り進めている間も、同じIDで進行を見て中止できる。中止は既に壊したblockを戻さない。名前は既存互換のため残し、v2では共通の「作業結果・中止」ツールとして説明する。

内部の`AgentJobStore`はDSLから独立した共通job状態を保持する。HTTP応答の配送確認前は開始できず、world session違い・取消・操作回数上限で次の操作を拒否する。取消と既に発行した操作の結果が競合しても進行数を記録し、入力解放が確認されるまでterminal結果を公表しない。保持する結果は最新jobと直前の完了jobに限る。内部のinput sequenceと座標moveはruntimeのclient tick、配送確認、進行取得、取消へ接続済み。**公開toolには未接続で、ゲームでの入力所有・lease解放は未検証**。他の行動種別、部分結果の詳細は接続時に実装・試験する。

move＋breakを利用側で毎ブロック交互に呼ぶ必要はない。`agent_move`に`clear_path`を指定する、または`agent_break_block`に`advance:true`を指定して坑道を連続施工できる。範囲内で新たに露出したblockはMODが局所的に観測し、液体、落下、危険な敵、inventory満杯等で停止・報告する。既存PR #26の坑道専用DSLは参考にするが、公開上の専用命令増殖は避ける。

破壊の`expected_drop`や完全な`expected_state`は必須にしない。道具は採掘可能性、速度、耐久、Silk Touch/Fortune等の条件を見て選び、適切なものがなければ他の道具または素手へfallbackできる。ただし指定した成果物が得られない可能性は結果に表す。位置、上限、block条件、除外条件を優先して、誤って別blockまで壊さない。設置ではitemと座標を基本にし、向き等のstate指定は任意にする。

## 制限付きスクリプト

公開言語はJavaScript風の小さな同期言語。turtle／p5.jsのように`move(...)`、`breakBlocks(...)`、`place(...)`、`interact(...)`、`input(...)`を順に書ける。行動関数はオブジェクト引数ではなく`move(x=100, y=64, z=120)`のような名前付き引数を受ける。変数、数値・文字列・真偽値・配列・オブジェクト、`if`、回数上限付き`for`／`repeat`、利用者定義の小関数、比較を当面の対象とする。`async/await`、Promise、Node.js、module読込み、Javaへのアクセス、ネットワーク、ファイルI/Oは言語仕様に入れない。完全なECMAScriptを走らせる必要はない。

実装済みの構文例（まだ公開toolからは実行できない）:

```js
function advance(x, count) {
    repeat(count) { move(x=x, y=64, z=120); x=x+1; }
}
advance(count=2, x=119);
move(x=116, y=64, z=120, clearPath=true, maxBreaks=32);
let count = 3;
for (let i=0; i<count; i++) {
    if (i < 2) { move(x=116+i, y=64, z=120); }
    else { repeat(2) { interact(target="lever"); } }
}
```

各行動関数は、完了または明示的な失敗まで内部で待ってから次の文へ進む。ゲームthreadを塞がず、script実行器は別の作業threadで動き、ゲーム操作を既存の入力所有・安全停止へ渡す。source長、実行時間、演算数、メモリ、ゲーム操作回数、観測量、反復回数を有界にする。停止後の入力解放と途中結果の回収は一つのjobとして保証する。

### このブランチで実装済みの内部言語処理

`agent/script/ActionScript`と`ActionScriptParser`はpackage-privateのpure Java parser/interpreter。JS engineや外部依存を使わない。全文をparseし、構文、注入された命令名のallowlist、利用者関数の引数と呼出しgraphを検証してから、immutableな名前付き引数を同期sinkへ順に渡す。未使用関数・未実行branchの不正構文・未知の呼出しも実行前に拒否する。変数解決と値の型は評価時に検査し、途中失敗で既に完了した命令は取り消さない。

- 値: 有限のdouble数値（指数表記可）、引用符付き文字列、boolean、null、配列・object literal。配列/objectはimmutableなデータで、property/indexアクセス・mutation・method呼出しはない。objectの値を名前付き引数に渡せるが、`move({x:100})`や位置引数は不可。
- 文: `let name=expression;`、既存のlocal変数への代入、`if (boolean) {...} else {...}`、`repeat(count) {...}`、`for (let i=initial; boolean; i++) {...}`。forの更新は`i=expression`も可、初期化したcounterだけを更新する。blockごとにlocal scopeを作り、外側変数の参照・更新とshadowingを許す。文末`;`と制御文の`{}`は必須。
- 式: `+ - * / %`、`< <= > >= == !=`、`! && ||`、括弧。通常の演算優先順位とboolean短絡評価。算術は数値だけ、条件はbooleanだけで、暗黙変換・文字列連結はない。非有限値は失敗。等値比較はscalarだけで、共有collection graphを再帰比較・展開しない。
- 文字列escapeは`\n`、`\r`、`\t`、`\\`、`\'`、`\"`。identifierはASCII英字・`_`で始まり、以降は数字も可。コメント、再帰、while、async/await、Promise、module、hostアクセス等は未対応または禁止。

- 小関数: トップレベルの`function name(a, b) { 文... }`と文としての`name(b=2, a=1);`。定義より前の呼出しと関数同士の合成を許す。全引数は必須で、順序は自由、既定値・位置引数・戻り値・式内呼出しはない。関数名・parameter・呼出し引数の重複、引数の不足・余分、直接／間接再帰は全文検証で拒否する。関数名・parameter・let（for counterを含む）で注入命令名をshadowできない。各呼出しは引数だけを持つ独立scopeを作り、呼出元・トップレベル変数を参照・更新できない。body内のblock scope規則は通常の文と同じ。関数は値ではなく、ネストした定義・closureはない。

上限は次の固定値。実行予算だけ呼出側で0以上の小さい値に減らせる。

| 対象 | 最大値・数え方 |
|---|---|
| 関数深さ | helper 16段。未使用関数を含む呼出しgraphの最長経路を実行前に検査し、実行時にも確認。循環はINVALID、深さ超過はLIMIT |
| source | UTF-16 code unit 32,768個（空白も含む） |
| AST | root/block/argument/property/function/parameterを含む4,096 node。未使用関数も対象。rootを含む木の深さ64。parserのstatement/expression同時入場も64 |
| work | 評価するnodeへの入場100,000回。root/block、関数定義文、helper呼出しと毎回のbodyも数え、argument/property wrapperはその値の評価だけ数える。上限到達後の次の評価を拒否 |
| 反復 | 全loop合計10,000回。bodyへ入る直前に加算。repeatの回数式は一度だけ評価し、非負整数かつ残反復予算以内でなければ開始前に拒否。forは条件を毎回評価し、trueでも残予算0なら停止 |
| 命令 | sink呼出し1,000回。呼出し直前に加算し、失敗した呼出しも含む。完了成功数は別に保持 |

loop・関数呼出しを事前展開せず、ASTを直接評価する。関数内のloopもwork・反復・sink呼出しの全体予算を共有し、関数呼出しごとにリセットしない。helper自体はsink呼出数・成功数に加算せず、workで制限する。文字列はsource由来で増殖演算を持たず、collection slotや変数への格納回数もwork上限以内。collection参照を深くcopy/serializeしない。注入sink側でも共有・深いcollectionを無制限に展開せず、命令引数のschema（不足・余分を含む）・大きさを検証する必要がある。

結果は`SUCCESS / INVALID / LIMIT / CANCELLED / FAILED`、消費work・反復数・呼出数・成功数を返す。sinkの失敗・例外・取消で直ちに停止し、再送しない。取消signalとthread interruptionをparseのtoken境界、AST・呼出しgraph検証、評価node、loop、sink前後で確認する。sinkにも取消signalを渡す。同期sink自体を強制中断したり、wall-clock deadlineを保証する仕組みはこの増分にはなく、sinkが待機中も取消と有限期限に協調することが前提。

**未実装:** 行動handler、worker/job接続、ゲーム入力の所有・解放、server照合、経過時間・観測量予算、実機試験。今回のsinkは注入interfaceと試験用実装だけで、ゲームを操作しない。`agent_run_script`はcatalog/runtimeへ公開していない。基本行動handlerと停止契約を接続・検証し、基本tool一式と同時公開するまで内部prototypeに留める。現行の公開catalog/schemaをこの内部実装のために変更しない。

## このブランチで実装済みの内部座標ナビゲーション

`agent/navigation/CoordinateGoalPlanner`はpure Java部品。1つのworld session・dimension・座標目標に対してjob全体で同じinstanceを保持する。入力はimmutableな`KnownTraversabilitySnapshot`、現在地、呼出側が確認したsession/revision、予算と取消signalだけで、world読取り・入力操作は行わない。目標は未観測・遠方でも指定できる。

snapshotの既知の安全な停止候補を目標への直線距離、同距離なら`NavCell`順に評価し、既存`DeterministicAStar`で到達できる最初の候補を返す。unknownやSTALE/BLOCKEDのedgeは通らず、斜めの角・現在revisionの直接証拠、距離・実行時間の予約は既存A*／`RoutePlan`に従う。既存の`PROBE_ALLOWED`は支持・clearance等が確認された有限probeとして維持し、未観測cellへ踏み出す許可にはしない。影響を受けず保持された古いedgeは既存mapの無効化契約に従って利用する。

候補をA*に渡す前に、現在地から確認済みedgeでつながるcellだけへ絞る。観測済みでも分断された地形が64件の候補枠を使い切り、近くの進める道を見落とすことを防ぐ。この事前判定は経路の承認ではなく、A*が距離・斜めの証拠・予算を改めて検査する。

- `REACHED_KNOWN_GOAL`: 入力の現在地が既知の目標と一致（移動edgeなし）。`KNOWN_GOAL_ROUTE`: 既知目標までの計画で、実移動の成功ではない。`PARTIAL_WAYPOINT`: 既知中間点までの計画で、到達後に新しい観測が必要。
- `BLOCKED / LIMIT / CANCELLED / STALE_MAP / WORLD_MISMATCH`: 経路なし。現在session/revisionとの不一致・受理済みrevisionからの巻戻り・異world/future edgeを拒否する。実行直前・各tickの再検証は呼出側に必要。
- 1回のplanの固定上限はsnapshot edge 4,096件、候補A*呼出し64回、A*全呼出し合計2,048展開。各予算は0まで縮小可能。edge上限超過は切り捨てずLIMIT。取消・thread interruptionは列挙・探索・結果確定前で確認する。
- 既知の安全な経路では、目標から一時的に遠ざかる中間点も選べる。発行した経路上のcellは同じjobで再び中間点にしない。次の中間点には前回発行後の新しいmap証拠を要求し、同じ証拠での巡回・実行失敗後の即時再発行を防ぐ。既知cell履歴は8,192件で上限停止する。未知cellへの移動、網羅的frontier探索、失敗した経路の自動再試行は未対応。

内部の`StartMove`は座標、到達許容幅（0.1～0.49）、最大実行tick（1,200）、総移動距離（256 block）を受ける。`CoordinateMoveJobExecution`が既知区間を既存の移動executorへ渡し、中間点到着後に新しい観測を待って次を計画する。部分経路の再計画要求も新証拠を待つ。目標への最終経路が途中で無効になれば、その経路を自動再発行せず失敗で停止する。観測が進まない場合も有界に停止する。runtimeは配送確認、world・player・control epoch、体力、screenと局所安全、移動距離、各tickの経路証拠を確認し、取消・危険・緊急停止・world境界で入力を解放してから終端を返す。座標目標が未観測でも受理するが、未知地形へ踏み出すわけではない。

**未完了:** 方向＋距離や到達条件、障害の自動破壊・設置、scriptからの利用、公開`agent_move`、ゲームでの到達・停止試験。現段階で公開catalog/schemaは変更しない。基本tool一式の公開は接続・検証後に行う。

## このブランチで実装済みの内部ブロック作業基盤

`BlockWorkRegion`は破壊・設置で共用する座標範囲を保持する。`x/y/z`は始点、`dx/dy/dz`は符号付きの**終点差分**で、両端を含む。差分0は単一座標。X、Z、Yの順で安定して列挙し、最大4,096 cellと整数overflowを検査する。将来の楕円・path形状は現時点の契約に含めない。

内部の`V2PlaceArguments`は同じ範囲、必須のVanilla `block`、省略可能な`item`・`properties`・`replace_blocks`と作業上限を受理する。`item`省略時はblock IDと同じitemを選ぶ。`properties`省略時は設置結果の既定stateを受け入れる。`replace_blocks`省略時はair系blockだけを対象とし、他のblockへの置換は明示条件が必要。破壊と設置は`V2BlockJobExecution`の配送確認・範囲進行・取消・入力解放後の終端確定を共用し、進行結果では`broken_blocks`と`placed_blocks`を別名で返す。

内部の`StartPlaceBlock`はruntimeのclient tickへ接続した。現時点の`MinecraftV2PlaceDriver`は、手の届く単一cellについて、局所観測済みの支持面を選び、hotbarの通常BlockItemを使って設置し、予測sequenceとサーバーACK・設置後stateを照合する。支持面との不用意なinteractを避けるため短期leaseでかがみ、実crosshair・置き先・予測stateを使用直前に再確認する。`advance:true`の場合、未観測・遠方の置き先へは既知の安全な区間を移動して再観測する。置き先のcellは立ち位置にも経路にも使わない。既知経路が増えなければ有界に停止し、未知cellへは踏み出さない。ベッド・扉など複数cellを作るitemは、派生cellの所有・照合が未実装のため現時点では拒否する。範囲は共通jobが順に処理するが、main inventoryからhotbarへの資材移動、特殊item、全Vanilla状態の実機確認は未実装。公開catalogには載せていない。

内部の`V2BreakArguments`は未観測座標でも受け、任意の`include_blocks`／`exclude_blocks`、最大破壊数・実行tick・移動距離、`advance`を保持する。条件は新たに観測したblockへ適用する想定で、これらの引数を受理する公開ツールはまだない。`V2BreakSourcePolicy`はv1の固定許可リストから独立し、明示対象の登録済みVanilla非air blockを扱う。既存の攻撃leaseと予測確認ポートはv1ポリシーを既定として残し、v2専用インスタンスだけ新ポリシーを使える。`KnownBlockBreakAttempt`はv2向けに、期待dropなしでもサーバーACKと権威あるairへの遷移で成功を確定できる。局所rayの照準選択は、MCPへの観測配送を必須とせず、現在の視点・reach・revision・観測時刻に合う内部frameだけを使う。

内部の`StartBreakBlock`は配送確認後に共通jobとして動き、範囲を順に調べる。`advance:true`なら対象がまだ見えない／届かない場合、既知の安全な経路で中間点まで接近し、新しい局所観測を待ってから次区間を検討する。破壊対象のcellは立ち位置にも経路にも使わない。未知cellへは踏み出さず、既知の経路が増えなければ有界に停止する。手の届く指定cellが空気の場合は、現在の視点からの視覚・衝突・液体rayが共に遮られず、経路上のcellがロード済みのときだけ読み飛ばす。局所rayで確認できた非air対象についてhotbarからドロップ採取可能な道具を優先し、同条件では採掘速度で選ぶ。適切な道具がなければ他の道具・空手も候補に残す。照準を合わせて通常の攻撃入力を出し、v2専用の最大600tickの攻撃lease内でサーバーACKとair遷移を確認して次の対象へ進む。v1の40tick上限は維持する。走査済みcell数と確定破壊数は取消・終了後も直前のjobまで進行結果に残す。取消・危険・world変更では入力を解放してから終了状態を確定する。公開catalogからはまだ呼び出せない。

**未完了:** `agent_move`からの経路障害物の自動破壊、遠方の空気・液体を含む範囲への到達、エンチャントや耐久条件を含む道具選択、600tickでも壊せないブロックの扱い、ブロックごとの効果記録、ゲームでの成功・危険停止試験。現段階の`advance`は安全な既知区間を歩ける場合に限り、planner自身は障害物を壊さない。v1の固定許可リストは現行公開経路に限り保持し、v2の完成形へ引き継がない。

## raw入力

ゲーム内の論理キー／左右中クリックを指定する。`steps`に複数の同時入力、順番、`hold_ticks`、`gap_ticks`、`repeat`、`until`、最大総時間を記載できるようにする。入力頻度はgame tick単位で表す。world操作を起こしうるクリックには座標範囲・対象種類・手持ちなどの任意guardを付けられ、guardなしでも総時間と入力回数の上限を保つ。画面やワールドが変われば入力を解放して停止する。OSへキーを送る機能とは区別する。

内部の`FiniteInputSequence`は、既存の8種類の論理入力について、同時押し・順次step・押下tick・gap・有限反復と外部停止条件／取消をtickごとに進める。最大64 step・合計1,200 tickで、停止後は空入力だけを返す。`InputSequenceLeaseDriver`は既存の短期leaseを使い、入力集合の切替前・停止時に旧入力を解放し、期限切れ・解放失敗なら次の入力を出さず停止する。`InputSequenceJobExecution`はこのdriverを共通jobへ接続し、配送確認・session・安全判定を満たしたtickだけ入力を発行する。取消・world変更・危険・lease失敗では停止意図を保持し、入力解放の再試行が成功するまでjobを非terminalに保つ。内部の`StartInputSequence`からclient tick・緊急停止・既存の配送確認／進行取得／取消へ接続した。入力はworld/session、画面、局所安全、体力、移動距離、control epochを毎tick確認し、移動には既存の衝突・revision証拠を要求する。**公開catalogにはまだ載せておらず、ゲームでの動作も未検証**。中クリック、任意キー、停止条件のgame評価、server結果照合、entity attackの適切な許可境界は未実装である。基本ツール一式と合わせて公開する前にこれらを解決する。

## 実施順・確認

1. `AGENTS.md`と規範仕様を新しい責任境界に短く改める。専用worktree／Issueで実装する。
2. 観測の小型化と`agent_get_mcp_status`、手元9枠、引数なしのatomic observationを実装する。
3. 基本行動ツールを一組で公開する。まず既存の内部実行器を再利用し、目標は公開DSLの撤去と単一のjob実行基盤に収束させること。
4. 範囲指定の破壊・設置、経路中の破壊・移動、道具fallbackを隔離fixtureで試験する。
5. 制限付きスクリプトと入力sequenceを接続し、有限ループ・取消・部分成功・入力解放を試験する。
6. 必要な箇所を許可済みの隔離Dockerで軽く実機確認する。従来方式の長時間の成功率計測は着手条件にしない。公開後の比較は補助として行う。main統合・Releaseは別指示まで行わない。

## 参照

- 現行契約：`main/AGENTS.md`、`main/docs/MCMCP_MCP_Tool_Catalog.json`
- [Baritoneの経路探索・長距離区間の説明](https://github.com/cabaletta/baritone/blob/1.21.4/FEATURES.md)
- [GraalVMのJavaScript組込みと依存条件](https://www.graalvm.org/jdk25/reference-manual/embed-languages/)
