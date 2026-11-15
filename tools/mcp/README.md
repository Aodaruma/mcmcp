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
- v2の同期応答が `isError:true` でも、失敗／取消のActionとしてcatalogの出力schemaに適合し、textとstructuredContentが一致する場合は `ok:true/result` へ保持します。`result.state=failed/cancelled` を成功扱いせず、IDと部分結果を保存して停止してください。追加poll・操作の再送は行いません。
- `-WaitSeconds` はAction開始成功時の有効なIDだけを待機に使い、開始応答が `queued` または `running` なら同じIDで結果を照会します。各照会は既存の `wait_timeout_ms`（最大25秒）を使います。v2の `agent_get_action` は既定で作業結果を含みます。`include_result:false` で進捗だけを取得できます。短い行動ツール自体も既定で最大20秒待ち、完了すれば結果を直接返します。詳細は[移動・結果返却ガイド](../../docs/PUBLIC_API_V2_MOVEMENT_RESULTS_20261011.md)を参照してください。
- HTTP・JSON-RPC・Toolエラー、ID欠落、schema不一致では待機しません。通信失敗後に開始・移送などのmutationを自動再送しません。応答を失った操作の成否は未確認です。
- `agent_get_action` の照会待機期限やCtrl+Cはクライアントの待機を終えます。一方、行動ツールの同期開始応答中に切断を検知したserverは、その開始済みjobへ取消を要求します。応答を失ったことだけでは取消完了・未実行と判断できません。取得済みIDがあれば状態を確認し、停止が必要なら `agent_cancel_action` を明示的に呼びます。新しいActionを推測で開始しないでください。

Success returns `ok:true` with schema-validated `result`; failure returns `ok:false` and exits with code 1. Check `result.state` for gameplay completion. Waiting uses only the ID from a successful start and stops on any error; no mutation is replayed. v2 `agent_get_action` includes results by default. Ending a result-query wait does not cancel the Action, but a detected disconnect during a synchronous start response requests cancellation of that started job. Response loss does not prove cancellation or non-execution; inspect the known ID or explicitly cancel it.

`script.json`は、例えば`{"source":"move(x=4, y=65, z=8);"}`です。旧`agent_start_action`とJSON Action DSLはv2では公開しません。

## 診断 / Diagnostics

| 診断 / Diagnostic | 次の確認 / Next check |
| --- | --- |
| `token_unavailable` | 起動中のゲームのtokenファイルと権限 / Active game's token path and permissions |
| `http_non_success`・401 | [プロファイル参照診断と復旧](../../docs/MCMCP_導入と接続ガイド.md#windowsのプロファイル参照診断と復旧)を先に確認。別profileは分離設定を使い、常用登録を置換しない / Check the helper's profile; use isolated config for another profile |
| `rate_limited`・429 | 呼び出し頻度を下げる。自動再送なし / Reduce request frequency; no automatic retry |
| `http_request_failed` / `request_timeout` | 起動状態・portを確認。送信済み操作は未確認扱い / Check the running game and port; sent mutations remain uncertain |
| `jsonrpc_error` | methodやprotocolの不一致。通信実装を都度書き換えない / Method or protocol mismatch; use the fixed transport |
| `discovery_mismatch` | server/versionの不一致。対応JAR・クライアントを確認 / Server or protocol version mismatch |
| `tool_rejected` | `error.code/message/recoverable` でMCPの拒否理由を確認 / Read the server's domain error |
| `invalid_tool_result` | `response_diagnostic.validation_stage` を確認。未検証ID・状態は診断用であり、成功・未実行の証拠や自動pollの許可ではない |
| `invalid_tool_arguments` / `invalid_success_schema` | catalogとJARの組合せ、JSONの必須項目・型 / Matching catalog/JAR, required fields and types |
| `action_wait_timeout` | 返された `action_id` を再照会。開始操作は再送しない / Query the returned ID; do not restart the Action |

HTTP応答本文・例外の生テキスト・Bearer値は接続診断へ出しません。JSON-RPCとTool結果検証は評価runnerと共通です。loopback限定、proxy・redirect・自動retryなし、UTF-8、35秒以下のリクエスト期限を維持します。

Tool結果や出力schemaの検証に失敗した場合は、最大約1 KiBの `response_diagnostic` に検証段階、isErrorの真偽、content件数、structuredContentの有無を保持します。UUID形式のID、既知の状態、最大128文字の識別子形式の停止理由だけを `unverified_*` として添え、`schema_validated:false` を明示します。生の返答、サーバーの任意文章、引数、認証情報は含めません。以前に検証済みの開始IDがあれば、従来どおりトップレベルの `action_id` に保持します。

呼出側が `ok:false` を例外化する場合も、先にこの診断を保存してください。`diagnostic_code` だけをthrowすると詳細は再び失われます。`tool_rejected` のドメインerrorと、結果形式が正しいAction失敗は別扱いです。共有検証関数のActionエラー対応には明示的な出力schemaが必要で、既存評価runnerの既定検証と `RequireSuccess` は変更していません。[修正・模擬試験・導入手順](../../docs/experiments/20261015_v2_client_action_results.md)を参照してください。

Connection diagnostics omit raw HTTP bodies, exception text, and the Bearer value. The evaluator and fallback share transport and result validation, with loopback-only requests, UTF-8, bounded timeouts, and no proxy, redirects, or automatic retries.

## 回帰テスト / Regression tests

```powershell
python -m unittest discover -s tools/mcp -p 'test_*.py' -v
pwsh -NoProfile -File tools/eval/Test-McmcpEvalTrace.ps1 -SelfTest
```

テストは一時loopback HTTP serverと架空tokenを使い、実Minecraftへ接続しません。`pwsh` がPATHにない場合は `MCMCP_TEST_PWSH` に実行ファイルのパスを設定します。

旧v1用 `Test-McmcpEvalTrace.ps1 -SelfTest` は固定5ツールを前提としており、現v2の17ツールcatalogでは修正前から不一致になります。公開クライアントの回帰確認には上記のPython試験と `tools/check-source.ps1 -SkipJava` を使用します。

Tests use temporary loopback HTTP servers and fake tokens, never Minecraft. Set `MCMCP_TEST_PWSH` if `pwsh` is not on PATH.

視点専用の`agent_look`も開始後の`-WaitSeconds`待機に対応する。引数例は`{"x":4.5,"y":65.5,"z":8.5}`。移動の`auto_replan`は既定true、`sneak`は既定false。詳細は[クイックガイド](../../docs/MCMCP_Public_API_v2_クイックガイド.md)を参照。

`-Tool agent_chat` でゲーム内チャットを送受信できます。`operation:read` は新着カーソル、`operation:send` は通常発言1件を扱い、送信のみ `-WaitSeconds` に対応します。[使用例と到達確認の制限](../../docs/PUBLIC_API_V2_CHAT_20261011.md)。

`-Tool agent_build` は[設計図の確認・施工](../../docs/PUBLIC_API_V2_BLUEPRINT_TOOL.md)に対応します。`operation:preview`は読み取り、`operation:execute`だけが`-WaitSeconds`で共通actionを待機します。実行にはpreviewのplan hashと現在のworld sessionが必要です。
