# MCMCP 導入・接続ガイド

<img src="assets/readme/mcmcp-logo.png" width="160" alt="MCMCPロゴ：ロボットとピッケル">

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
3. 初回は「MCP接続設定」を押し、「Codexを自動設定」または「Claude Codeを自動設定」を選ぶ。
4. 表示された変更先を確認して「確認して設定」を押し、対象のMCPクライアントを再起動する。
5. 右下の「MCP操作: OFF」をクリックし、「ON / 待機中」にする。
6. CodexまたはClaudeへ作業を依頼する。

タイトル画面、ワールド選択画面、サーバー選択画面には操作ボタンを表示しない。ワールドに入っている間は、ポーズメニュー、チャット、インベントリなどの画面にボタンを表示する。通常の一人称画面では右下の状態アイコンで稼働状態を確認できる。

作業を止める場合は、ゲーム内ボタンをもう一度押してOFFにする。実行中のAction、保持中のキー、左右クリックはすべて解除される。

自動設定では、Codexの`~/.codex/config.toml`またはClaude Codeの`~/.claude.json`を更新する。既存ファイルは初回だけ`.mcmcp.bak`へ退避する。手動設定・解釈できない設定に加え、**同じURL・portでも別Prismプロファイルのhelperを参照する登録は上書きしない**。同時起動した別ゲームとの競合も、設定ファイル横の`.mcmcp.lock`を用いた排他中に登録を再確認して防ぐ。このlockファイルは再利用するため残す。確認画面には秘密値を含まない参照診断を表示する。token自体は設定ファイルへ複製せず、MCMCPが登録するローカルheader helperが接続時にowner-onlyのtokenファイルから読み取る。

### 複数Prismプロファイル・検証専用Codex

通常プロファイルの登録を保持したまま検証する場合、**Esc → MCP接続設定 → Codexの分離設定（検証用）**を選ぶ。現在のゲームフォルダーの`config/mcmcp/codex-home/config.toml`に独立した登録を生成し、常用のCodex/Claude設定は変更しない。同じプロファイルの生成済み登録だけを更新できる。別プロファイルへの自動切替や既存登録の強制上書きは提供しない。

生成するだけではクライアントの接続先は切り替わらない。利用者が別のPowerShellウィンドウを開き、対象ゲームのパスへ置き換えて起動する。プロジェクト側の`.codex/config.toml`で同名接続を上書きしていない作業フォルダーを使う。

```powershell
$env:CODEX_HOME = 'C:\path\to\validation\minecraft\config\mcmcp\codex-home'
codex
```

これはそのシェルから起動するCodex専用の選択であり、永続ユーザー環境変数を変更しない。終了後はそのウィンドウを閉じる。常用`auth.json`・token・私有ログをコピーせず、必要なCodexログインは利用者が分離環境で行う。正式Validation受入では、この分離設定または一時CODEX_HOMEを使い、常用登録を置換しない。CODEX_HOME配下には設定だけでなく認証・履歴等も保存されるため、生成後のディレクトリーを配布・報告へ添付しない。[Codex公式の設定保存先](https://learn.chatgpt.com/docs/config-file/config-advanced#config-and-state-locations)も参照。

通常自動設定が検査するのはゲーム側の`user.home`にある既定ファイルである。別CODEX_HOME・プロジェクト設定・MSIXの仮想化されたコピーを含むクライアント全体のeffective configを自動発見したという意味ではない。分離設定を生成済みでも、実際に選択されたか不明なので初回案内は抑止しない。

### 別のアプリを開きながら操作する場合

Minecraftで **F3＋P**（初期キー設定）を押し、フォーカス喪失時の一時停止が**無効**になった表示を確認する。有効だと、MCPがチェストを閉じて通常画面に戻った直後、非アクティブなMinecraftにポーズメニューが自動で開き、次の操作が停止することがある。マルチプレイでも同様にメニューが開く。

切り替えはトグル式で、もう一度押すと戻せる。設定は保存され、MCPを使わない通常プレイにも適用される。手動Escによる緊急停止は無効にならない。すでに開いたメニューは「ゲームに戻る」で閉じてから再試行する。MCMCPはこの設定を自動変更しない。

## 4. Bearer tokenについて

MCPクライアントは`mcp-token`の内容をBearer tokenとして使用する。tokenを画面、チャット、スクリーンショット、共有ログへ貼らない。

tokenは起動ごとに変わらない。プロファイルごとの初回起動時に一度だけ生成され、`mcp-token`を削除しない限り同じ値が再利用される。通常は前節の自動設定を使えば、tokenを読んだり環境変数へ登録したりする必要はない。

以下は自動設定を使えない場合だけの手動手順である。

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

**推奨は第3節のゲーム内自動設定です。** 設定後にCodexを再起動し、`codex mcp list` またはMCP一覧で `mcmcp` の登録を確認します。ゲームと同じPC・同じユーザー環境の設定を確認してください。以下の手動登録は、自動設定を利用できない場合だけ必要です。<br>
**Prefer the in-game setup in section 3.** Restart Codex and check `codex mcp list` or its MCP server list. Use the same computer and user environment as Minecraft. Manual registration below is a fallback.

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

**推奨は第3節の「Claude Codeを自動設定」です。** 再起動後に `claude mcp list` とClaude Code内の `/mcp` で接続を確認します。自動設定では `headersHelper` がtokenを読み、tokenを設定へ貼り付ける必要はありません。<br>
**Prefer “Claude Codeを自動設定” in section 3.** Restart Claude Code, then check `claude mcp list` and `/mcp`. The generated `headersHelper` reads the token without copying it into the configuration.

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

テキストファイルの編集は不要である。許可されていないマルチプレイサーバーで「MCP操作: OFF」を押すと、現在の正確な接続先を示す警告画面が開く。サーバー規約と管理者の許可を確認して「このサーバーを記憶してON」を押すと、そのアドレスだけがローカルに保存され、次回から同じ警告は表示されない。

許可はワイルドカードや他サーバーへ広がらない。保存先は`minecraft\config\mcmcp\allowed-servers.json`であり、許可を取り消したい場合はMinecraftを終了してこのファイルを削除する。各接続sessionで、ワールドに入った後にゲーム内ボタンを押してONにする必要がある。

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
- `401 Unauthorized`: 認証拒否は正常な保護動作。まず下表でhelperの参照プロファイル・古い設定を確認し、対象設定だけを復旧してMCPクライアントを再起動する。認証を無効化しない。
- タイトル画面にボタンがない: 正常。ワールドへ入ってから`Esc`を押す。
- 初回案内が出ない: Prism LauncherだけでなくMinecraftのワールドへ入り、`Esc`メニューの「MCP接続設定」を確認する。
- ボタンが押せない: world/playerの準備、死亡画面、multiplayer設定、allowlistを確認する。
- port競合: Minecraftを終了し、`mcmcp-client.toml`の`port`とMCPクライアント側URLを同じ空きportへ変更する。
- ツールが見えない: Minecraftを先に起動してからCodex/Claude Codeを再起動し、`/mcp`を確認する。

tokenそのものをトラブル報告へ添付してはいけない。

### Windowsのプロファイル参照診断と復旧

ゲームの設定確認画面の診断は、token本文・hashを読まず、helperも実行せず、設定の参照先と生成内容だけを調べる。`configured`でも通信成功やBearer一致を保証しない。通信は[共通診断の`-Check`](../tools/mcp/README.md)で別に確認する。`-Check`もT0前またはrun terminal後に行う。

| 参照診断・通信結果 | 意味と復旧 |
| --- | --- |
| `unconfigured` | 検査対象ファイルに登録なし。通常用なら自動設定、検証用なら分離設定を選ぶ。 |
| `another_profile` | helperが別ゲームフォルダーを参照。同じURL・portでも上書きせず、分離設定を選ぶ。削除済みの旧プロファイル参照も保持する。 |
| `helper_missing` / `stale_helper` | 同じプロファイルのhelperがない／現行の生成内容と異なる。同じ対象への自動設定で再生成し、クライアントを再起動する。 |
| `endpoint_mismatch` | 同じプロファイルの登録URLとゲーム側portが異なる。意図したportを確認して同じ対象を再設定する。 |
| `unmanaged_unknown` / `existing_unmanaged_entry` | 手動設定、追加の認証指定、非標準のhelper、曖昧な管理ブロックなど。URLだけで認可せず保持する。分離設定、または利用者による対象項目だけの確認を使う。 |
| `config_unreadable` / `token_unavailable` | 形式・サイズ・アクセス権またはtokenファイルのメタデータを確認できない。本文や生の例外を共有しない。 |
| HTTP 401 (`http_non_success`) | 到達したendpointが認証を拒否。上記参照診断を先に行う。静的tokenや環境変数の古いコピーは参照診断だけでは判定不能。 |
| `http_request_failed` / `request_timeout` | endpointへ接続できない／期限切れ。ゲーム起動・endpoint有効化・portを確認。401とは区別する。 |
| `discovery_mismatch` / `jsonrpc_error` | protocol/version/method等の不一致。対応するJARとクライアントを確認し、認証やprotocol検証を緩めない。 |

MSIXの通常パスとcanonical/UNC表現が同じディレクトリーを指す場合は同一プロファイルとして扱う。一方、LocalCache等にある独立した私有コピーは、内容・URLが同じでも同一とは扱わない。ゲームから見える設定とクライアントが実際に読む設定が異なる場合は、利用者が双方の**設定ファイルの場所とhelper参照先**を確認する。私有コピーの作成原因を特定アプリの不具合と決めつけない。

以前の操作で常用登録を別プロファイルへ切り替えた場合は、バックアップを保持し、利用者が常用プロファイルのMCMCP管理ブロックだけを復旧する。設定ファイル全体の復元・削除、token削除、Bearer値の貼り付けは避ける。今回の自動設定は、その復旧を無断で代行しない。

### 接続とActionの失敗を分けて確認する / Separate connection and Action failures

接続を詳しく確認する場合は、repoに含まれる[接続診断ツール](../tools/mcp/README.md)を `-Check` で実行します。接続と固定5 Toolの登録だけを検査し、Minecraft内の操作は開始しません。通常のMCP Toolが使える場合はそちらを優先してください。<br>
Use the repository's [connection diagnostic](../tools/mcp/README.md) with `-Check` to test connectivity and the five tools without starting gameplay. Prefer normal MCP tools whenever available.

接続拒否・HTTP 401・protocol不一致と、Toolの `isError:true` は別の失敗です。後者はMCPまで到達したうえで、操作許可や観測などの条件によって拒否されています。開始が成功して有効な `action_id` を受け取った場合だけ、そのIDで待機します。IDがない失敗応答を空文字や仮のIDで繰り返し照会しないでください。<br>
Connection/HTTP/protocol failures differ from a Tool's `isError:true`: the latter reached MCP but was rejected by its operation checks. Only poll a valid ID returned by a successful start. Never substitute an empty or guessed ID.

通常のMCP登録がない環境で使うfallbackは上記の固定ツールを利用します。protocol情報・UTF-8・timeout・結果検証は評価runnerと共通です。送信済み操作の応答を受け取れなかった場合、成功とも失敗とも断定せず、移動・移送などを自動再送しません。<br>
When normal MCP registration is unavailable, use the fixed fallback. It shares protocol, UTF-8, timeout, and result validation with the evaluator. A lost response leaves the mutation outcome uncertain; do not automatically resend it.
