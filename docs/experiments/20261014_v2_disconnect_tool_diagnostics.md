# 2026-10-14 施工停止と `tool_rejected` の診断

## 結論

12:32 JSTの `public_tool_failed:agent_run_script/tool_rejected` は、PowerShellクライアントがMCPのドメインエラー応答を受け取ったことを示す。切断専用のコードではない。具体的な `error.code/message/recoverable` はクライアントから返るが、施工workerの例外化で保存されなかった。この記録だけでは切断原因・切断時刻・今回の拒否理由を確定できない。

通常ゲームへの照会・入力・再接続・交換は行っていない。施工担当の保存記録、現在のコード、架空tokenを使う一時loopbackサーバーで調査した。稼働用worker・共通クライアント・製品コードは変更していない。

## 保存記録から確認できること

- 直前のAction `981a8454-d1c3-4411-b909-c376554f13bc` は `succeeded/completed`。entry71460..71473の13設置を33.7198411秒で完了し、failureはnull。施工担当の台帳は床70,709個・松明764本。
- worker19064の最終checkpointは03:32:37.7615025Z（12:32:37 JST）。その後、松明の支持確認用 `agent_run_script` で停止した。
- 支持確認scriptはplayer・支持床・松明の `observe` と `report` だけ。成功応答を得た後に観測結果を保存し、その後で中央への移動・次施工を送る順番。今回の例外では後続の移動・次施工へ進まない。
- 03:58:51.840Z（12:58:51 JST）の保存済み公開state/statusはworld/player/sessionがnull、MCP OFF、実行中Actionなし。これは確認時点の状態であり、12:32時点の拒否原因を遡って証明しない。
- 03:00の定時再起動とは別時刻。以前の施工期限超過、今回の切断、R22bの速度改善との因果関係を示す証拠はない。

監査元は親mcmcpディレクトリの `_PLAYING/map-art-MFwtpM21wWc-3x3/construction/` にある `unexpected-disconnect-071473-20261014.json`、`blueprint-071460-071473.result.json`、`background-20261014-103947.out.log/.err.log`。

## 診断が失われる場所

`tools/mcp/McmcpClient.ps1` は検証済み `isError=true` を次の形で返す。

```json
{"ok":false,"failure_kind":"tool","diagnostic_code":"tool_rejected","error":{"code":"NO_WORLD","message":"No client world is ready","recoverable":true}}
```

これは説明用の応答例であり、今回の実応答を復元したものではない。`NO_WORLD`、`SAFETY_PRECONDITION`、`MCP_OPERATION_DISABLED`、`TASK_BUSY`、`SERVER_BUSY` などは同じ外側のラベルになる。`McmcpRuntime.commitV2Script` はworld、安全条件、他Actionの有無、READYなどを確認するが、どの条件で拒否されたかは保存されていない。

`Run-BlueprintBackground.ps1` 24～28行および `Run-BuildBatch.ps1` の `Public-Tool` は、`ok=false` の際に `diagnostic_code` だけを連結してthrowする。`.error` は捨てられ、`.observed.json` の保存にも到達しない。破棄された応答は既存のstdout/stderrから復元できない。

## 別途見つかった同期完了応答の不整合

`McpHttpServer.sendCompletion` は、同期待機中のActionが `failed/cancelled` になると、Action結果と `structuredContent` を残して `isError=true` にする。一方、`McmcpTransport.Assert-McmcpToolResult` は `isError=true` を「structuredContentなし、code/message/recoverableだけのドメインエラー」に限定する。

そのため、この形のAction結果は現在のPowerShellクライアントで **`invalid_tool_result`** となり、Action IDやfailure/resultも返らない。`tool_rejected` にはならないので、今回の保存されたエラーと同一原因とは扱わない。Action IDが記録にないことだけで、一般にAction未開始・副作用なしを推定してはいけない。

## 隔離模擬試験

既存 `tools/mcp/test_transport.py` のHTTP fixtureを使い、現在の `Invoke-Mcmcp.ps1` を4ケースで実行した。実ゲームのport/tokenは使用していない。各ケースはdiscover＋開始要求の2リクエストだけで、再送・pollなし。

| 模擬応答 | クライアント結果 | workerに残る情報 |
| --- | --- | --- |
| NO_WORLD | tool_rejected、error詳細あり | 共通ラベルのみ |
| SAFETY_PRECONDITION | tool_rejected、error詳細あり | 共通ラベルのみ |
| MCP_OPERATION_DISABLED | tool_rejected、error詳細あり | 共通ラベルのみ |
| 同期Action failed＋structuredContent | invalid_tool_result、Action結果なし | 形式エラーのラベルのみ |

4ケースすべて上記の挙動を再現。最初の試験では同期失敗もtool_rejectedになるという仮説を置いたが、検証器が先に拒否すると判明したため期待値を訂正した。同期ケースはHTTP応答形式の模擬試験であり、ゲーム中の切断再現試験ではない。

試験スクリプトと結果は親mcmcpディレクトリの `artifacts/v2-disconnect-diagnostics-20261014/check_diagnostics.py` と `results.json`。実行例：

```powershell
python artifacts/v2-disconnect-diagnostics-20261014/check_diagnostics.py workspaces/public-api-v2
```

## 改善案と担当境界

1. 施工担当の次回worker更新で、throw前に日時・tool名・工程名・entry・既知のsession/action ID・failure_kind・diagnostic_code・ドメインerrorの3項目を上限付きJSONへ保存する。保存したファイル名を例外にも含める。開始直前の工程記録と応答保存を分け、応答のない状態を判別できるようにする。token・HTTPヘッダー・生の通信例外・script全文は保存しない。
2. 同期Action結果とドメインエラーを、公開schemaを使って区別できるよう共通クライアント／検証器の契約を整理する。単に `isError` の検証を緩めず、Action ID・state・failure・resultの型、textとstructuredContentの一致、秘匿値検査を維持する。failed/cancelled、偽装・不正schema、secret、再送なしを回帰試験にする。これは製品挙動の変更を伴う別対応で、今回未実装。
3. `recoverable=true` は自動再送の許可として扱わない。正常に受け取った既知Action IDは結果照会へ使い、応答喪失時は未確認として停止する。確認済みの13設置は再投入しない。
4. 通常ゲーム復帰は施工担当と本人の接続方針に従う。復帰後の新session、実行中Action、同期、支持床・松明・次セルの確認を経て、未施工entry71473から続行する。こちらから重複した再接続依頼やゲーム操作は行わない。

今回の成果物は原因切り分けと改善案。切断原因の解消・新JARの検証完了とは報告しない。
