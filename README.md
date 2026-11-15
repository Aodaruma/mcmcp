# MCMCP

<img src="docs/assets/readme/mcmcp-logo.png" width="180" alt="MCMCP: a robot with a pickaxe / ロボットとピッケル">

Minecraftでの作業を、CodexやClaude CodeなどのAIに頼めるNeoForge MODです。自分のプレイヤーを通して、周囲の確認、移動、アイテム整理、農作業などを行います。

A NeoForge mod that lets you ask AI assistants such as Codex and Claude Code to help in Minecraft. Your player can inspect the surroundings, move, organize items, and work on crops.

**[画像付き導入ガイド / Setup guide](docs/MCMCP_配布用README.md)** · **[PDF](docs/MCMCP_配布用README.pdf)** · **[Releases](https://github.com/Aodaruma/mcmcp/releases)**

## はじめるには / Getting started

- **Minecraft Java Edition 26.2 / NeoForge 26.2.0.59 / Java 25**
- 同じPCで使うMCP対応クライアント（Codex・Claude Codeなど）<br>An MCP client running on the same PC, such as Codex or Claude Code.
- MODは自分のMinecraftに導入します。サーバー側への導入は不要です。<br>Install the mod in your Minecraft client. No server-side installation is needed.

1. **MODを追加する** — CurseForgeまたはPrism Launcherの使用中のプロフィールへJARを追加します。<br>**Add the mod** — Add the JAR to your existing CurseForge or Prism Launcher profile.
2. **AIクライアントを接続する** — ワールドに入り、Esc →「MCP接続設定」で設定し、AIクライアントを再起動します。<br>**Connect your AI client** — Enter a world, press Esc, open “MCP接続設定” (MCP connection settings), then restart your AI client after setup.
3. **操作をONにして依頼する** —「MCP操作」をONにしてゲームへ戻り、まず現在の状態や持ち物の確認を頼みます。<br>**Enable control and ask for help** — Turn on “MCP操作” (MCP control), return to the game, and start by asking about your status or inventory.

導入ガイドは日本語です。配布用ZIP・JAR・PDFは[Releases](https://github.com/Aodaruma/mcmcp/releases)、開発中のビルドは[GitHub Actions](https://github.com/Aodaruma/mcmcp/actions/workflows/release.yml)を参照してください。

The illustrated guide is currently in Japanese. See [Releases](https://github.com/Aodaruma/mcmcp/releases) for distribution ZIPs, JARs, and PDFs, or [GitHub Actions](https://github.com/Aodaruma/mcmcp/actions/workflows/release.yml) for development builds.

> [!NOTE]
> 操作中は**Escで緊急停止**できます。作業が終わってもONは維持されるため、自動操作を無効にするときはOFFにしてください。マルチプレイではサーバーごとの許可確認があります。<br>Press **Esc to stop an active action**. Control stays ON between actions; switch it OFF to disable automation. Multiplayer control requires confirmation for each server.
>
> 別のアプリを開きながら使う場合は、Minecraftの **F3＋P**（初期キー設定）で「フォーカス喪失時の一時停止」を無効にしてください。<br>When using another app alongside Minecraft, press **F3+P** (default binding) to disable “pause on lost focus.”

## 対応状況 / Status

公開API v2は、座標・対象・数量を指定する17ツールを提供します。[ツールと操作例](docs/MCMCP_Public_API_v2_クイックガイド.md)、[設計図施工](docs/PUBLIC_API_V2_BLUEPRINT_TOOL.md)、[実機検証と残件](docs/PUBLIC_API_V2_STATUS_20260928.md)を参照してください。

API v2 provides 17 tools for coordinates, targets, and quantities. See the [API guide](docs/MCMCP_Public_API_v2_クイックガイド.md) and [verification status](docs/PUBLIC_API_V2_STATUS_20260928.md).

| 操作 / Operation | ツール / Tools |
| --- | --- |
| 状態・観測 / State and observation | `agent_get_state`、`agent_get_mcp_status`、`agent_get_observation` |
| 移動・視点・施工 / Movement and building | `agent_look`、`agent_move`、`agent_break_block`、`agent_place_block`、`agent_build` |
| 操作・生産・所持品 / Interaction and inventory | `agent_interact`、`agent_craft`、`agent_inventory` |
| 入力・script・chat / Input, scripts and chat | `agent_click`、`agent_input_sequence`、`agent_run_script`、`agent_chat` |
| 進行・取消 / Progress and cancellation | `agent_get_action`、`agent_cancel_action` |

**0.1系からの移行：** 旧`agent_start_action`とJSON Action DSLは削除されています。MCPクライアントを再起動し、v2ガイドへ移行してください。<br>**Upgrading from 0.1:** restart your MCP client and migrate from the removed `agent_start_action` / JSON Action DSL to the v2 tools.

設計図は`agent_build`でpreviewし、hashとworld/sessionを確認して有限の区間を施工できます。取消・失敗は確定済みの変更を巻き戻しません。実機で確認した規模と、APIが受け付ける上限は区別してください。

## 質問・不具合 / Questions and issues

導入で困った場合は[ガイドの「困ったとき」](docs/MCMCP_配布用README.md#5-困ったとき--トラブルシューティング)を参照してください。不具合や要望は[Issues](https://github.com/Aodaruma/mcmcp/issues)へどうぞ。

For setup problems, see the guide’s [troubleshooting section (Japanese)](docs/MCMCP_配布用README.md#5-困ったとき--トラブルシューティング). Report bugs or request features in [Issues](https://github.com/Aodaruma/mcmcp/issues).

## 開発者向け / For developers

修正への参加は[CONTRIBUTING.md](CONTRIBUTING.md)からどうぞ。Issueで担当と範囲を決め、fork / cloneした環境からPRを送れます。Codex・Claude Code共通の作業ルールは[AGENTS.md](AGENTS.md)、保守・マージ方針は[MAINTENANCE.md](docs/MAINTENANCE.md)にあります。<br>Start with [CONTRIBUTING.md](CONTRIBUTING.md): coordinate on an issue, work in your own fork / clone, and submit a PR. [AGENTS.md](AGENTS.md) applies to Codex and Claude Code; see [MAINTENANCE.md](docs/MAINTENANCE.md) for triage and merge rules.

**[開発ボード / Project](https://github.com/users/Aodaruma/projects/5)** · **[着手できるIssue / Ready issues](https://github.com/Aodaruma/mcmcp/issues?q=is%3Aissue+is%3Aopen+label%3Astatus%3Aready)** · **[初めての修正 / Good first issues](https://github.com/Aodaruma/mcmcp/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)**

<details>
<summary>ビルド・技術文書・配布手順 / Build, technical docs, and release instructions</summary>

Java 25を`JAVA_HOME`へ設定して実行します。<br>Set `JAVA_HOME` to Java 25, then run:

```powershell
.\gradlew.bat test harnessTest adminBridgeTest verifyHarnessIsolation build
```

Linux/macOSでは`bash gradlew`を使います。検証用MODは本体JARから分離しています。<br>On Linux/macOS, use `bash gradlew`. Test-only mods are kept separate from the production JAR.

- [接続設定の詳細 / Connection details](docs/MCMCP_導入と接続ガイド.md)
- [公開API v2 / Public API v2](docs/MCMCP_Public_API_v2_クイックガイド.md) · [Tool Catalog](docs/MCMCP_MCP_Tool_Catalog.json)
- [設計・仕様 / Design and specification](docs/Minecraft_MCP_NeoForge_設計仕様書.md) · [MCP Tool Catalog](docs/MCMCP_MCP_Tool_Catalog.json)
- [実験・検証記録 / Test and experiment records](docs/experiments/)
- [配布・PDF生成 / Distribution and PDF generation](tools/release/README.md)

技術文書は主に日本語です。`v0.1.0-rc3`のようなタグをpushすると、CIがそのバージョンのJAR・PDF・ZIPを作り、非draft Releaseを公開します。接尾辞付きはPre-releaseです。PDF生成にTyporaは不要です。

Technical documentation is primarily in Japanese. Pushing a tag such as `v0.1.0-rc3` builds a matching JAR, PDF, and ZIP, then publishes a non-draft Release. Tags with a prerelease suffix produce a Pre-release. PDF generation does not require Typora.

</details>

## ライセンス / License

ソースコードは[MPL 2.0](LICENSE)で提供します。第三者素材の出典は[NOTICE.md](NOTICE.md)を参照してください。<br>Source code is licensed under [MPL 2.0](LICENSE). See [NOTICE.md](NOTICE.md) for third-party credits.

Minecraft / Mojang / Microsoftの公式製品ではありません。<br>NOT AN OFFICIAL MINECRAFT MOD. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
