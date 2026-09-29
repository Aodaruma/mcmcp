# 公開API v2：通常環境の収納確認が終了しない問題（2026-09-29）

Issue #84 / Draft PR #85の試用中、「くらふとぶ！」でネザライトバックパックのinspectが400 tickを使い切り、取消・実時間期限・MCP OFF後もrunningに残る報告を受けた。入力拡張版`f787171893dff9ea3027fb9139c28ad28f8dad0b05ffcc375b8fa304bc9b2e7b`で発生。移送・粉設置は実行していない。

## 確認できたこと

`agent_inventory`へ`operation:"inspect",target:"storage",storage_slot:8,storage_item:"sophisticatedbackpacks:netherite_backpack",max_ticks:400`を一度送信。action `0a770cb5-f51d-4f10-89d6-e38d5d66d37e`は約38秒から400/400・complete:falseとなり、約205秒でもrunning・cancel_requested:true・remaining_seconds:0だった。前景へ戻し、MCP OFF・空cursorの画面閉鎖後も残った。ログに関連例外はなかった。privateな現場記録は公開添付しない。

コードでは、開封した画面の所有確認に「全slot同期済みで、操作可能な対応layoutであること」まで要求していた。同期不足や対応外upgradeタブ等でこの判定が成立しないと、終了処理も開封待ちのままになる。通常環境で最初に判定を拒否した具体的な条件は、この時点では未確定。120収納枠や1枠2,048個だけでinspectの上限を超えるわけではない。

## 修正候補

新しいserver開封記録、session、container ID、menu typeと、providerが確認する同一収納個体・slotを用いて画面の所有を確定する。この確認を、内容の読み取り・移送に必要な完全同期とlayout検査から分けた。内容を確認できない場合は成功にせず、所有画面の閉鎖と入力解放後に失敗を確定する。cursor確認や未確定移送の保護は維持する。

結果の`storage_progress`にはphase、開封証拠待ち、解放待ち・確認・異常、固定のwait_reason/failureを保持する。`storage_open_unconfirmed`、`storage_contents_unconfirmed`、`storage_layout_unsupported`を区別し、終了後も原因を残す。公開toolの名前・入力schema・上限は変更していない。

- 候補JAR SHA-256: `227ae54631d1b09f3fbd75f617bfcd03c1eaf348cef48f4b0e6416ea516a597c`
- `gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。製品Java 1,616件、harness 13件、admin bridge 28件、failure/errorなし。
- `tools/check-source.ps1 -SkipJava -PythonExecutable python`も成功。
- 4件の回帰を追加。slot同期不足でも開封所有を識別し、古い記録・別session・別container/typeを拒否すること、cleanup経路が完全内容検査に依存しないこと、解放後に診断原因を保持することを確認した。
- **候補の実機再確認・導入は未完了。** 現場の画面操作は依頼元が所有し、差し替え担当が復旧とJAR差し替え・再起動を行う。

## 再確認

実体側JAR hash・製品JAR 1個・再起動後の新session/schemaを確認する。対象収納へ同じinspectを一度だけ実行し、同一IDの最終結果・storage_progress・controlを記録する。成功なら内容の取得と画面閉鎖、非対応なら失敗の確定と実行所有終了を確認する。未確定のinspectを重複送信しない。内容取得と終了が確認できるまで移送や粉の設置は開始しない。

開封応答自体がなく、遅延応答の可能性を排除できない場合や、未確定cursor/移送が残る場合に、時間経過だけで解放済みとは扱わない。必要な復旧は通常GUIの切断・再接続またはJVM再起動とする。通常環境の原因と実機結果は確認後に追記する。
