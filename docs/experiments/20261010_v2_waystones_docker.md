# 2026-10-10 Waystones・バッグ・遠方梯子の統合検証

aod-mimoidの既存隔離Dockerで実施。通常「くらふとぶ！」は地図アート担当の所有を維持し、開発担当は操作・再起動していない。導入作業は通常ゲームの所有引継ぎ後に差し替え担当が行う。

## 最終候補

- 製品ソース：`322b0cf89da1b27ec261bd05125737ceb0e278cf`、候補R15。
- 製品JAR SHA-256：`c913077ad3b72a187493a0872b232dd7158541e4fb4b5e01c00114df50642e68`。
- harness SHA-256：`eb1918797e8a8ec7e72087d9dd0cd0f1d50ac621b30d9b486b1cf1ff11265a73`。通常profileには入れない。
- Waystones 26.2.0.9、JAR SHA-256 `5b9e47b8f94a3a544e828a8063a088fc2d832b36d33a22f7f0167610a624f4c1`、NeoForge 26.2.0.59、Minecraft 26.2。
- ローカルJava 1,704＋harness 14＋admin bridge 28＝1,746件、build・harness隔離、共通PowerShell smoke runner 7ケースが成功。製品commitの[CI](https://github.com/Aodaruma/mcmcp/actions/runs/38012880639)も成功。
- 実行中JARの公開15ツールは、名前・説明・入出力schemaを含む全定義がcatalogと一致した。

## 実機で見つかった問題

R12の最初の試行は検証クライアントの古いcatalogが新しい入力を拒否し、ゲーム側jobは開始されていなかった。catalogを同期して再実行した。

R12は一覧を開いて閉じる、使用中取消、tick上限が成功したが、全候補を選択不可とした。Shogiのルール一覧には型付き費用だけでなく、変数代入・効果の戻り値であるJsonPrimitive／Float／Boolean等も含まれる。これを未対応費用と誤判定していた。R13以降は既知のスカラー値を費用から除き、nativeのEither許可判定、経験値費用、未対応の構造化要件の拒否を維持した。回帰試験2件を追加した。

試験fixtureの再準備では、Endの古いWaystone登録が残って同名候補が重複した。harnessは固定座標・dimension・fixture名が一致する旧登録だけを解除して再作成する。R15で再準備を2回行い、各dimensionの候補が1件ずつであることを確認した。

初回のEsc runnerは配送確認前の`unconfirmed`を終了扱いし、Escを送らず一覧取得が完了した。これは停止の合格に数えない。runnerを共通job状態の契約に合わせて修正し、使用送信後を記録して再試験した。

## R15の確認結果

| 操作 | 確認結果 |
| --- | --- |
| 行き先一覧 | Overworld／Endの2件、期限付き参照、選択可否・経験値費用。選択送信なし、画面解放 |
| 使用中cancel | 使用送信後に取消。cancelled、release_pending=false。続くinspectも成功 |
| max_ticks=1 | failed、選択送信なし。続くinspect成功 |
| 経験値上限0 | 27ポイント必要なEndを送信前に拒否。費用消費なし |
| 未発行参照 | failed、waystone_destination_or_cost_changed、選択送信なし |
| End往路 | 選択要求は1回。clientでEnd到着後、serverで309.5／101／308.5、経験値75→48を照合 |
| Overworld帰路 | 新しい一覧から参照を取得し1回選択。serverで309.5／101／308.5、経験値48→21を照合 |
| session変更前の参照 | 往路前の未使用参照を帰還後に渡し、選択送信前に拒否 |
| 保持use中のEsc | 5tickで画面境界のfailed（unexpected_screen_opened）。release_pending=false、選択送信なし。復帰後READY/jobなし |
| Esc後OFF→ON | UI OFFでjobなし。再度ONにして新しい一覧取得が成功。使用中のOFF競合とは区別する |

全非転送ケースは位置・dimension・経験値不変、体力20・cursor空。往復も各到着後に体力20・cursor空をserver reportで確認した。同一接続のdimension変更後は新しいsessionとなり、MCP ON意図を維持してREADY/jobなしへ戻った。選択jobは`selection_dispatched:true`／`effect_confirmed:false`で終了し、到着は後続stateとserver reportで別に照合した。

同じR15でバッグPlain／Upgraded／Tabのtake24・store16・再open、移送中取消、14clickを超える要求の事前拒否が合格。upgrade設定とopen tabは不変。遠方下階を直接指定するFarGoalも成功し、server接地・体力20・cursor空を確認した。[バッグ詳細](20261010_v2_backpack_docker.md)、[梯子詳細](20261010_v2_ladder_docker.md)。

## 証拠と残る範囲

証拠はローカル `artifacts/v2-ladder-far-20261010/r15-evidence.zip`（SHA-256 `a533ce2cb230893c24f68159195ed157b777422a855b5d32b47f6875573670f2`）、全schema照合は同ディレクトリの`r15-schema-verification.json`、配布候補は`package-r15`。元world・設定・JARの66ファイルは10:30 JSTにhash一致で復元し、fixture除去・検証container停止を確認した。10:32 JSTにDocker Desktopプロセス0件と維持用SSHの終了も確認した。証拠内の`restoration.json`／`desktop-restoration.json`を参照。

試験設定ではワープストーンの往復を直ちに実行でき、クールダウン拒否は発生しなかった。独自cooldown設定、使用中のUI OFF／world変更の同時競合、通信遅延・マルチプレイ、未知の費用・別MOD版・scroll／warp plate／設置Waystoneは実機未確認。経験値上限はメニュー受信費用の送信前検査であり、server再計算費用の原子的な上限ではない。nativeの耐久消費も経験値上限の対象外。
