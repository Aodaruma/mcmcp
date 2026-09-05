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

✅ 実装済み / Implemented　☐ 未対応・改善中 / Pending or in progress

| 観測・管理<br>Observation & inventory | 作業・建築<br>Tasks & building | 操作・連携<br>Controls & integration |
| --- | --- | --- |
| ✅ 周囲観測<br>Surroundings | ✅ 小麦農業<br>Wheat farming | ✅ 長押し操作<br>Bounded input holds |
| ✅ 安全な移動<br>Known paths | ✅ 原木・丸石採掘<br>Supported mining | ✅ 攻撃機能（対応トラップ）<br>Combat (supported traps) |
| ✅ 視点操作<br>Camera control | ✅ 丸石製造機<br>Cobblestone generators | ✅ MCP接続・ON/OFF<br>MCP setup & control |
| ✅ 持ち物確認<br>Inventory inspection | ✅ クラフト・精錬<br>Crafting & smelting | ✅ 緊急停止<br>Emergency stop |
| ✅ チェスト棚卸し<br>Chest & barrel inspection | ✅ 小規模建築・コピー<br>Small builds & copying | ✅ マルチプレイ<br>Multiplayer support |
| ✅ アイテム出し入れ<br>Item transfers | ✅ 回路組み立て（定型）<br>Preset redstone circuits | ☐ 追加MOD互換性<br>More mod compatibility |
| ☐ 未探索エリア・段差移動<br>Exploration & full-block steps | ✅ ポーション醸造<br>Standard potion brewing | ☐ 低FPS時の安定性<br>Low-FPS stability |
| ☐ 額縁付きチェストの安定性<br>Item-frame chest reliability | ✅ 釣り<br>Fishing | ✅ Pre-release公開<br>Pre-release publication |
| ✅ 額縁の表示変更<br>Item-frame displays | ☐ 大規模建築<br>Large building jobs | |
| | ☐ 連続釣り<br>Multi-cycle fishing | |

対応する対象・操作範囲は[機能詳細](docs/MCMCP_Action_DSL_クイックガイド.md)を参照してください。<br>See the [feature guide (Japanese)](docs/MCMCP_Action_DSL_クイックガイド.md) for supported targets and limits.

## 質問・不具合 / Questions and issues

導入で困った場合は[ガイドの「困ったとき」](docs/MCMCP_配布用README.md#5-困ったとき--トラブルシューティング)を参照してください。不具合や要望は[Issues](https://github.com/Aodaruma/mcmcp/issues)へどうぞ。

For setup problems, see the guide’s [troubleshooting section (Japanese)](docs/MCMCP_配布用README.md#5-困ったとき--トラブルシューティング). Report bugs or request features in [Issues](https://github.com/Aodaruma/mcmcp/issues).

## 開発者向け / For developers

<details>
<summary>ビルド・技術文書・配布手順 / Build, technical docs, and release instructions</summary>

Java 25を`JAVA_HOME`へ設定して実行します。<br>Set `JAVA_HOME` to Java 25, then run:

```powershell
.\gradlew.bat test harnessTest adminBridgeTest verifyHarnessIsolation build
```

Linux/macOSでは`./gradlew`を使います。検証用MODは本体JARから分離しています。<br>On Linux/macOS, use `./gradlew`. Test-only mods are kept separate from the production JAR.

- [接続設定の詳細 / Connection details](docs/MCMCP_導入と接続ガイド.md)
- [Action DSLガイド / Action DSL guide](docs/MCMCP_Action_DSL_クイックガイド.md) · [実行例 / Examples](docs/action-templates/)
- [設計・仕様 / Design and specification](docs/Minecraft_MCP_NeoForge_設計仕様書.md) · [MCP Tool Catalog](docs/MCMCP_MCP_Tool_Catalog.json)
- [実験・検証記録 / Test and experiment records](docs/experiments/)
- [配布・PDF生成 / Distribution and PDF generation](tools/release/README.md)

技術文書は主に日本語です。`v0.1.0-rc3`のようなタグをpushすると、CIがそのバージョンのJAR・PDF・ZIPを作り、非draft Releaseを公開します。接尾辞付きはPre-releaseです。PDF生成にTyporaは不要です。

Technical documentation is primarily in Japanese. Pushing a tag such as `v0.1.0-rc3` builds a matching JAR, PDF, and ZIP, then publishes a non-draft Release. Tags with a prerelease suffix produce a Pre-release. PDF generation does not require Typora.

</details>

## ライセンス / License

ソースコードは[MPL 2.0](LICENSE)で提供します。第三者素材の出典は[NOTICE.md](NOTICE.md)を参照してください。<br>Source code is licensed under [MPL 2.0](LICENSE). See [NOTICE.md](NOTICE.md) for third-party credits.

Minecraft / Mojang / Microsoftの公式製品ではありません。<br>NOT AN OFFICIAL MINECRAFT MOD. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
