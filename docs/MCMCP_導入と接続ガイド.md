# MCMCP 導入・接続ガイド

この文書は、MCMCPをMinecraftへ導入し、CodexまたはClaude Codeから利用するまでの手順を説明する。MCMCPはMinecraftクライアント内で動作するため、MinecraftサーバーへのMOD導入は不要である。

## 1. 必要なもの

- Minecraft 26.2
- NeoForge 26.2.0.59
- Java 25
- MCMCPの通常配布JAR（ファイル名に`test-harness`や`fixture-admin`を含まないもの）
- Codex、Claude Code、またはStreamable HTTPに対応したMCPクライアント

## 2. MODをインストールする

Prism Launcherでは、対象インスタンスを右クリックして「編集」→「MOD」→「MODを追加」を選び、MCMCPのJARを追加する。エクスプローラーから直接配置する場合は、対象インスタンスの次のフォルダーへJARをコピーする。

```text
<Prismのインスタンス>\minecraft\mods\
```

同じ`mcmcp`の古いJARがある場合は、新しいJARと同時に読み込ませない。test harness JARは検証専用なので、通常プレイ用プロファイルへ入れない。

Minecraftを一度起動すると、次のファイルが生成される。

```text
minecraft\config\mcmcp-client.toml
minecraft\config\mcmcp\mcp-token
```

既定のMCP接続先は`http://127.0.0.1:8765/mcp`である。`127.0.0.1`だけで待ち受けるため、同じPC上のMCPクライアントからだけ接続できる。

## 3. ゲーム内で有効にする

1. Minecraftでワールドまたはサーバーへ入る。
2. `Esc`でポーズメニューを開く。
3. 右下の「MCP操作: OFF」をクリックし、「ON / 待機中」にする。
4. CodexまたはClaudeへ作業を依頼する。

タイトル画面、ワールド選択画面、サーバー選択画面には操作ボタンを表示しない。ワールドに入っている間は、ポーズメニュー、チャット、インベントリなどの画面にボタンを表示する。通常の一人称画面では右下の状態アイコンで稼働状態を確認できる。

作業を止める場合は、ゲーム内ボタンをもう一度押してOFFにする。実行中のAction、保持中のキー、左右クリックはすべて解除される。

## 4. Bearer tokenを準備する

MCPクライアントは`mcp-token`の内容をBearer tokenとして使用する。tokenを画面、チャット、スクリーンショット、共有ログへ貼らない。

Windows PowerShellでは、Prismインスタンスの実際のパスへ置き換えて次を実行する。`Get-Content`の結果は画面へ出力せず、そのままユーザー環境変数へ保存する。

```powershell
$mcmcpInstance = 'C:\path\to\PrismLauncher\instances\profile'
$mcmcpTokenPath = Join-Path $mcmcpInstance 'minecraft\config\mcmcp\mcp-token'
$mcmcpToken = (Get-Content -LiteralPath $mcmcpTokenPath -Raw).Trim()
[Environment]::SetEnvironmentVariable('MCMCP_BEARER_TOKEN', $mcmcpToken, 'User')
Remove-Variable mcmcpToken
```

設定後、CodexやClaude Codeを完全に終了して起動し直す。tokenファイルを作り直した場合も、環境変数を更新してMCPクライアントを再起動する。

ユーザー環境変数へ保存したくない場合は、PowerShellセッション内だけで次のように設定し、同じウィンドウからMCPクライアントを起動する。

```powershell
$mcmcpInstance = 'C:\path\to\PrismLauncher\instances\profile'
$mcmcpTokenPath = Join-Path $mcmcpInstance 'minecraft\config\mcmcp\mcp-token'
$env:MCMCP_BEARER_TOKEN = (Get-Content -LiteralPath $mcmcpTokenPath -Raw).Trim()
```

## 5. Codexへ接続する

Codex CLIで一度だけ次を実行する。

```powershell
codex mcp add mcmcp --url http://127.0.0.1:8765/mcp `
  --bearer-token-env-var MCMCP_BEARER_TOKEN
codex mcp list
```

手動で設定する場合は`%USERPROFILE%\.codex\config.toml`へ次を追加する。

```toml
[mcp_servers.mcmcp]
url = "http://127.0.0.1:8765/mcp"
bearer_token_env_var = "MCMCP_BEARER_TOKEN"
startup_timeout_sec = 30
tool_timeout_sec = 900
```

Codexアプリ、Codex CLI、IDE拡張は同じCodexホスト上のMCP設定を共有する。設定後はCodexを再起動し、`/mcp`またはMCP server一覧で`mcmcp`を確認する。詳しい形式は[Codex公式MCPガイド](https://developers.openai.com/codex/mcp/)を参照する。

## 6. Claude Codeへ接続する

tokenをコマンド履歴や設定ファイルへ直接書かないため、環境変数展開を使う。任意の作業フォルダーに次の`.mcp.json`を作成するか、同等の内容をユーザー設定へ登録する。

```json
{
  "mcpServers": {
    "mcmcp": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer ${MCMCP_BEARER_TOKEN}"
      }
    }
  }
}
```

CLIから登録する場合は、PowerShellでシングルクォートを維持したまま次を実行する。

```powershell
claude mcp add-json mcmcp `
  '{"type":"http","url":"http://127.0.0.1:8765/mcp","headers":{"Authorization":"Bearer ${MCMCP_BEARER_TOKEN}"}}' `
  --scope user
claude mcp list
```

Claude Codeを起動し、`/mcp`で接続状態を確認する。HTTP transport、header、環境変数展開の詳細は[Claude Code公式MCPガイド](https://code.claude.com/docs/en/mcp)を参照する。

### Claude Desktopについて

MCMCPはloopback上のBearer認証付きHTTP serverである。Claude Desktopの「設定→コネクタ」へ`127.0.0.1`を登録する方法は、クラウド側からユーザーPCのloopbackへ到達できず、MCMCPはOAuth serverでもないため利用できない。現在はClaude Codeからの直接接続を推奨する。

Claude Desktopで利用するには、将来的にローカルDXTまたはstdio-to-HTTP bridgeを別途配布する必要がある。Claude Desktopではremote MCPを`claude_desktop_config.json`へ直接書く方式も現行の公式手順ではない。詳細は[AnthropicのClaude Desktop向け案内](https://support.anthropic.com/en/articles/11503834-building-custom-integrations-via-remote-mcp-servers)を参照する。

## 7. マルチプレイで使う場合

マルチプレイは既定で無効である。利用するサーバーの規約と管理者の許可を確認したうえで、Minecraftを終了してから`minecraft\config\mcmcp-client.toml`を次のように変更する。

```toml
multiplayer_default = true
```

さらに`minecraft\config\mcmcp\allowed-servers.json`を作成し、Minecraftのサーバー一覧へ登録したアドレスをport込みで完全一致させる。

```json
{"schema_version":1,"servers":["example.org:25565"]}
```

ワイルドカード、port省略、余分なJSON propertyは使用できない。各接続sessionで、ワールドに入った後にゲーム内ボタンを押してONにする必要がある。

## 8. 最初の確認

MCPクライアントへ、まず次のように依頼する。

```text
MCMCPで現在の状態だけを確認してください。まだ行動は開始しないでください。
```

接続できたら、短く安全な作業から試す。

```text
周囲と所持品を観測し、実行可能な安全な作業候補を説明してください。
```

モデルには「MCMCPだけで操作する」「不明な対象は再観測する」「失敗時に別手段で勝手に画面操作しない」と明示すると、意図しない操作経路を避けやすい。

## 9. トラブルシューティング

- 接続拒否: Minecraftが起動中か、`mcmcp-client.toml`の`endpoint_enabled=true`とportを確認する。
- `401 Unauthorized`: 環境変数のtokenが現在の`mcp-token`と一致しているか確認し、MCPクライアントを再起動する。
- タイトル画面にボタンがない: 正常。ワールドへ入ってから`Esc`を押す。
- ボタンが押せない: world/playerの準備、死亡画面、multiplayer設定、allowlistを確認する。
- port競合: Minecraftを終了し、`mcmcp-client.toml`の`port`とMCPクライアント側URLを同じ空きportへ変更する。
- ツールが見えない: Minecraftを先に起動してからCodex/Claude Codeを再起動し、`/mcp`を確認する。

tokenそのものをトラブル報告へ添付してはいけない。
