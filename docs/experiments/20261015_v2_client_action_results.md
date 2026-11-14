# 同期Actionの失敗結果とクライアント診断の保持

2026-10-15。PowerShell公開クライアントが、仕様に沿った同期Actionの失敗・取消結果を `invalid_tool_result` として捨てる不具合を修正する。形式が正しいAction結果を返し、形式が不正なら拒否したまま限定した診断を保持する。ゲーム側JARの変更やActionの自動再送は行わない。

## 報告と原因の確度

施工担当から、18:49 JSTごろ松明後の `agent_move` が `public_tool_failed:agent_move/invalid_tool_result` となりworker65700が停止したと報告された。entry105832..105845の13設置は成功し、台帳は床104701・松明1144。alignmentは `dispatching / action_id=null` で返答が保存されていなかった。

保存された公開stateでは、プレイヤーは既に `(-579.656,80,464.058)` へ移動している。その後、施工担当がREADY・Actionなし・支持床・HP20を確認し、新しい短いsneak移動で支持床中央へ到達、在庫と記録を照合した。開発側は保存ファイルを共有読取りで確認し、通常MCP・ゲーム・worker・台帳への操作を行っていない。

`McpHttpServer.sendCompletion` は、同期のActionがfailed/cancelledで終わると、ActionのstructuredContentと同じJSONのTextContentを残して `isError:true` にする。旧 `Assert-McmcpToolResult` は `isError:true` をcode/message/recoverableのドメインエラーだけに限定し、structuredContentを拒否する。この形式不整合は模擬通信で再現できる。ただし18:49の実応答は残っていないため、今回もこの分岐だったとは断定できず、元の移動停止理由も復元できない。

## 修正後の契約

| 受信した結果 | 公開クライアントの返却と停止 |
| --- | --- |
| 通常の成功応答 | 従来どおり `ok:true/result`。Actionの成否はresult.stateを確認 |
| v2の同期failed/cancelled | 選択toolの出力schema、正確なUUID、停止状態、textとstructuredContentの同一性を確認し `ok:true/result`。終了コード0は受信・形式の成功を意味し、施工成功を意味しない |
| ドメインエラー | 従来どおり `ok:false/tool_rejected/error`。自動再送しない |
| Tool形状・schema・ID照合の不一致 | `ok:false` と従来の診断コード。限定したresponse_diagnosticを添え、結果として採用しない |
| 通信失敗・認証情報を含む返答 | 従来どおり拒否。生の通信本文や例外を出さない |

新しく許可する同期Actionエラーは、Action開始・照会・取消を呼んだ場合のv2のfailed/cancelledだけ。queued/running/succeededをisError:trueとして返すもの、異なるtext、TextContent内の重複JSONキー、不正なUUID・型・schemaを拒否する。chatのreadやbuildのpreviewをAction開始として扱わない。照会／取消のIDは要求したIDと一致する必要がある。Actionを開始し直したり、失敗から続行したりしない。

共有transportの検証関数には `ActionOutputSchema` を明示した呼出しだけがこの経路を使える。公開fallbackクライアントが選択toolのcatalogを渡す。既存評価runnerの既定経路や `RequireSuccess` の拒否条件を緩めない。

`response_diagnostic` は、検証段階・isErrorの真偽・content件数・structuredContent有無と、形式を限定した `unverified_action_id / unverified_state / unverified_failure / unverified_stop_reason` だけを持つ。`schema_validated:false` を必ず付ける。生のresult、引数、サーバー文章、HTTP本文、認証情報は保持せず、診断からpollや操作を始めない。開始時に取得済みの検証済みIDと、検証に失敗した返答内の未検証IDを混同しない。

## workerへの適用

既存の松明後整列処理は、`Public-Tool` がAction結果を返せれば、ID・stateを台帳へ保存し、resultファイルを書いてからstateを検査して停止する。このため、形式が正しいfailed/cancelledはクライアント修正だけで結果が保存される。

一方、実際に不正な返答の `ok:false` は、現workerがdiagnostic_codeだけをthrowすると詳細が失われる。凍結したworker候補では、throw前に `public-tool-failure-<UUID>.json` を書く。日時・tool・failure_kind・diagnostic_code・検証済みID（存在時）・限定診断・ドメインerrorのcode/recoverableだけを保存する。サーバーmessage、引数、token pathや値、生の返答は保存しない。未検証IDを台帳の有効Actionに昇格させず、workerは停止したままにする。

候補は通常workerのhash `f278985658086bea4dc0ef917e159cb8388e1a6005616087d230cd3372621e0c` からPublic-Toolの失敗保存だけを変更した。旧新の差分を確認し、安全な区切りで施工担当が適用する。稼働中のPowerShellプロセスには読み込んだ関数が残るため、ファイル配置だけを反映完了とせず、新しい有限workerの起動時に読込先を確認する。適用にゲームJARの交換やゲーム再起動は不要。

## 検証と制限

架空tokenと一時loopback HTTP serverを使った `test_transport.py` 全22テストが成功した。同期failed/cancelledの結果保持、再送・余分なpollなし、schema不正、text不一致・重複キー、ID不一致、巨大／任意文章の除外、直接／JSON escapeされたtokenの非露出を確認した。worker候補はPublic-Toolの関数だけを読み、mockクライアントで形式不一致・ドメインエラーの保存、正しい失敗Actionの返却の3ケースが成功した。`tools/check-source.ps1 -SkipJava -PythonExecutable python` も成功。実ゲームへは接続していない。

旧クライアントの凍結コピーに同じ新規回帰試験を当てると、同期failed/cancelled×待機指定0/10秒の4パターンすべてで結果を失うことを再現した。現在通常環境で使われるcatalogと試験用catalogの全17ツールの出力schemaはJSONとして一致することも確認した。

追加で実行した旧 `Test-McmcpEvalTrace.ps1 -SelfTest` は、固定5ツールのv1監査と17ツールのv2 catalogの不一致によりvalidケースで失敗する。元commitのファイルを別コピーに展開しても、同一のcatalog hash・surface不一致で失敗することを確認した。この修正の成功検証には使わず、通常のsource checksと別に記録する。

18:49の操作を再実行していないため、元の停止理由や実環境の再発率は未確認。保存されなかった返答は候補の導入で遡って回復できない。

## 候補の保存先

作業ルートの `artifacts/v2-client-result-20261015/candidate/` に、クライアント2ファイル、CLI入口、現行catalog、worker候補、READMEとこの記録を凍結する。`manifest.json` は各ファイルのSHA-256、元ファイルの期待hash、試験結果を持つ。ソースは `workspaces/client-result-diagnostics` の専用branch `fix/client-result-diagnostics` に保持する。

候補作成時には、通常環境が参照する `workspaces/public-api-v2/tools/mcp/McmcpClient.ps1` と `McmcpTransport.ps1` のhash不変を確認した。通常環境への適用は、次節のとおり施工担当が後から行った。凍結候補とmanifestの未導入表記は作成時点の記録を保持する。

## 通常環境への適用と6床試行 2026-10-15

施工担当が279行末の安全な区切りで、21:10 JSTにクライアント2ファイルとworkerを適用した。開発側も保存された導入記録を読み、3ファイルの現在hashが凍結候補と一致し、退避した3ファイルのhashも期待旧版と一致することを確認した。MOD R30・JVM・sessionは担当報告どおり維持され、今回の更新にゲーム再起動は含まない。

21:12 JSTの試行では、entry108291..108297の未施工6床がAction `5078d9e6-b9b3-4cb1-ae25-d8fb0e14e313` で成功した。保存Actionを再読し、6設置・15.7039381秒・next_index108297・batch_completeを確認した。担当の在庫監査は黒コンクリートの実減6、設計一致、既存記録不変を示す。保存された公開安全確認でもREADY・Actionなし・HP/food20・接地・黒支持床・次air・inventory同期一致・バッグ解放を確認した。

監査元は作業ルートの `_PLAYING/map-art-MFwtpM21wWc-3x3/construction/` にある `client-result-install-20261015.json`、`client-result-pilot-verified-20261015.json`、`client-result-pilot-safe-20261015.json`、`blueprint-108291-108297.result.json`。新worker44164で291行末まで有限施工を継続するとの報告を受けた。これは開始報告であり、その全区間の完了確認ではない。

今回の6床は通常の成功経路の確認である。実環境でfailed/cancelledや不正返答を発生させた試験は行っておらず、それらの経路は前節の隔離模擬試験で確認した範囲に留まる。元の移動停止原因も未確定のまま。開発側は記録とファイルを読んだだけで、通常ゲームへの照会や入力を行っていない。

## 後続施工の失敗結果保持 2026-10-15

後続worker44164は `construction_deadline` により停止したが、親の失敗結果、成功済み373床と失敗した子の確定5床を保存できた。担当が新規観測・全在庫で378床を照合し、再接続後に未施工6床を13.445703秒で成功させた記録も読取り確認した。23:07 JSTの確定状況は284行末の109,056床・1,188松明。内側の期限停止の切り分けと証跡は、主開発worktreeの `docs/experiments/20261013_v2_construction_deadline_triage.md` に追記した。

この施工は `agent_build(wait_timeout_ms=0)` と `agent_get_action` による非同期の結果照会である。照会結果にfailedがあっても通常のMCP envelopeは `isError:false` であり、保存されたAction JSONには元のenvelopeも含まれない。従って今回は部分失敗の結果が失われず復旧に使えた確認であって、新たに許可した同期 `isError:true` 分岐の実機試験には数えない。元の移動停止理由、今回の施工待機の原因、後の切断との因果も未特定。
