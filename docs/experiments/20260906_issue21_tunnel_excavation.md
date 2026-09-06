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
- 通常nodeの32マス/8break等の受付上限を維持し、坑道専用の大きいcounterをwire schemaと同期する。
- break ACKとpickupの証拠を分離し、ledgerの省略があってもaggregateと実測counterを保持する。

## 実機受入 / Game acceptance

窓口は保守担当に一本化します。改善担当から通常プロフィールを操作しません。`MCMCP-Validation`または削除可能なcloneで、場所・変更範囲・baseline・復旧手順・対象JAR hashをT0前に記録し、T0後は公開MCPのみで実行します。

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

Maintenance owns game acceptance. Compare equivalent baselines using tool calls, action starts, elapsed time, completed distance and actual model usage when available. Schema byte counts are not token or billing measurements. Code/CI success and game acceptance are separate gates; do not publish a release containing this change before acceptance.
