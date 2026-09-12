# 対象復帰を待つ有限長押し（PR #35）

Issue #34 / PR #35の有限長押しに、明示した反復モードを追加した。対象はMinecraft 26.2、NeoForge 26.2.0.59、Java 25。

## 動作と安全境界

- `repeat_target` 省略・falseは従来の厳密停止。trueでは `max_repetitions:1..64` を必須とする。
- 入力取得時は正しい対象を要求する。以後の対象消失、MISS、entity、別の位置・面・block・stateでは新規入力を抑止し、同じ条件の復帰を同一Action内で待つ。自動照準や期限延長は行わない。
- 初回を含むVanillaの新規採掘・使用開始ごとにinteractionを記録する。採掘は同数の破壊枠を事前予約する。継続、cooldown、待機は反復回数へ数えず、次の開始が上限を超える場合は送信前に停止する。成功した破壊数や回収量を試行数から推定しない。
- 開始済みのAgent useだけは、対象不一致中もVanillaの物理useキーupで解除されない。次の使用開始は改めて対象一致と予算を要求する。terminal cleanup側のreleaseUsingItemにはこの例外を適用しない。
- 姿勢・手持ち・health・reach・Survival・Screen・world/session・安全条件を入力境界で再検証する。Esc、OFF、cancel、期限、watchdogは既存の解放経路を保つ。simulation pause後は更新済みのpause時間で元のactive-time予算を継続する。

公開JSON例と予算の指定は[クイックガイド](../MCMCP_Action_DSL_クイックガイド.md#対象の再生成を待つ有限長押し)に記載した。catalog、DSL、評価runnerの固定hashを同期した。簡易catalog validatorが扱わない条件式は、従来通りDSLのparser/validatorでも強制する。

## 検証

- `gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build` 成功。main test 1,273件、harness 13件、admin bridge 21件、失敗0。
- `tools/check-source.ps1 -SkipJava` 成功。Python MCP transport 14件、capability gateのmock試験11組。
- 追加回帰試験: 厳密モード互換、反復条件・回数・予算不足の拒否、複数の待機/復帰、継続中の非加算、上限超過のラッチ、使用開始前の非所有、pause/watchdog、会計例外、試行数と確認済みeffectの区別。
- ASMでコンパイル対象のMinecraft JARを調べ、採掘開始の経路、使用cooldown後の予算確認、handleKeybindsの単一releaseUsingItem呼び出し、Mixin登録を検証した。
- 独立Codex CLIへ差分と関連コードを直接渡した静的レビューで、ブロッキング指摘なし。subagentsは使用していない。CLIの読み取り専用コマンドがWindowsの実行ポリシーで拒否されたため、ファイル操作を行わないレビューとした。

## 実機で残る確認

利用者の許可に基づく指定Prismプロファイルへの製品JAR上書きと、実機での合格判定は分ける。この記録時点では実機試験を実施していない。`release:verification-needed`を維持し、公開Releaseタグを付けない。

1. 起動ログで両Mixinの適用失敗がないことを確認する。
2. 同じ対象の消失→再生成を数回繰り返し、同じaction_idで再開することを確認する。
3. 別block、MISS、entity、別の面への照準中は新規採掘・使用が出ず、元の対象へ戻すと再開することを確認する。既定モードは停止したままにする。
4. 弓・飲食の開始後に一時的に照準を外し、use継続と終了後の新規開始抑止を確認する。
5. 反復上限、元の時間/tick期限、Esc、OFF、cancel、手持ち変更、world変更で入力が解放されることを確認する。

道具の自動交換、耐久保護、回収量保証は含まない。
