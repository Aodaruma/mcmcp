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

導入ガイドは日本語です。GitHub Releaseはまだ未公開で、公開前のビルドは[GitHub Actions](https://github.com/Aodaruma/mcmcp/actions/workflows/release.yml)から取得できます。

The illustrated guide is currently in Japanese. No GitHub Release has been published yet; development builds are available from [GitHub Actions](https://github.com/Aodaruma/mcmcp/actions/workflows/release.yml).

> [!NOTE]
> 操作中は**Escで緊急停止**できます。作業が終わってもONは維持されるため、自動操作を無効にするときはOFFにしてください。マルチプレイではサーバーごとの許可確認があります。<br>Press **Esc to stop an active action**. Control stays ON between actions; switch it OFF to disable automation. Multiplayer control requires confirmation for each server.
>
> 別のアプリを開きながら使う場合は、Minecraftの **F3＋P**（初期キー設定）で「フォーカス喪失時の一時停止」を無効にしてください。<br>When using another app alongside Minecraft, press **F3+P** (default binding) to disable “pause on lost focus.”

## 対応状況 / Status

チェック済みは記載した範囲で実装済み、未チェックは今後の対応・確認事項です。<br>Checked items are implemented within the stated scope. Unchecked items remain to be added, improved, or verified.

- [x] 周囲のブロック・生き物・落下アイテム・音や、自分の持ち物を確認する。<br>Inspect nearby blocks, entities, dropped items, sounds, and your inventory.
- [x] 観測済みの安全な経路を移動し、対象へ視点を合わせる。<br>Follow observed safe routes and look toward a target.
- [x] チェスト・樽の中身を調べ、アイテムを1スタックずつ出し入れする。<br>Inspect chests and barrels, and transfer items one stack at a time.
- [x] 小麦畑を耕す・種をまく・成熟を待つ・収穫する・落下物を拾う。<br>Till soil, sow wheat, wait for growth, harvest, and collect drops.
- [x] 対応する原木・丸石を採掘し、既設の丸石製造機を時間・回数を決めて使う。<br>Mine supported logs and cobblestone, and run an existing cobblestone generator with time and cycle limits.
- [x] 対応レシピをクラフトし、かまど・溶鉱炉・燻製器で精錬する。<br>Craft supported recipes and smelt items in furnaces, blast furnaces, or smokers.
- [x] 対応ブロックを少数ずつ設置・撤去・回転コピーし、定型のレッドストーン回路を作る。<br>Place, remove, or copy small groups of supported blocks with rotation, and build predefined redstone circuits.
- [x] 対応する標準ポーションを1段階ずつ醸造する。<br>Brew supported standard potions one recipe step at a time.
- [x] 釣り竿を投げ、アタリを待って引き上げる。<br>Cast a fishing rod, wait for a bite, and reel it in.
- [x] 対象と時間を決めて、攻撃・使用などの長押し操作を行う。<br>Hold supported inputs, such as attack or use, with a specified target and duration.
- [x] 利用者が許可した対応トラップで、回数・時間を限定して攻撃する。<br>Attack in supported, user-approved mob traps with attack and time limits.
- [x] ゲーム内で接続設定・ON/OFF・緊急停止・マルチプレイ許可を操作する。<br>Manage connection settings, ON/OFF control, emergency stops, and multiplayer permission in-game.
- [ ] 最初の配布ZIPをGitHub Releaseで公開する。<br>Publish the first distribution ZIP on GitHub Releases.
- [ ] 額縁付きチェストへのアクセスを安定させ、低FPS時の動作確認を続ける。<br>Improve access to chests with item frames and continue testing at low frame rates.
- [ ] 未探索の場所の探索や、1ブロックの段差を含む経路への対応を広げる。<br>Expand exploration and navigation, including routes with full-block steps.
- [ ] 大きな建築を、移動・材料管理・仮設ブロックの撤去まで通して進める。<br>Support larger building jobs with movement, material management, and temporary-block cleanup.
- [ ] 未対応MODのブロックや作業画面への対応を増やす。<br>Add support for more modded blocks and workstation interfaces.
- [ ] 複数回の釣りを、終了時の回収まで含めて一括実行する。<br>Run multiple fishing cycles as one job, including cleanup when it ends.

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
