# MCP接続診断・fallback / Connection diagnostics and fallback

通常はゲームの **Esc → MCP接続設定 → Codex / Claude Codeを自動設定** を使い、AIクライアントを再起動してください。登録されたMCP Toolを優先します。設定と確認手順は[接続ガイド](../../docs/MCMCP_導入と接続ガイド.md)を参照してください。

Use in-game setup and restart your AI client first. Prefer its registered MCP tools. This developer utility is a fallback for an environment where those tools are unavailable; it is not an additional MCP server or a replacement for normal registration.

必要環境は **PowerShell 7.4以上** と本repositoryのcloneです。Windows PowerShell 5.1の `powershell.exe` ではなく `pwsh` を使います。追加PowerShell moduleは不要です。tokenをコマンドへ貼らず、起動中のゲームのtokenファイルのパスを指定します。

Requires **PowerShell 7.4+** and a repository clone, with no extra PowerShell modules. Pass the active game's token file path; never paste the token itself.

## 接続だけを確認 / Check the connection

repo直下で、`C:\path\to\minecraft` を実際のゲームフォルダーへ置き換えます。

```powershell
pwsh -NoProfile -File tools/mcp/Invoke-Mcmcp.ps1 `
  -TokenPath 'C:\path\to\minecraft\config\mcmcp\mcp-token' -Check
```

`-Check` は接続とcatalog記載のTool登録だけを確認します。worldの読み取り・Action開始・ONへの切替は行いません。成功時の`tool_count`はcatalogの件数です。ゲーム内の操作許可とは別の判定です。

`-Check` only discovers the server and lists tools. A reachable connection does not imply that gameplay is enabled. No Action is started and no control setting is changed.

## 固定fallbackを使う / Use the fixed fallback

通常のMCP Toolが利用できない環境に限り、次の呼び出しを使います。`-ArgumentsPath` は、公開[Tool catalog](../../docs/MCMCP_MCP_Tool_Catalog.json)に従って作成したUTF-8 JSONです。引数・座標・Actionの推測や補完は行いません。

Use this path only when normal MCP tools are unavailable. Supply exact UTF-8 JSON arguments from the public catalog; the client does not infer or fill gameplay arguments.

```powershell
# 状態を一度だけ読む / Read state once
pwsh -NoProfile -File tools/mcp/Invoke-Mcmcp.ps1 `
  -TokenPath 'C:\path\to\minecraft\config\mcmcp\mcp-token' -Tool agent_get_state

# 用意したスクリプトを一度開始し、最大60秒待つ / Run a script once and wait
pwsh -NoProfile -File tools/mcp/Invoke-Mcmcp.ps1 `
  -TokenPath 'C:\path\to\minecraft\config\mcmcp\mcp-token' `
  -Tool agent_run_script -ArgumentsPath './script.json' -WaitSeconds 60

# v2の座標移動を一度開始し、同じaction_idで完了を待つ
pwsh -NoProfile -File tools/mcp/Invoke-Mcmcp.ps1 `
  -TokenPath 'C:\path\to\minecraft\config\mcmcp\mcp-token' `
  -Tool agent_move -ArgumentsPath './move.json' -WaitSeconds 60
```

- 成功は `ok:true` とschema検証済みの `result`、失敗は `ok:false` と下表の診断を返します。プロセス終了コードはそれぞれ0・1です。`ok:true` は通信・結果形式の成功であり、Actionの完了判定は `result.state` を確認します。
- `-WaitSeconds` はAction開始成功時の有効なIDだけを待機に使います。各照会は既存の `wait_timeout_ms`（最大25秒）を使います。v2の詳細な作業結果が必要なときは、完了後に `agent_get_action` を `include_result:true` で明示的に呼びます。
- HTTP・JSON-RPC・Toolエラー、ID欠落、schema不一致では待機しません。通信失敗後に開始・移送などのmutationを自動再送しません。応答を失った操作の成否は未確認です。
- 待機期限やCtrl+Cはクライアントの待機を終えるだけで、Actionを自動cancelしません。取得済みIDがあれば `agent_get_action` で状態を確認し、停止が必要なら `agent_cancel_action` を明示的に呼びます。新しいActionを推測で開始しないでください。

Success returns `ok:true` with schema-validated `result`; failure returns `ok:false` and exits with code 1. Check `result.state` for gameplay completion. Waiting uses only the ID from a successful start and stops on any error; No mutation is replayed. A wait timeout or Ctrl+C does not cancel an Action; inspect the known ID or explicitly cancel it.

`script.json`は、例えば`{"source":"move(x=4, y=65, z=8);"}`です。旧`agent_start_action`とJSON Action DSLはv2では公開しません。

## 診断 / Diagnostics

| 診断 / Diagnostic | 次の確認 / Next check |
| --- | --- |
| `token_unavailable` | 起動中のゲームのtokenファイルと権限 / Active game's token path and permissions |
| `http_non_success`・401 | ゲームから接続設定をやり直す。token値は共有しない / Re-run in-game setup; do not share the token |
| `rate_limited`・429 | 呼び出し頻度を下げる。自動再送なし / Reduce request frequency; no automatic retry |
| `http_request_failed` / `request_timeout` | 起動状態・portを確認。送信済み操作は未確認扱い / Check the running game and port; sent mutations remain uncertain |
| `jsonrpc_error` | methodやprotocolの不一致。通信実装を都度書き換えない / Method or protocol mismatch; use the fixed transport |
| `tool_rejected` | `error.code/message/recoverable` でMCPの拒否理由を確認 / Read the server's domain error |
| `invalid_tool_arguments` / `invalid_success_schema` | catalogとJARの組合せ、JSONの必須項目・型 / Matching catalog/JAR, required fields and types |
| `action_wait_timeout` | 返された `action_id` を再照会。開始操作は再送しない / Query the returned ID; do not restart the Action |

HTTP応答本文・例外の生テキスト・Bearer値は接続診断へ出しません。JSON-RPCとTool結果検証は評価runnerと共通です。loopback限定、proxy・redirect・自動retryなし、UTF-8、35秒以下のリクエスト期限を維持します。

Connection diagnostics omit raw HTTP bodies, exception text, and the Bearer value. The evaluator and fallback share transport and result validation, with loopback-only requests, UTF-8, bounded timeouts, and no proxy, redirects, or automatic retries.

## 回帰テスト / Regression tests

```powershell
python -m unittest discover -s tools/mcp -p 'test_*.py' -v
pwsh -NoProfile -File tools/eval/Test-McmcpEvalTrace.ps1 -SelfTest
```

テストは一時loopback HTTP serverと架空tokenを使い、実Minecraftへ接続しません。`pwsh` がPATHにない場合は `MCMCP_TEST_PWSH` に実行ファイルのパスを設定します。

Tests use temporary loopback HTTP servers and fake tokens, never Minecraft. Set `MCMCP_TEST_PWSH` if `pwsh` is not on PATH.
