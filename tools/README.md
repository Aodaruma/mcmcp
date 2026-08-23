# Development tools

ここはproduction MODへ同梱しない、Stage 3/4のlocal development toolです。PowerShell 7.4以上を使います。

## Build gate runner

```powershell
# worldへ接続せずclosed manifestだけを検査
pwsh -File .\tools\run-build-gate.ps1 `
  .\tools\build-gates\build-runner.example.json -ValidateOnly

# 起動済みharness clientの既存routineをLLMなしで順次実行
pwsh -File .\tools\run-build-gate.ps1 `
  .\tools\build-gates\build-runner.example.json
```

manifestは`navigate_to / apply_block_plan`だけを最大17 step含められます。runner自身はblock、item、経路を推測せず、各routineのserver-confirmedなterminal結果を検査します。Bearer tokenは既定で`run/harness-client/config/craftagent/bearer.token`から読み、表示しません。

## Blueprint SVG

`capture_creative_region`を`operation=start`で開始し、返された`job_id`を`operation=status`でpollします。MCP応答には全cellを含めません。成功statusが返す相対artifact pathの`.json.gz`をSVG exporterへ渡します。

```powershell
# gzip artifactの検査
pwsh -File .\tools\export-blueprint-svg.ps1 `
  -InputPath .\run\harness-client\craftagent\exports\creative-blueprints\<job-id>.json.gz -ValidateOnly

# Y layerごとのSVGを生成
pwsh -File .\tools\export-blueprint-svg.ps1 `
  -InputPath .\run\harness-client\craftagent\exports\creative-blueprints\<job-id>.json.gz `
  -OutputDirectory .\blueprint-svg
```

SVG exporterは外側`craftagent.creative-blueprint-artifact/v1`、内側`craftagent.blueprint-palette-rle/v1`の二層artifactを検証します。airを含む完全な直方体、最大4,194,304 cell、各辺最大256、最大64 chunk column、展開後64 MiB、SHA-256形式が必須です。RLE順序は`chunk_z_x_then_y_z_x_within_clipped_chunk`、論理hash順序は`y_z_x`です。画像はXを右、Zを下に描き、完全BlockState単位のpaletteと相対/絶対Yを表示します。
