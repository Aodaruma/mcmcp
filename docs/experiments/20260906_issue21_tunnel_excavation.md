# Issue #21 — 坑道掘削の実装と受入条件

## 依頼と範囲 / Scope

友人のClaudeによるブランチマイニングで、1マスずつ観測・判断する往復が遅く高コストとの報告。使用model/version、prompt、trace、実使用量は未入手で、モデル側の原因や改善率は未確定です。

ownerは2026-09-06に別worktreeでの実装を依頼し、4マスの外側上限ではなく1〜10チャンク相当の直線掘進と、有限範囲の枝坑道patternの両方を希望しました。直線がdefaultです。専用branchは`issue/21-branch-mining`、着手baseは`93712ec45c2fcbf644d9cc86565a12edeec4c8bf`です。

The reported Claude run is unavailable. This work addresses the architectural round-trip overhead rather than claiming a model-specific defect. The owner requested both straight and branching excavation in an isolated worktree, with straight mode as default.

## 自動試験の観点 / Automated checks

- 初回の配送済み面・完全state・入口姿勢と、未配送の将来範囲を区別する。
- 直線16/160と枝坑道の座標・順序・枝からの戻り・最終位置を固定する。
- 露出した液体、床欠損、落下物、敵、道具/容量不足で停止し、suffixを送信しない。
- renderer欠測待ち、ACK不明、cancel、入力解放、元の総期限を検証する。
- dev fixtureが一時的に所有する22チャンクと512 rays/tickを現在値から再確認し、外部変更時は実行を拒否して、終了・失敗・置換・shutdown時に元の値へ戻す。
- 実行前statusのraw hash・`setupId`・mode・実`worldSessionId`をAction終端・cleanup・oracleまで束縛する。
- 通常nodeの32マス/8break等の受付上限を維持し、坑道専用の大きいcounterをwire schemaと同期する。
- break ACKとpickupの証拠を分離し、ledgerの省略があってもaggregateと実測counterを保持する。

## ローカル検証結果 / Local validation

- 対象コード / Validated code commit: `e3a22babb63e7ce7f7e0a5b23a1ea9a983ab5e29`（latest main `805cd5321f907932bf9f53a172c8975dd7014698`を統合）。
- `gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build --console=plain`: **PASS**。unit 1,303、harness 27、admin 21、計1,351件でfailure/error 0。
- `gradlew runGameTestServer --console=plain`: **15/15 PASS**。固定坑道fixtureの代表90ブロック配置も専用source set内で検証。
- `Test-McmcpEvalTrace.ps1 -SelfTest`: **71/71 PASS**、`Test-McmcpLiveMonitor.ps1`: **81/81 PASS**。
- 坑道専用mock: capability gate **PASS**、bounded acceptanceの4モード正常系と追加80異常系（範囲外変更、別run/別Action、status差し替え、欠測、型違い、重複JSON key）**PASS**、renderer witness **13/13 PASS**。
- `python -m unittest discover -s tools/mcp -p 'test_*.py' -q`: **14 PASS**。認証不要のmock HTTP試験で、ゲームへは接続していません。
- catalog raw SHA-256: `0c36ccfe6c923b61a385c402d2343178a11f3b942ee9d1eba3a4993a748c7544`、semantic Tool surface SHA-256: `a452d7812915c0e3d0d2e51fa9a2ee32e97667469ccde04ebfd7e388fb0cbe67`。
- JAR: `build/libs/mcmcp-neoforge-26.2-0.1.0-SNAPSHOT.jar`。
- JAR SHA-256: `A8A1168B284D576F63DB4182D2C216C11E0B04EB49C87922431187A87CB8F37D`。
- 実機での16/160マス完走・枝坑道・性能改善は**未確認**です。新opcodeは回収完了を断定せず、`drop_collection=not_asserted`を記録します。

レビューで採掘後の予測airとACK待機の分離、ACK確認後に中断した場合のcounter保存、可視液体・落下中blockの停止、入力解放が3回未確認の場合の既存deferred cleanup/OFF lockへの移行を補強しました。さらに、準備後に強制読込フラグまたはrays/tickが外部変更された場合、保存台帳の件数へフォールバックせずstatus・oracle・server pre-tickで同じ実測判定を使って復旧します。受入証拠は全階層の重複keyと暗黙のJSON型変換を拒否し、開始receipt、poll結果、terminal直後とcleanup後の公開stateを同じAction IDへ束縛します。最初の終了意図と所有権を保持し、解放未確認のterminalを公開しません。

The automated checks above passed. They do not establish live-game completion or measured token savings. The validation JAR contains the new operation and deterministic acceptance support; live acceptance remains pending.

## 実機受入 / Game acceptance

改善担当がこの機能の実装・受入を担当し、保守担当から届くmainのhotfixを都度レビューして取り込みます。`MCMCP-Validation`または削除可能なcloneで、場所・変更範囲・baseline・復旧手順・対象JAR hashをT0前に記録し、T0後は公開MCPのみで実行します。production用Prism JARは実機受入前に置き換えません。

| 条件 / Scenario | 確認 / Check |
| --- | --- |
| 安全な直線16マス / Straight 16 | 1つのstart Actionで完了。幅1高さ2、終点、回収の別証拠。 |
| 安全な直線160マス / Straight 160 | 内部継続中にLLMへの再startを要求しない。有限進捗と最終位置。 |
| 枝坑道 / Branches | 固定footprint外を掘らず、左右の枝から戻り、主坑道終点で完了。 |
| 掘削で液体・床欠損露出 / Hazard | 次のbreak/前進を停止し、確認済みprefixと未確認操作を区別。 |
| 観測欠測 / Missing visibility | 元deadline内でのみ待ち、可視証拠なしのattackを行わない。 |
| ACK遅延・不明 / Uncertain ACK | 対象mutationを再送しない。 |
| 途中Esc/OFF / Cancellation | 全工程で入力を解放し、通常プレイへ戻せる。 |
| 道具・容量不足 / Resources | 継続前に停止し、回収個数を推測しない。 |

同じbaselineと目的で既存の複数Action方式と比較し、Tool呼出し数、start数、所要時間、完了距離、モデルから取得できる実使用量を記録します。Tool schemaのUTF-8 byte数をtoken数や課金額として扱いません。モデル未指定の推測値で改善率を作りません。

The improvement owner runs game acceptance and incorporates reviewed maintenance hotfixes from main. Compare equivalent baselines using tool calls, action starts, elapsed time, completed distance and actual model usage when available. Schema byte counts are not token or billing measurements. Code/CI success and game acceptance remain separate gates.
