# Development tools

ここはproduction MODへ同梱しない、Stage 3/4のlocal development toolです。PowerShell 7.4以上を使います。

## MCP接続診断 / MCP connection diagnostics

通常のCodex・Claude Code接続を優先し、接続確認には[固定MCPクライアント](mcp/README.md)の `-Check` を使います。Toolが登録されていない環境向けのfallbackも同じ実装です。HTTP・JSON-RPC・Toolエラーを区別し、開始IDのないActionを待機しません。<br>
Prefer native MCP registration in Codex or Claude Code. The [fixed client](mcp/README.md) provides connection diagnostics and a fallback for unavailable tools, distinguishing transport and tool errors without polling missing Action IDs.

## Build gate runner

観測欠測の単独チェスト確認試験は [回復試験ガイド](eval/RECOVERY.md) を参照してください。製品JAR・baseline・FPS条件を記録し、欠測未発生と回復成功を分けて判定します。<br>
See the [recovery regression guide](eval/RECOVERY.md) for standalone chest inspection, JAR/baseline/FPS evidence and the distinction between an unexercised gap and witnessed recovery.

```powershell
# worldへ接続せずclosed manifestだけを検査
pwsh -File .\tools\run-build-gate.ps1 `
  .\tools\build-gates\build-runner.example.json -ValidateOnly

# 起動済みharness clientの既存routineをLLMなしで順次実行
pwsh -File .\tools\run-build-gate.ps1 `
  .\tools\build-gates\build-runner.example.json
```

manifestは`navigate_to / apply_block_plan`だけを最大17 step含められます。sampleは`移動→柱施工→移動→柱施工`の4 routineです。移動中に次の3 route cellが見えない場合、または可視なmob/playerが次cellを塞ぐ場合は、movement keyをneutralにした40 client tickの再観測windowを最大3回使います。危険block、敵対mob、被ダメージは待たずに停止します。runner自身はblock、item、経路を推測せず、各routineのserver-confirmedなterminal結果を検査します。Bearer tokenは既定で`run/harness-client/config/mcmcp/mcp-token`から読み、表示しません。

## Blueprint SVG

`capture_creative_region`を`operation=start`で開始し、返された`job_id`を`operation=status`でpollします。MCP応答には全cellを含めません。成功statusが返す相対artifact pathの`.json.gz`をSVG exporterへ渡します。

```powershell
# gzip artifactの検査
pwsh -File .\tools\export-blueprint-svg.ps1 `
  -InputPath .\run\harness-client\mcmcp\exports\creative-blueprints\<job-id>.json.gz -ValidateOnly

# Y layerごとのSVGを生成
pwsh -File .\tools\export-blueprint-svg.ps1 `
  -InputPath .\run\harness-client\mcmcp\exports\creative-blueprints\<job-id>.json.gz `
  -OutputDirectory .\blueprint-svg
```

SVG exporterは外側`mcmcp.creative-blueprint-artifact/v1`、内側`mcmcp.blueprint-palette-rle/v1`の二層artifactを検証します。airを含む完全な直方体、最大4,194,304 cell、各辺最大256、最大64 chunk column、展開後64 MiB、SHA-256形式が必須です。RLE順序は`chunk_z_x_then_y_z_x_within_clipped_chunk`、論理hash順序は`y_z_x`です。画像はXを右、Zを下に描き、完全BlockState単位のpaletteと相対/絶対Yを表示します。
