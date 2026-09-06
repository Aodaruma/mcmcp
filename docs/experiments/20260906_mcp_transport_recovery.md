# 接続・fallback検証（Issue #3）

## 変更内容

Luna調査で、エラー応答から取得できなかったAction IDを空のまま照会し続ける経路が見つかった。正常なMCP登録を優先し、未登録環境では毎回通信scriptを生成せず `tools/mcp/Invoke-Mcmcp.ps1` を使う。

- HTTP・JSON-RPC envelope・Tool result検証を既存評価runnerから共通ファイルへ抽出した。ゲーム用DSLや公開5 Toolは変更していない。
- requestはUTF-8 bytesを送る。loopback完全指定、proxy/redirect/自動retry禁止を維持する。HTTP・protocol・JSON-RPC・Tool・成功schemaの失敗を区別する。
- catalogのinputSchema/outputSchemaを使い、成功したqueued応答のIDだけで待機する。`AWAITING_CONSENT` はIDなしの正常応答として返す。単独get/cancelを含め、応答IDが要求IDに一致しなければ拒否する。
- Toolエラーは3 fieldのdomain errorを保持し、二重JSON内のUnicode escapeを復号した後にもBearer完全一致を検査する。生のHTTPエラー本文や例外を出力しない。
- 待機は `wait_timeout_ms` 最大25秒を使う。期限切れでは取得済みIDを返し、開始操作を再送しない。待機の中断をActionのcancelや完了と混同しない。

## 既存評価runnerの固定hash

main `f9f4d32` のcatalog自体は変更せず、既存runnerとtrace auditorに残っていた古い固定値を現行catalogに同期した。同期前はtrace自己検証のvalidケースがhash不一致で失敗した。

- catalog file SHA-256: `3f4be7fb8dc3f6e9acad9f8552fba61a6ee2c8a3fd6bbb7d4ada0a807e5c119a`
- 公開name/description/inputSchemaの意味的canonical SHA-256: `7b149a064e8e1ba3c634fa9032a9b5f6a0dcef48cd09d624b66011da9099e195`
- checkoutはLF固定。mainと本branchのcatalog file hashが一致することを確認した。hash検査を無効にしたり、実行時の値で期待値を置き換えたりしていない。

## 検証範囲

`tools/mcp/test_transport.py` の14テストは一時loopback HTTP serverと架空tokenを使う。正常な開始→完了、待機上限、元の引数、UTF-8 bytes、metadata、schema不一致、ID欠落/不一致、承認待ち、HTTP 401/403/429/500/redirect、JSON-RPC異常、boolean以外のisError、二重JSON内のsecret、既存評価wrapperの実HTTP timeoutを確認した。

Javaの `test harnessTest adminBridgeTest verifyHarnessIsolation build` は1,236 tests、failure/error 0で成功した。Java runtime・catalog・配布用画像/PDFは変更していない。

通常の登録済みMCP Toolから実ゲームの `agent_get_state` 取得にも成功し、操作がREADYであることを確認した。Action開始・ゲーム設定変更は行っていない。別のローカル検証profileのtokenによる `-Check` はHTTP 401・固定診断・exit 1で停止した。その後、既存MCP設定が参照しているprofileを確認し、同じtokenファイルを指定した `-Check` は `connection:reachable / tool_count:5 / exit 0` で成功した。token内容・既存設定・ゲームprofileのファイルは変更していない。

この結果は通信経路の検証であり、Lunaの操作能力や実ワールドのAction成功を示すものではない。観測欠測からの回復と低FPSの実機検証はIssue #4で扱う。
