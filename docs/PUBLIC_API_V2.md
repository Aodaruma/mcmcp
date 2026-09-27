# MCMCP公開API v2：改訂案

2026-09-27。利用者の追加フィードバックを反映した実装用の設計案。基準は`main`の`f7d4bf4`。この文書の新APIは実装途中または未実装であり、現在の公開MCP仕様とは区別する。

## 目的と責任

LLMは行き先、作業範囲、条件、反復の意図を伝える。MODは必要な局所観測、経路、道具選択、照準、ゲーム入力、server応答の確認を引き受ける。利用者が未観測の座標や範囲を指定してよいが、MODはその場所の隠れたworld状態を先読みしない。現在地から進み、読み込まれて見えるようになった部分を判断して続行する。

公開JSON Action DSLは、制限付きの小さなスクリプトと短い基本行動ツールへ**置換**する。移行中の内部再利用は許すが、両方式の公開維持を完成形にしない。旧`main`の`AGENTS.md`にあった固定5ツール、raw入力禁止、任意コード禁止は、このPRで新しい契約へ更新する。

## state・観測

| ツール | 既定で返す内容 | 明示要求／別ツール |
|---|---|---|
| `agent_get_state` | プレイヤーのdimension、位置・向き、体力・空腹・状態異常、手元のhotbar 9枠と選択枠。各枠のitem ID、個数、使用可否など公開可能な事実 | 全inventory、装備、レシピ、所持品集計はsection指定。session ID、MCP制御、最新frame/action IDは含めない |
| `agent_get_control`（新） | MCPのON/READY/OFF、許可capability、session、実行中action ID、最新frame ID、公開API version | 詳しいpolicy／利用可能命令は詳細要求時だけ |
| `agent_get_observation` | 引数なしで最新の観測を**一回で取得**。既定では近くのblock、entity、危険、通行候補を短く返す。要件ごとに範囲・種類・件数を指定可能 | 既知frameのページング、見えた面の詳しい証拠、音等は明示要求。毎回`get_state`でframe IDを取る必要をなくす |

観測は`visible_surface`ごとの長いrecordから、位置で束ねた`block` recordへ変える。見えた面は`faces:"UN"`のように1字コード（U/D/N/S/W/E）を並べるか、必要時に短い配列を返す。利用側が面の集合を解析する必要がない行動では表示自体を省き、MODは内部の実rayを保管する。座標のdimensionは応答共通部に置き、各recordに重複させない。tick/revisionは共通部へ寄せ、異なるものだけrecordに差分を残す。公開できない詳細stateは`null`のままにする。

`unknown_boundary`の行ごとの既定出力も、理由別の件数一覧も返さない。代わりに応答へ短い`complete:false`（探索範囲が全て判明したという意味ではない）と`truncated`／`next_cursor`だけを持たせる。**結果にない座標はunknownであり、airではない**。探索の方向を決める必要がある時だけ境界・遮蔽を要求できる。目視したairや通行可能cellを求める要求は、その事実を明示して返す。

## 行動ツールを一組として公開

次の公開面を一組で設計・公開する。説明量が重くなる場合は`agent_do({kind:...})`型の統合を比較できるが、初期案はLLMが見つけやすい名前付きツールを同時に公開する。各ツールのSchemaは短くし、詳細な使い方は必要時だけ取得する。

| ツール | 主な指定 | MODが行うこと |
|---|---|---|
| `agent_move` | 座標、方向＋距離、または「対象に届く」「範囲内へ」などの到達条件。必要なら経路中の破壊・設置を許可する条件 | 段階的な探索・移動、障害の局所観測、許可された範囲内で必要な破壊・設置、到達確認 |
| `agent_break_block` | 単一座標／範囲／方向と長さ、任意のblock条件・除外条件・最大個数 | 近づく、見えるようになったblockを確認する、適切な道具を探して選択する、通常操作で破壊する、結果を確認する |
| `agent_place_block` | 単一座標／範囲、block/item、任意の望むstate・配置条件 | 手持ち確保、支持面・方向・接続の検討、移動・設置・照合。state未指定なら既定の設置状態を受け入れる |
| `agent_interact` | block／entity／itemと目的。座標／範囲や種類の条件は任意 | 対象を見つける、接近、通常使用、画面やstateの変化を確認する |
| `agent_inventory` | `inspect`／`transfer`／`drop`、item条件、数量、移動元・先 | hotbar／inventory／対応menuの同期、移送・投棄、数量の確認 |
| `agent_click` | 左／右／中、対象／現在照準、回数、画面文脈 | 必要な入力と効果の確認。中クリックはゲームモード別に意味を扱う |
| `agent_input_sequence` | 同時・順次のsteps、押下tick、間隔、反復数、停止条件 | 複数入力の単一所有、有限実行と解放 |
| `agent_run_script` | 下記の制限言語のsourceと総予算 | 関数・変数・分岐・反復で複数の基本行動を組み立てて進める |

これらの一呼出しは共通の`action_id`を返す。短い処理は同じ応答へ完了結果を載せられる。長い処理は`running`と`action_id`を返す。`agent_get_action({action_id})`は**その一件**の進行、成功／失敗、確定した変更、途中までの結果を読む。`agent_cancel_action({action_id})`は**その一件**の停止を要求する。たとえば`agent_move`が坑道を掘り進めている間も、同じIDで進行を見て中止できる。中止は既に壊したblockを戻さない。名前は既存互換のため残し、v2では共通の「作業結果・中止」ツールとして説明する。

move＋breakを利用側で毎ブロック交互に呼ぶ必要はない。`agent_move`に`clear_path`を指定する、または`agent_break_block`に`advance:true`を指定して坑道を連続施工できる。範囲内で新たに露出したblockはMODが局所的に観測し、液体、落下、危険な敵、inventory満杯等で停止・報告する。既存PR #26の坑道専用DSLは参考にするが、公開上の専用命令増殖は避ける。

破壊の`expected_drop`や完全な`expected_state`は必須にしない。道具は採掘可能性、速度、耐久、Silk Touch/Fortune等の条件を見て選び、適切なものがなければ他の道具または素手へfallbackできる。ただし指定した成果物が得られない可能性は結果に表す。位置、上限、block条件、除外条件を優先して、誤って別blockまで壊さない。設置ではitemと座標を基本にし、向き等のstate指定は任意にする。

## 制限付きスクリプト

公開言語はJavaScript風の小さな同期言語。turtle／p5.jsのように`move(...)`、`breakBlocks(...)`、`place(...)`、`interact(...)`、`input(...)`を順に書ける。変数、数値・文字列・真偽値・配列・オブジェクト、`if`、回数上限付き`for`／`repeat`、利用者定義の小関数、比較を当面の対象とする。`async/await`、Promise、Node.js、module読込み、Javaへのアクセス、ネットワーク、ファイルI/Oは言語仕様に入れない。完全なECMAScriptを走らせる必要はない。

例（構文は実装時に固定する）:

```js
for (let i = 0; i < 16; i++) {
  breakBlocks({ direction: "forward", width: 1, height: 2, length: 1 });
  move({ direction: "forward", blocks: 1 });
}
```

各行動関数は、完了または明示的な失敗まで内部で待ってから次の文へ進む。ゲームthreadを塞がず、script実行器は別の作業threadで動き、ゲーム操作を既存の入力所有・安全停止へ渡す。source長、実行時間、演算数、メモリ、ゲーム操作回数、観測量、反復回数を有界にする。停止後の入力解放と途中結果の回収は一つのjobとして保証する。言語処理系を自作する場合は閉じた文法のparser/runnerを比較する。GraalJSを使う場合も公開構文を厳格に限定できるか、依存サイズと停止特性を確認する。

## raw入力

ゲーム内の論理キー／左右中クリックを指定する。`steps`に複数の同時入力、順番、`hold_ticks`、`gap_ticks`、`repeat`、`until`、最大総時間を記載できるようにする。入力頻度はgame tick単位で表す。world操作を起こしうるクリックには座標範囲・対象種類・手持ちなどの任意guardを付けられ、guardなしでも総時間と入力回数の上限を保つ。画面やワールドが変われば入力を解放して停止する。OSへキーを送る機能とは区別する。

## 実施順・確認

1. `AGENTS.md`と規範仕様を新しい責任境界に短く改める。専用worktree／Issueで実装する。
2. 観測の小型化と`get_control`、手元9枠、引数なしのatomic observationを実装する。
3. 基本行動ツールを一組で公開する。まず既存の内部実行器を再利用し、目標は公開DSLの撤去と単一のjob実行基盤に収束させること。
4. 範囲指定の破壊・設置、経路中の破壊・移動、道具fallbackを隔離fixtureで試験する。
5. 制限付きスクリプトと入力sequenceを接続し、有限ループ・取消・部分成功・入力解放を試験する。
6. 必要な箇所を許可済みの隔離Dockerで軽く実機確認する。従来方式の長時間の成功率計測は着手条件にしない。公開後の比較は補助として行う。main統合・Releaseは別指示まで行わない。

## 参照

- 現行契約：`main/AGENTS.md`、`main/docs/MCMCP_MCP_Tool_Catalog.json`
- [Baritoneの経路探索・長距離区間の説明](https://github.com/cabaletta/baritone/blob/1.21.4/FEATURES.md)
- [GraalVMのJavaScript組込みと依存条件](https://www.graalvm.org/jdk25/reference-manual/embed-languages/)
