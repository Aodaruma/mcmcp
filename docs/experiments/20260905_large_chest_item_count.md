# ラージチェスト集計個数の上限修正

2026-09-05。computer-use・gameplay Actionを使わず、実行ログとコードを調査した。

## 再現情報と原因

- `694ec9f3-6f13-4410-87d5-17db82a2ac41`、overworld `(158,66,-304)` のinspectで `INTERNAL_ERROR / runtime_exception`。17 ticks、interaction1、NODE_EVIDENCEなしとして報告された。
- 最新ログの19:58:56.619に `IllegalArgumentException: item count is outside the bounded range` を確認。`KnownContainerAttempt.ItemCount` → `itemCounts` → `tick` → `McmcpRuntime.tickAgentContainer` で発生した。
- server同期snapshotの同一アイテム合計を、ItemCountが2,304個で拒否していた。これはプレイヤー36枠×64の上限で、対応済みgeneric_9x6の54枠×64=3,456個と不整合だった。ログには対象アイテム名・正確な個数は出ておらず、そこまでは断定していない。
- 先行Action `ec10a5f5-f279-4806-affd-77814a92c8fb` の `unexpected_screen_closed` は別件。利用者から操作が重なったとの補足があり、通常無操作でも画面閉鎖が再現するとは扱っていない。

## 修正と検証

- 読み取り結果ItemCountの上限を54×64へ修正。server full-content・画面所有権・cleanup後に結果を返す条件、公開DSLの転送目標上限は変更していない。
- 2,305、3,000、3,456個でserver-confirmed結果がcleanup後に成功として返る回帰テスト。interaction1、effectsなし、release/retire各1回を確認した。
- 0と3,457個は引き続き拒否する境界テスト。
- unit1,075件、harness13件、admin21件、buildとharness isolation成功。
- JAR SHA-256: 958F9A26554903DC1BB3C8CAEDE41C41C255643717DD9FADC3342F7EE0C38128
- 実行中ゲームと配布ZIPは変更していない。他タスクで再試行中のため、ゲーム操作を追加していない。修正版を反映して公開MCPのNODE_EVIDENCEに内容が返る実機追試は未実施。
