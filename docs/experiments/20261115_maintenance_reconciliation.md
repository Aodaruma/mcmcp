# 2026-11-15 保守・未コミット修正の整理

## 対象と判断

利用者から、現状調査、修正別のcommit/push、Issue確認、条件を満たす場合のRelease、完了worktree整理の指示を受けた。過去の「追加指示待ち」は今回の依頼を踏まえて扱い、通常ゲーム・profile・稼働workerは操作していない。

開始時のmainは `f7d4bf4`、origin/mainは `4335393`。mainをfast-forwardして一致させた。開発本体は `feat/public-api-v2` の `c317ecd` で、mainから150commit先、未コミット118ファイルだった。`perf/torch-flow` の114ファイルは同じ製品ソースの重複で、固有の製品修正はなかった。

元の未コミット変更はファイル別hashとpatchを付けてローカル退避した。各試験時に凍結されたソースをGit indexへ順に反映し、作業ファイルを巻き戻さず、次の単位に分けて履歴を保存した。

| commit | 内容 |
| --- | --- |
| `22281de` | R19 緊急回復・有限飛行・帰還・反撃後の施工 |
| `414139e` | R20 観測済み支持床への復帰・占有中の待機 |
| `971cfbf` | R21 松明支持面の実観測付き再照準 |
| `663285e` | R22 床施工の重複移動・camera処理の削減 |
| `0e6a446` | R23 バッグ移送後のplayer slot同期 |
| `221def6` | R24 既存の可視セルからの施工再開 |
| `fd0a81e` | R25/26を保持したR29c相当の着地観測・装備・弓盾修正 |
| `1644b97` | R30 入力期限診断と確定済み床の保持 |
| `181c41d` | workerの不変receipt保持とオフライン回帰試験 |
| `ceed6ac` | 同期failed/cancelled結果と限定診断の保持 |
| `1305d13` | 開始応答がrunningの場合も指定時間まで結果を待機 |
| `d97fa34` | 弓の通信例外後も入力と他の所有資源を解放 |

クライアント修正は別worktreeの `da49a67` / `005945b` を同じ内容で取り込んだ。READMEの補足も統合し、回帰試験の取りこぼしを解消した。workerの通常環境への追加導入は行っていない。

## 追加で確認・修正した不具合

- クライアントが開始応答の `queued` だけを待機し、同期開始の期限後に返る `running` を即返していた。同じ検証済みIDの照会へ進む1行修正と、修正前FAIL・修正後PASSの回帰試験を追加した。開始操作は再送しない。
- [Issue #90](https://github.com/Aodaruma/mcmcp/issues/90): 弓取消のpacket送信例外が入力中和と残りcleanupを飛ばしていた。取消・発射の例外後もローカル停止、slot復元、shield/view/selection解放を続ける。未確認取消は所有者間で保持し、失敗した発射を再送しない。独立レビュー後に修正し、RuntimeException/LinkageError、最初・次の送信失敗、local停止失敗を注入した。

## 検証

- 整理前のR30相当snapshot: `tools/check-source.ps1` 成功。Java 1,936件（通常1,894 / harness14 / admin28）、失敗・error・skip 0。transport、設計図変換、worker JSON、施工checkpoint、capability mock、v2 smoke mockも成功。
- クライアント追加修正: transport 23/23、待機関連5件、`check-source.ps1 -SkipJava` 成功。
- 弓修正: focused 34件成功。新しい候補JARの実機確認は未実施。
- 弓修正後のJava全体: 1,940件（通常1,898 / harness14 / admin28）、失敗・error・skip 0。`test harnessTest adminBridgeTest verifyHarnessIsolation build` 成功。
- 配布ツール: Python 3/3成功。最終ソース全体とGitHub CIの結果はPR #85へ対象SHAとともに記録する。

これらのソース試験はMinecraft実機の代用にしない。過去の実機記録は対象snapshot/JARに限定した証拠として保持する。

## Issue・Release

[PR #85](https://github.com/Aodaruma/mcmcp/pull/85) / [Issue #84](https://github.com/Aodaruma/mcmcp/issues/84) は継続する。[Issue #91](https://github.com/Aodaruma/mcmcp/issues/91) に、水面・狭い岸への緊急着地の既知不合格を分離した。R27/R28の取り下げ済み試案は採用していない。広い床への着地、装備12ケース、入力期限停止・再開7ケースの成功を、狭岸条件の合格へ転用しない。

`construction_deadline` と松明後の `route_edge_changed` は原因未特定だが、安全停止・確定prefix保持・再観測後の復旧は確認されている。これらを緊急着地の既知不合格と混同しない。

旧v1候補 PR #26/#35/#80/#82/#83/#86/#87/#88/#89 は実機未確認または未実装要件が残るDraftとして維持する。作業フォルダの整理をIssue完了やRelease合格に読み替えない。

既知の不合格と新しい弓修正の実機未確認が残るため、main統合、バージョンタグ、Release公開は行わない。既存配布 `v0.1.0-rc.4` は変更せず、開発版 `0.1.0-SNAPSHOT` を維持する。

## 作業コピーの保存と整理

旧maintenance draftの5ファイルは `docs/maintenance-handoff` / `81e52b8` に保全してpushした。古い安全契約の移管案・運用方針を現行v2へそのまま統合していない。

旧miningの分担worktreeは、内容が保存済み #21 branchの過去commitと同一またはpatch同値であることを独立確認した。その他の候補branchはremoteとPRに保持し、合計16個の作業フォルダを削除した。重複したdirtyコピーは退避時から変更がないことを全ファイルhashで確認してから削除した。mainと開発本体のpublic-api-v2を保持する。元の退避・hash照合・削除記録はローカルの保守artifactへ保存した。
