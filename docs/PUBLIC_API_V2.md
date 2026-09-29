# MCMCP公開API v2：改訂案

今回の優先機能と試用手順は[試用メモ](PUBLIC_API_V2_TRIAL_20260929.md)を参照。実装状況・未確認事項は[進捗と再開メモ](PUBLIC_API_V2_STATUS_20260928.md)、実機結果は[基本smoke](experiments/20260928_public_api_v2_local.md)と[所持収納・使用中停止](experiments/20260929_v2_storage_stop_local.md)を参照。この設計案の完成目標、実装済み、実機確認済みの範囲を区別する。

2026-09-27。利用者の追加フィードバックを反映した実装用の設計案。基準は`main`の`f7d4bf4`。2026-09-28のDraft PRでは基本行動8ツールを一組で公開catalogへ接続した。旧JSON Action DSLの開始ツールは公開catalog・受付から削除し、13ツールになった。旧DSLの入力schemaは内部実行器の回帰試験用resourceにだけ残し、本体JARには同梱しない。実ゲーム確認と不足する行動範囲が残るため、v2完成・配布済みとは扱わない。

実ゲームの主要成功・取消・危険停止は[隔離smoke test](../tools/eval/fixtures/public-api-v2/README.md)で検証する。runner／fixtureの単体検査と実ゲーム合格を区別し、対象JAR・action ID・readback・復旧を実験記録へ残す。

## 長時間・多回数実行の追加（2026-09-29）

明示した時間・反復・script予算の上限を全行動で見直した。[上限一覧・使用例](PUBLIC_API_V2_LONG_EXECUTION_20260929.md)を参照。共通jobと入力消費側で単調な実時間期限を検査し、子jobは親scriptの総期限を共有する。既定値、短いlease、局所安全・確認待ちと解放後のterminal公開は維持する。秒指定inputは全論理入力で使用でき、材料を明示したuseだけに静止・補充待ちguardを追加した。短い隔離入力試験A–Hを確認済み。長い移動・施工・scriptの最大予算の実機運転、通常profileでの運用、24時間連続実測は残る。

## 目的と責任

LLMは行き先、作業範囲、条件、反復の意図を伝える。MODは必要な局所観測、経路、道具選択、照準、ゲーム入力、server応答の確認を引き受ける。利用者が未観測の座標や範囲を指定してよいが、MODはその場所の隠れたworld状態を先読みしない。現在地から進み、読み込まれて見えるようになった部分を判断して続行する。

公開JSON Action DSLは、制限付きの小さなスクリプトと短い基本行動ツールへ**置換**する。移行中の内部再利用は許すが、両方式の公開維持を完成形にしない。旧`main`の`AGENTS.md`にあった固定5ツール、raw入力禁止、任意コード禁止は、このPRで新しい契約へ更新する。

## state・観測

| ツール | 既定で返す内容 | 明示要求／別ツール |
|---|---|---|
| `agent_get_state` | プレイヤーのdimension、位置・向き、体力・空腹・状態異常、手元のhotbar 9枠と選択枠。各枠のitem ID、個数、使用可否など公開可能な事実 | 全inventory、装備、レシピ、所持品集計はsection指定。session ID、MCP制御、最新frame/action IDは含めない |
| `agent_get_mcp_status`（新） | MCP control mode、game pause、READY期限（該当時）、world session ID、実行中action ID、最新frame ID、公開schema version | 詳しいpolicy／利用可能命令は`agent_get_state`の明示sectionで当面維持 |
| `agent_get_observation` | 引数なしで最新の観測を**一回で取得**。既定では近くのblock、entity、危険を短く返し、entityの速度・当たり判定と個別の観測証拠は明示要求にする。通行エッジはMODが内部で経路判断に使い、明示要求時だけ公開する。要件ごとに範囲・種類・件数を指定可能 | 既知frameのページング、見えた面の詳しい証拠、音等は明示要求。毎回`get_state`でframe IDを取る必要をなくす |

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
| `agent_inventory` | `inspect`／`swap`／`transfer`／`drop`、item条件、数量、移動元・先 | hotbar／inventory／対応menuの同期、移送・投棄、数量の確認 |
| `agent_click` | 左／右／中、対象／現在照準、回数、画面文脈 | 必要な入力と効果の確認。中クリックはゲームモード別に意味を扱う |
| `agent_input_sequence` | 同時・順次のsteps、押下tick、間隔、反復数、停止条件 | 複数入力の単一所有、有限実行と解放 |
| `agent_run_script` | 下記の制限言語のsourceと総予算 | 関数・変数・分岐・反復で複数の基本行動を組み立てて進める |

Draft PRで公開した範囲は、moveの座標／方向、Vanillaのbreak/place、block state変更・手持ちitem使用・観測したentityへの通常使用のinteract、プレイヤーinventoryのinspect/swap/drop、座標指定のVanilla収納と対応providerの所持収納のinspect/transfer、world内の有限click、9種類の論理入力、制限付きscriptである。swapはmain inventoryの1 stackとhotbarの指定枠を交換し、両slotのserver更新を確認する。moveの明示許可による局所障害物処理、遠方entityへの接近、4種類の停止条件、複数cell設置、menuの開封・照合と限定収納のShiftクリック、PC側施工checkpointを追加した。任意MOD収納や任意GUI操作は未対応。v2の`get_action`は進行数を既定で返す。`include_result:true`を指定すると、block作業では確定セル座標と確認済みの変更前後block stateを直近最大128件、総数・保持開始番号・打切り有無とともに返す。block破壊のdrop・消費item等を含む詳細effectは未実装。実ゲームでの成功・取消・危険停止を確認するまでDraftを維持する。

これらの一呼出しは共通の`action_id`を返す。現在の開始応答は`queued`と`action_id`を返す。完了までは同じIDを照会する。`agent_get_action({action_id})`は**その一件**の進行、成功／失敗を短く返す。確定した変更と途中までの結果は`include_result:true`で明示要求する。`agent_cancel_action({action_id})`は**その一件**の停止を要求する。たとえば範囲指定の`agent_break_block(advance:true)`が坑道を掘り進めている間も、同じIDで進行を見て中止できる。中止は既に壊したblockを戻さない。名前は既存互換のため残し、v2では共通の「作業結果・中止」ツールとして説明する。

内部の`AgentJobStore`はDSLから独立した共通job状態を保持する。HTTP応答の配送確認前は開始できず、world session違い・取消・操作回数上限で次の操作を拒否する。取消と既に発行した操作の結果が競合しても進行数を記録し、入力解放が確認されるまでterminal結果を公表しない。保持する結果は最新jobと直前の完了jobに限る。内部のinput sequenceと座標moveはruntimeのclient tick、配送確認、進行取得、取消へ接続済み。公開toolへ接続済み。基本入力の取消・危険停止と、shield使用中の取消・Esc・UI OFF・次元移動で入力所有終了を実機確認した。部分結果の詳細は引き続き実装・試験する。

`AgentJobStore`はjob固有の小さな`result`もterminal後と直前jobまで保持する。`agent_inventory({operation:"inspect"})`は配送確認後のclient tickでプレイヤーの全所持枠を読み、非空slotの番号・item ID・個数、空枠数、選択中のhotbar枠を返す。任意のitem IDで絞り込め、MOD itemも同じ形式で扱う。`operation:"swap"`はitem ID、main inventoryのsource slot（9～35）、hotbar slot（0～8）を指定し、両slotのserver payloadで交換を確認する。hotbar枠が空でなくても交換するため、数量指定の移送とは区別する。`operation:"drop"`はitem IDと1～64個の数量を取り、同じIDのstackが複数あればslot番号で特定させる。main inventoryのstackは一度だけ選択中のhotbar枠へSWAPし、両slotのサーバー更新を確認してから投棄する。所持品menuの通常THROWをclient予測なしで送り、各入力の後は選択枠のサーバーpayloadとローカル所持品が要求どおり変化するまで次を送らない。不確実な送信・応答切れでは再送せず、確定数と未確定数を結果に残す。SWAP後の配置は元に戻さず、結果でその可能性を示す。これらのプレイヤー所持品操作はmenuを開かない。

収納の確認は`agent_inventory({operation:"inspect",target:"container",x:4,y:65,z:8})`、取り出し／収納は`{operation:"transfer",target:"container",x:4,y:65,z:8,direction:"take",item:"minecraft:stone",count:13}`（収納は`direction:"store"`）で行う。`block`条件とinspectの`item`絞込みは任意。未観測座標も受け付け、既定の`advance:true`で局所観測した経路を通り、操作距離内の見える面から開ける。総移動距離は既定64 block・最大4,096、総時間は既定1,200・最大1,728,000 tick。実時間の共通期限も適用する。`advance:false`で移動を禁止できる。現段階ではチェスト、樽、8種の銅チェストに対応する。内部の所有menu・slot確認処理を共有し、transferは1～896個の指定数を移し、再開封して照合する。取消・失敗時も`confirmed_count`、`unconfirmed`、確認済み／不確実な数量変化を残し、menuと入力を解放するまでterminalにしない。inspectは確認済みの内容をitem ID別に集約し、絞込み指定時だけ該当IDを返す。公開catalogとscriptの`inventory(...)`へ接続済み。チェストのinspect／take3／store2は実機smokeで確認したが、MOD収納の汎用接続、他の収納種類・数量・取消の実機確認は残る。

収納transferは現在、既存処理が検証できる通常item・slotに限り、最大14個のsource stackと14回のPICKUP入力で事前計画できる数量を扱う。`count`の上限内でもこの条件や容量に収まらない場合は入力前に拒否する。この制限を任意MOD収納への汎用対応が完了したものとは扱わない。

所持する収納itemは`agent_inventory({operation:"inspect",target:"storage",storage_slot:2})`で確認する。数量移送は同じtargetへ`operation:"transfer",direction:"take"|"store",item:"minecraft:stone",count:3`を指定する。`storage_slot`はプレイヤー所持枠番号（0～40）であり、任意の`storage_item`で収納itemの種類も照合できる。開封参照・profile hash・DSLは利用側へ要求しない。MOD側でその枠の収納を一度だけ選び、providerによる個体照合と通常開封、server同期済みslotの数量移送、再開封の照合へ接続する。inspect結果の`slots`番号は収納menu内の番号であり、プレイヤーの`storage_slot`とは区別する。itemでの絞込みは開封して全内容を確認した後に行う。

この所持収納経路はPR #82の共通実行部分（`99d5ada`）を再利用する。`StorageAccess`は発見・開封・個体確認、`KnownMenuProfileSupport`は同期方式・slotの役割と容量を担当し、PICKUP計画・数量確認・部分結果・終了処理は共有する。組込みproviderは現在Sophisticated Backpacks 3.25.90／Core 1.4.99の検証済みJARに限る。他MODにはprovider／同期profileの追加が必要であり、未知の仕様を同じものと仮定しない。取消時に送信済みPICKUPがある場合はその応答を待ち、確認済みcursorの残数を元のslotへ一度だけ戻す。その入力と空cursorがserverで確認できるまで閉じず、不確実な入力を再送しない。移送中の一部が未確定の場合は結果に残す。初期化済みのhotbar slot 2のbackpackで、inspect・store3・take2と再開封後の数量一致をv2実機で確認した。新品は通常UIで一度開いて個体IDを初期化する必要があり、自動初期化は未対応。移送の応答待ち取消・遅延同期、他の所持枠・upgrade付き収納は追加検証が残る。

move＋breakを利用側で毎ブロック交互に呼ぶ必要はない。現時点では`agent_break_block`の`advance:true`で接近しながら採掘できる。`agent_move`の`clear_path:true`（既定false）で水平移動の局所障害物処理を有効にできる。進めない場合だけ、目標へ近づく方向の見える安全な空間越しに手の届くVanilla障害物を通常破壊する。液体・落下block・TNT・block entity・複数cell構造は除外する。`bridge_block`を併記した場合だけ、所持する通常のfull blockで不足する足場を補う。最大64変更で、server確認と新しい経路証拠を待ち、観測待ちも`max_ticks`に含める。範囲内で新たに露出したblockはMODが局所的に観測し、液体、落下、危険な敵、inventory満杯等で停止・報告する。既存PR #26の坑道専用DSLは参考にするが、公開上の専用命令増殖は避ける。

破壊の`expected_drop`や完全な`expected_state`は必須にしない。道具は採掘可能性、速度、耐久、Silk Touch/Fortune等の条件を見て選び、適切なものがなければ他の道具または素手へfallbackできる。ただし指定した成果物が得られない可能性は結果に表す。位置、上限、block条件、除外条件を優先して、誤って別blockまで壊さない。設置ではitemと座標を基本にし、向き等のstate指定は任意にする。

`agent_interact({target:"item"})`は選択中の手持ちitemを一度使用する。`item`でVanilla／MODのitem IDを指定すると所持品から選び、main inventoryにしかない場合は選択hotbar枠へ一度SWAPしてserver確認後に使う。`hold_ticks`は既定40・最大1,727,999、`max_ticks`は既定min(1,728,000, max(200, hold_ticks+80))・最大1,728,000。max_ticksはhold_ticksより大きく指定する。通常のuseItemと有限のUSE入力を使用し、終了・取消で長押しを解放し、元の選択枠へ戻す。SWAPした所持品配置そのものは戻さず、結果に記録する。単発使用では、使い終わったitemを自動で再使用しない。取消も通常の使用キー解放を行うため、弓など解放時に発動するitemの効果を巻き戻すものではない。

item使用の既定完了は、クライアント側で使用を受け付けたこと（または確認済み所持品変化）、長押し終了、該当prediction sequenceのserver ACKを条件とする。これはitem固有のworld効果の保証ではない。結果の`server_processed`と、selected slotの新しいserver payloadから確認できた`effect_confirmed`を分ける。`result_item`を任意指定した場合は、そのitem IDになった新しいserver payloadとローカル手持ちの一致も待つ。確認できないまま期限に達した操作を再送しない。item使用でmenuが開く場合は画面境界で停止する。座標menuを操作する場合は専用の`target:"menu"`を使う。長押し保持hookはmilkの飲用完了（server ACKとbucketへの更新）で実機確認した。shield使用中のAPI取消・Esc・UI OFF・次元移動と、手動再許可後の新規使用も実機確認した。他item固有の効果や全停止経路の網羅は残る。

`agent_interact({target:"entity",entity_ref:"観測の参照",item:"minecraft:bucket",result_item:"minecraft:milk_bucket"})`は観測済みentityへ既知の安全経路で接近し、reach内で照準を合わせ、MAIN_HANDで通常のentity操作を一度だけ行う。`advance`は既定true、falseで接近禁止。`max_distance`は既定64・最大4,096、`max_ticks`は既定1,200・最大1,728,000。`entity_type`は任意の種類条件。`item`省略時は現在の手持ち、`minecraft:air`なら空枠を選ぶ。itemと同じ持ち替え／server確認処理を共有し、main inventoryからの交換後は配置を元に戻さない。entityの種類やitemにVanilla専用allowlistを設けず、登録MODの通常操作へ渡す。別entityへの置換、遮蔽、接近不能、使用直前のreach外、画面・session変更では停止する。

entity packetにはitem使用のprediction ACKがないため、`result_item`省略時の成功はclientの操作受付を条件とする。PASS後の無関係な拾得など、手持ちstackの変化だけでは成功にしない。結果の`confirmation`は`client_dispatch`／`server_held_item`を区別し、`effect_confirmed`は確認済み手持ちstackの変化だけを示す。繁殖・騎乗・entity状態変化など固有の効果を保証しない。`result_item`指定時は新しいserver payloadと現在の手持ちの一致を必須にする。clientがPASSを返すMODでも、このpostconditionが確認できれば完了できる。古いpayloadやclient予測だけでは完了せず、期限までに確認できなければ再送せず停止する。単体・公開schema検査と実ゲームのentity操作確認は分けて扱う。

## 制限付きスクリプト

公開言語はJavaScript風の小さな同期言語。turtle／p5.jsのように`move(...)`、`breakBlocks(...)`、`place(...)`、`interact(...)`、`input(...)`を順に書ける。行動関数はオブジェクト引数ではなく`move(x=100, y=64, z=120)`のような名前付き引数を受ける。変数、数値・文字列・真偽値・配列・オブジェクト、`if`、回数上限付き`for`／`repeat`、利用者定義の小関数、比較を当面の対象とする。`async/await`、Promise、Node.js、module読込み、Javaへのアクセス、ネットワーク、ファイルI/Oは言語仕様に入れない。完全なECMAScriptを走らせる必要はない。

実装済みの構文例（公開`agent_run_script`で受け付ける。移動2反復＋inventoryの実機smokeは成功したが、以下の複合例全体の実機確認ではない）:

```js
function advance(x, count) {
    repeat(count) { breakBlocks(x=x, y=64, z=120, dy=1, advance=true); x=x+1; }
}
advance(count=2, x=119);
move(x=116, y=64, z=120);
let count = 3;
for (let i=0; i<count; i++) {
    if (i < 2) { move(x=116+i, y=64, z=120); }
    else { interact(target="block", x=116, y=64, z=120, block="minecraft:lever"); }
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

内部の`StartScript`は配送確認後に専用workerでこの言語を実行する。`move`／`breakBlocks`／`place`／`interact`／`inventory`／`click`／`input`を既存の内部v2 jobへ1命令ずつ渡し、各命令のterminalを待って次へ進む。親scriptは別の共通job IDを持ち、命令の確定数を保持する。取消・緊急停止時は実行中の子jobに取消を要求し、その入力解放が終わるまで親をterminalにしない。source・work・反復・呼出し数・実行時間は有限にする。workerはMinecraft stateを直接読まない。

`interact(target="menu",...)`は1命令内で開封・照合・閉鎖を完結する。menu type・block・slot/item/count条件を要求し、最大16回の全stack移動を新しいserver snapshotで確認する。クリック対応はVanillaのgeneric chest/dispenser・hopper・shulkerの通常slotに限定する。加工・取引・MOD独自GUIは読むだけでクリックしない。

**未完了:** 命令ごとの失敗詳細、menuを開いたまま複数命令へ引き継ぐ操作、観測量予算。`agent_run_script`は公開catalogへ接続済みで、2反復の移動・inventory合成を実機確認した。script内のすべての行動や停止経路を網羅したわけではなく、Draft PRを維持する。

## このブランチで実装済みの内部座標ナビゲーション

`agent/navigation/CoordinateGoalPlanner`はpure Java部品。1つのworld session・dimension・座標目標に対してjob全体で同じinstanceを保持する。入力はimmutableな`KnownTraversabilitySnapshot`、現在地、呼出側が確認したsession/revision、予算と取消signalだけで、world読取り・入力操作は行わない。目標は未観測・遠方でも指定できる。

snapshotの既知の安全な停止候補を目標への直線距離、同距離なら`NavCell`順に評価し、既存`DeterministicAStar`で到達できる最初の候補を返す。unknownやSTALE/BLOCKEDのedgeは通らず、斜めの角・現在revisionの直接証拠、距離・実行時間の予約は既存A*／`RoutePlan`に従う。既存の`PROBE_ALLOWED`は支持・clearance等が確認された有限probeとして維持し、未観測cellへ踏み出す許可にはしない。影響を受けず保持された古いedgeは既存mapの無効化契約に従って利用する。

候補をA*に渡す前に、現在地から確認済みedgeでつながるcellだけへ絞る。観測済みでも分断された地形が64件の候補枠を使い切り、近くの進める道を見落とすことを防ぐ。この事前判定は経路の承認ではなく、A*が距離・斜めの証拠・予算を改めて検査する。

- `REACHED_KNOWN_GOAL`: 入力の現在地が既知の目標と一致（移動edgeなし）。`KNOWN_GOAL_ROUTE`: 既知目標までの計画で、実移動の成功ではない。`PARTIAL_WAYPOINT`: 既知中間点までの計画で、到達後に新しい観測が必要。
- `BLOCKED / LIMIT / CANCELLED / STALE_MAP / WORLD_MISMATCH`: 経路なし。現在session/revisionとの不一致・受理済みrevisionからの巻戻り・異world/future edgeを拒否する。実行直前・各tickの再検証は呼出側に必要。
- 1回のplanの固定上限はsnapshot edge 4,096件、候補A*呼出し64回、A*全呼出し合計2,048展開。各予算は0まで縮小可能。edge上限超過は切り捨てずLIMIT。取消・thread interruptionは列挙・探索・結果確定前で確認する。
- 既知の安全な経路では、目標から一時的に遠ざかる中間点も選べる。発行した経路上のcellは同じjobで再び中間点にしない。次の中間点には前回発行後の新しいmap証拠を要求し、同じ証拠での巡回・実行失敗後の即時再発行を防ぐ。既知cell履歴は8,192件で上限停止する。未知cellへの移動、網羅的frontier探索、失敗した経路の自動再試行は未対応。

内部の`StartMove`は絶対`x/y/z`、または開始時のプレイヤーcellからの`direction/distance`を受ける。後者はワールド方位の南北東西・斜め4方向・上下を1～4,096 blockで指定し、座標との混用は拒否する。任意の`arrival_radius`は0～16 blockの三次元距離で、既定の0は指定cellへの到着を要求する。正の値なら指定座標に近い安全な到達可能cellで止まれ、目標cell自体が通れない場合にも使える。到達許容幅（0.1～0.49）、最大実行tick（1,728,000、既定1,200）、総移動距離（4,096 block、既定256）も受ける。`CoordinateMoveJobExecution`が既知区間を既存の移動executorへ渡し、中間点到着後に新しい観測を待って次を計画する。部分経路の再計画要求も新証拠を待つ。最終経路完了時は入力を解放し、次のclient tickで現在cellを読み直して到達を確定する。目標への最終経路が途中で無効になれば、既定は失敗で停止する。`clear_path:true`では新しい経路証拠を待って再計画する。観測が進まない場合も有界に停止する。runtimeは配送確認、world・player・control epoch、体力、screenと局所安全、移動距離、各tickの経路証拠を確認し、取消・危険・緊急停止・world境界で入力を解放してから終端を返す。目標が未観測でも受理するが、未知地形へ踏み出すわけではない。

座標・見えるblock state・所持item数・画面種類の`stop_when`と任意の局所障害物処理を実装済み。任意entity状態条件・複合条件、縦方向の自動施工は未対応。公開catalog/schemaとscriptへ接続済みで、基本移動・script移動の到着を実機確認した。すべての地形・停止経路の検証は残る。

## このブランチで実装済みの内部ブロック作業基盤

内部の`StartInteract`のblock分岐はstate変更を扱う。単一座標またはblock ID条件付きの範囲を受け、必要なら既知の安全経路で近づく。MODのblock IDも照準対象にできる。手持ちは空hotbar枠、または明示したitem IDのhotbar枠を使い、通常の右クリック後に予測sequence・サーバーACK・変更後のblock stateを確認する。`expected_after_block`／`expected_after_properties`を指定した場合はその条件にも一致させる。stateが変わらないmenu開閉は別の`target:"menu"`分岐で扱う。entity／item分岐は上記の共通operation jobへ接続し、main inventoryからの持ち替えも扱う。block分岐の使用item選択はこの段階ではhotbarのみ。

`BlockWorkRegion`は破壊・設置で共用する座標範囲を保持する。`x/y/z`は始点、`dx/dy/dz`は符号付きの**終点差分**で、両端を含む。差分0は単一座標。X、Z、Yの順で安定して列挙し、最大4,096 cellと整数overflowを検査する。将来の楕円・path形状は現時点の契約に含めない。

内部の`V2PlaceArguments`は同じ範囲、必須のVanilla `block`、省略可能な`item`・`properties`・`replace_blocks`と作業上限を受理する。`item`省略時はblock IDと同じitemを選ぶ。`properties`省略時は設置結果の既定stateを受け入れる。`replace_blocks`省略時はair系blockだけを対象とし、他のblockへの置換は明示条件が必要。破壊と設置は`V2BlockJobExecution`の配送確認・範囲進行・取消・入力解放後の終端確定を共用し、進行結果では`broken_blocks`と`placed_blocks`を別名で返す。

内部の`StartPlaceBlock`はruntimeのclient tickへ接続した。現時点の`MinecraftV2PlaceDriver`は、手の届く単一cellについて、局所観測済みの支持面を選び、通常BlockItemを使って設置し、予測sequenceとサーバーACK・設置後stateを照合する。itemがhotbarにない場合、main inventoryの対応stackを選択中のhotbar枠へ一度だけSWAPし、両slotのサーバー更新と実inventory値が一致してから設置する。不確実な送信や確認切れではSWAPを再送しない。SWAP後のhotbar配置は自動で元に戻さないため、設置失敗時にもinventory配置が変わり得る。支持面との不用意なinteractを避けるため短期leaseでかがみ、実crosshair・置き先・予測stateを使用直前に再確認する。`advance:true`の場合、未観測・遠方の置き先へは既知の安全な区間を移動して再観測する。置き先のcellは立ち位置にも経路にも使わない。既知経路が増えなければ有界に停止し、未知cellへは踏み出さない。ベッド・扉・二段植物は派生cellも現在の可視性・置換条件・配置stateを確認し、同じprediction sequenceのACKと両cellのserver更新で確定する。`confirmed_cells[].companions`に相方を記録し、個数はアンカー単位。相方は指定アンカー範囲の外に出る場合がある。既に同じ設置が見えている場合は`observed_cells`へ分け、異なるblockによるskipを施工完了と扱わない。全Vanilla状態の実機確認は未完了。公開catalogへ接続済み。

内部の`V2BreakArguments`は未観測座標でも受け、任意の`include_blocks`／`exclude_blocks`、最大破壊数・実行tick・移動距離、`advance`を保持する。公開`agent_break_block`はこれらを受け、条件を新たに観測したblockへ適用する。`V2BreakSourcePolicy`はv1の固定許可リストから独立し、明示対象の登録済みVanilla非air blockを扱う。既存の攻撃leaseと予測確認ポートはv1ポリシーを既定として残し、v2専用インスタンスだけ新ポリシーを使える。`KnownBlockBreakAttempt`はv2向けに、期待dropなしでもサーバーACKと権威あるairへの遷移で成功を確定できる。局所rayの照準選択は、MCPへの観測配送を必須とせず、現在の視点・reach・revision・観測時刻に合う内部frameだけを使う。

内部の`StartBreakBlock`は配送確認後に共通jobとして動き、範囲を順に調べる。`advance:true`では各X/Z列のY方向を先に処理し、2段の坑道なら手前の足元と頭上を空けてから奥へ進む。対象がまだ見えない／届かない場合、既知の安全な経路で中間点まで接近し、新しい局所観測を待ってから次区間を検討する。破壊対象のcellは立ち位置にも経路にも使わない。未知cellへは踏み出さず、既知の経路が増えなければ有界に停止する。手の届く指定cellが空気の場合は、現在の視点からの視覚・衝突・液体rayが共に遮られず、経路上のcellがロード済みのときだけ読み飛ばす。局所rayで確認できた非air対象についてhotbarからドロップ採取可能な道具を優先し、同条件では採掘速度で選ぶ。適切な道具がなければ他の道具・空手も候補に残す。照準を合わせて通常の攻撃入力を出し、v2専用の最大600tickの攻撃lease内でサーバーACKとair遷移を確認して次の対象へ進む。v1の40tick上限は維持する。走査済みcell数と確定破壊数に加え、確定セル座標の直近128件・総数・保持開始番号・打切りを取消・終了後も直前のjobまで残す。取消・危険・world変更では入力を解放してから終了状態を確定する。公開catalogから呼び出せ、基本破壊・連続採掘の実機確認済み。

**未完了:** 遠方の空気・液体を含む範囲への到達、エンチャントや耐久条件を含む道具選択、600tickでも壊せないブロックの扱い、block状態以外の詳細効果記録、採掘中の危険停止の実機試験。2cell破壊と10cellの連続採掘は実機確認済み。現段階の`advance`は安全な既知区間を歩ける場合に限り、planner自身は障害物を壊さない。v1の固定許可リストは現行公開経路に限り保持し、v2の完成形へ引き継がない。

## raw入力

ゲーム内の論理キー／左右中クリックを指定する。`steps`に複数の同時入力、順番、`hold_ticks`、`gap_ticks`、`repeat`、`until`、最大総時間を記載できるようにする。入力頻度はgame tick単位で表す。world操作を起こしうるクリックには座標範囲・対象種類・手持ちなどの任意guardを付けられ、guardなしでも総時間と入力回数の上限を保つ。画面やワールドが変われば入力を解放して停止する。OSへキーを送る機能とは区別する。

内部の`FiniteInputSequence`は、9種類の論理入力について、同時押し・順次step・押下tick・gap・有限反復と外部停止条件／取消をtickごとに進める。最大64 step・合計1,728,000 tickで、停止後は空入力だけを返す。`InputSequenceLeaseDriver`は既存の短期leaseを使い、入力集合の切替前・停止時に旧入力を解放し、期限切れ・解放失敗なら次の入力を出さず停止する。中クリックに相当する`pick`は、独立した入力所有を持ち、押下の立ち上がり時にワールド内でVanillaのピック操作を一度呼ぶ。`InputSequenceJobExecution`はこのdriverを共通jobへ接続し、配送確認・session・安全判定を満たしたtickだけ入力を発行する。取消・world変更・危険・lease失敗では停止意図を保持し、入力解放の再試行が成功するまでjobを非terminalに保つ。内部の`StartInputSequence`からclient tick・緊急停止・既存の配送確認／進行取得／取消へ接続した。内部の`StartClick`は左右中のbutton、有限回数・押下tick・間隔を同じjobに変換する。任意のblock座標・block IDまたは観測済みのentity_ref・種類を指定した場合、開始時と各入力tickに実crosshair・reach・生存／視線を照合する。最後の入力でmenuが開いても、同じworld／所有者／安全条件を確認して入力解放を完了できる。入力はworld/session、画面、局所安全、体力、移動距離、control epochを毎tick確認し、移動には既存の衝突・revision証拠を要求する。**公開catalogへ接続済みで、gateへのraw右click、有限入力反復、移動入力の取消とかがみ入力中の危険停止を実機確認した**。raw入力によるmenu内クリック、任意範囲guard、任意キー、server結果照合、entity attackの適切な許可境界は未実装である。完成版へ進める前にこれらを解決する。

内部の`agent_input_sequence`は、任意の`stop_when:{x,y,z,radius}`を受ける。プレイヤー足元のworld座標が指定cellのX/Z中心・Y値から半径内に入ったtickでは次の入力を発行せず、保持中のleaseを解放して終了する。半径は0.1～16、未指定時は0.75。`type:"position"`を明示する形に加え、`type:"block"`と座標・block ID・任意properties、`type:"item"`とitem ID・count・比較（at_least/at_most/equals）、`type:"screen"`とnone/container/inventory/chatを指定できる。block条件は現在見えるloaded cellだけで判定し、隠れた空気を真としない。item条件は自分の所持数。成立時は次の入力前に解放する。任意キー入力と複合条件は未対応。

## 実施順・確認

1. `AGENTS.md`と規範仕様を新しい責任境界に短く改める。専用worktree／Issueで実装する。
2. 観測の小型化と`agent_get_mcp_status`、手元9枠、引数なしのatomic observationを実装する。
3. 基本行動ツールを一組で公開する。まず既存の内部実行器を再利用し、目標は公開DSLの撤去と単一のjob実行基盤に収束させること。
4. 範囲指定の破壊・設置、経路中の破壊・移動、道具fallbackを隔離fixtureで試験する。
5. 制限付きスクリプトと入力sequenceを接続し、有限ループ・取消・部分成功・入力解放を試験する。
6. 必要な箇所を独立したローカル検証profileまたは許可済みの隔離Dockerで軽く実機確認する。従来方式の長時間の成功率計測は着手条件にしない。公開後の比較は補助として行う。main統合・Releaseは別指示まで行わない。

## 参照

- 現行契約：`main/AGENTS.md`、`main/docs/MCMCP_MCP_Tool_Catalog.json`
- [Baritoneの経路探索・長距離区間の説明](https://github.com/cabaletta/baritone/blob/1.21.4/FEATURES.md)
- [GraalVMのJavaScript組込みと依存条件](https://www.graalvm.org/jdk25/reference-manual/embed-languages/)
