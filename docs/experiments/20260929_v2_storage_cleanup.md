# 公開API v2：通常環境の収納確認が終了しない問題（2026-09-29）

Issue #84 / Draft PR #85の試用中、「くらふとぶ！」でネザライトバックパックのinspectが400 tickを使い切り、取消・実時間期限・MCP OFF後もrunningに残る報告を受けた。入力拡張版`f787171893dff9ea3027fb9139c28ad28f8dad0b05ffcc375b8fa304bc9b2e7b`で発生。移送・粉設置は実行していない。

## 確認できたこと

`agent_inventory`へ`operation:"inspect",target:"storage",storage_slot:8,storage_item:"sophisticatedbackpacks:netherite_backpack",max_ticks:400`を一度送信。action `0a770cb5-f51d-4f10-89d6-e38d5d66d37e`は約38秒から400/400・complete:falseとなり、約205秒でもrunning・cancel_requested:true・remaining_seconds:0だった。前景へ戻し、MCP OFF・空cursorの画面閉鎖後も残った。ログに関連例外はなかった。[Actionだけを抽出した記録](artifacts/20260929-v2-storage-cleanup/failure.json)を保存した。privateな現場の位置・所持品全体・ログは公開添付しない。

コードでは、開封した画面の所有確認に「全slot同期済みで、操作可能な対応layoutであること」まで要求していた。同期不足や対応外upgradeタブ等でこの判定が成立しないと、終了処理も開封待ちのままになる。初回報告時は拒否条件が未確定だったが、後述の比較で開いた作業台タブと整合することを確認した。120収納枠や1枠2,048個だけでinspectの上限を超えるわけではない。

## 修正

新しいserver開封記録、session、container ID、menu typeと、providerが確認する同一収納個体・slotを用いて画面の所有を確定する。この確認を、内容の読み取り・移送に必要な完全同期とlayout検査から分けた。内容を確認できない場合は成功にせず、所有画面の閉鎖と入力解放後に失敗を確定する。cursor確認や未確定移送の保護は維持する。

結果の`storage_progress`にはphase、開封証拠待ち、解放待ち・確認・異常、固定のwait_reason/failureを保持する。`storage_open_unconfirmed`、`storage_contents_unconfirmed`、`storage_layout_unsupported`を区別し、終了後も原因を残す。公開toolの名前・入力schema・上限は変更していない。

- 候補JAR SHA-256: `227ae54631d1b09f3fbd75f617bfcd03c1eaf348cef48f4b0e6416ea516a597c`
- 製品source: `48e741aae3143a67a8e17b6baf1bfeab48ced153`。[候補・検査metadata](artifacts/20260929-v2-storage-cleanup/candidate.json)と[hash manifest](artifacts/20260929-v2-storage-cleanup/manifest.json)。同commitの[CI build](https://github.com/Aodaruma/mcmcp/actions/runs/36529953026)も成功。
- `gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。製品Java 1,616件、harness 13件、admin bridge 28件、failure/errorなし。
- `tools/check-source.ps1 -SkipJava -PythonExecutable python`も成功。
- 4件の回帰を追加。slot同期不足でも開封所有を識別し、古い記録・別session・別container/typeを拒否すること、cleanup経路が完全内容検査に依存しないこと、解放後に診断原因を保持することを確認した。
- 差し替え担当が15:15～15:19 JSTに実体へ導入・Prism GUI再起動し、新session、13ツール/schema 2、input最大86,400秒とcatalog全schema一致を確認した。旧`f7871718…`backup、新hash、製品JAR 1個を照合。[導入receipt](artifacts/20260929-v2-storage-cleanup/deployment.json)

## 通常環境の再試験

同じ引数・Prism前景でinspectを一度実行。action `cc80db30-51a0-43ce-bcb7-8009424be852`は3.24275秒・65 tickで`failed / inventory_not_confirmed`となった。`storage_progress`は`phase:terminal / awaiting_open_evidence:false / failure:storage_layout_unsupported / release_confirmed:true / release_pending:false / release_fault:false`。最終controlは`ready / game_paused:false / running_action_id:null`だった。[再試験のAction・control](artifacts/20260929-v2-storage-cleanup/retest.json)

この試験で終了しない問題の修正を確認した。続いて現場担当が通常UIで同じバッグを開き、作業台upgradeタブと3×3の追加slotが開いていることを確認。タブだけを閉じ、GUI閉鎖・元の選択slot 5へ戻した後に、条件が変わった同じバッグへ新規inspectを一度実行した。

action `75985008-c996-41a2-8875-011b8d49fa2e`は0.2488562秒・5 tickで`succeeded / complete:true / release_confirmed:true`となり、89件の非空slotを読み取れた。[個人物品一覧を省いた成功記録](artifacts/20260929-v2-storage-cleanup/closed-tab-success.json)。これにより、開いた作業台タブでの失敗・終了と、閉鎖後の内容取得成功の両方を確認した。移送と通常環境の粉設置は実行していない。

## 残る確認と復旧

対応収納の作業台等のupgradeタブは通常UIで閉じてからAPI操作する。任意upgradeやextra slotの操作対応を追加したものではない。別の収納構成・移送・通信遅延・24時間運転は、この短い確認だけでは網羅していない。未確定のinspectを重複送信しない。

開封応答自体がなく、遅延応答の可能性を排除できない場合や、未確定cursor/移送が残る場合に、時間経過だけで解放済みとは扱わない。必要な復旧は通常GUIの切断・再接続またはJVM再起動とする。
