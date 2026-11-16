# MCMCP

日本語版 · [English](README.md)

<img src="docs/assets/readme/mcmcp-logo.png" width="180" alt="MCMCP: ロボットとピッケル">

Minecraftでの作業を、CodexやClaude CodeなどのAIアシスタントに頼めるNeoForge MODです。自分のプレイヤーを通して、周囲の確認、移動、採掘・建築、クラフト、アイテム整理などを行います。

**[ダウンロード](https://github.com/Aodaruma/mcmcp/releases)** · **[画像付き導入ガイド](docs/MCMCP_配布用README.md)** · **[ガイドPDF](docs/MCMCP_配布用README.pdf)**

## はじめるには

**Minecraft Java Edition 26.2・NeoForge 26.2.0.59・Java 25**と、同じPCで動くMCP対応のAIクライアントが必要です。MODは自分のMinecraftに導入します。サーバー側への導入は不要です。

1. **MODを追加する** — [Releases](https://github.com/Aodaruma/mcmcp/releases)から配布ZIPをダウンロードし、中のJARをCurseForgeまたはPrism Launcherのプロフィールへ追加します。
2. **AIアシスタントを接続する** — ワールドに入り、Esc → **MCP接続設定**で設定します。選択した設定でAIクライアントを再起動してください。
3. **操作をONにして依頼する** — **MCP操作**をONにしてゲームへ戻り、まず現在の状態や持ち物の確認を頼んでみてください。

導入手順の詳細は画像付きガイドを参照してください。開発中のビルドは[GitHub Actions](https://github.com/Aodaruma/mcmcp/actions/workflows/release.yml)から取得できます。

> [!NOTE]
> 操作中は**Escで緊急停止**できます。作業が終わってもONは維持されるため、自動操作を無効にするときはOFFにしてください。マルチプレイではサーバーごとの許可確認があります。
>
> 別のアプリでAIクライアントを使う場合は、Minecraftの **F3＋P**（初期キー設定）で「フォーカス喪失時の一時停止」を無効にしてください。

## できること

公開API v2は、座標・対象・数量を指定する**17ツール**を提供します。各操作で共通の進行確認・結果取得・取消が使えます。

| 作業 | ツール |
| --- | --- |
| 状態・周囲の確認 | `agent_get_state`、`agent_get_mcp_status`、`agent_get_observation` |
| 移動・視点・採掘・建築 | `agent_look`、`agent_move`、`agent_break_block`、`agent_place_block`、`agent_build` |
| 操作・クラフト・アイテム管理 | `agent_interact`、`agent_craft`、`agent_inventory` |
| 入力・スクリプト・ゲーム内チャット | `agent_click`、`agent_input_sequence`、`agent_run_script`、`agent_chat` |
| 進行確認・取消 | `agent_get_action`、`agent_cancel_action` |

複数の操作は、実行時間や回数に上限のあるスクリプトで組み合わせられます。JSON/CSVの設計図は`agent_build`で事前確認し、区間を区切って施工できます。取消や失敗によって、すでにワールドへ反映された変更が元に戻ることはありません。

[APIガイド](docs/MCMCP_Public_API_v2_クイックガイド.md)、[設計図ガイド](docs/PUBLIC_API_V2_BLUEPRINT_TOOL.md)、[実機で確認した範囲と残る課題](docs/experiments/20261115_release_rc1.md)を参照してください。APIが受け付ける上限と、実機で検証した規模は区別しています。

**0.1系からの移行：** 公開API v2では`agent_start_action`と公開JSON Action DSLを置き換えています。MCPクライアントを再起動してツール一覧を更新し、v2のツールとスクリプトへ移行してください。

## 質問・不具合

導入で困った場合は[トラブルシューティング](docs/MCMCP_配布用README.md#5-困ったとき--トラブルシューティング)を参照してください。不具合や要望は[Issues](https://github.com/Aodaruma/mcmcp/issues)へどうぞ。

## 開発に参加するには

[CONTRIBUTING.md](CONTRIBUTING.md)から始めてください。Issueで担当と範囲を決め、自分のforkや作業用チェックアウトからPRを送れます。Codex・Claude Code共通の作業ルールは[AGENTS.md](AGENTS.md)、レビュー・マージ・公開の方針は[MAINTENANCE.md](docs/MAINTENANCE.md)にあります。

**[開発ボード](https://github.com/users/Aodaruma/projects/5)** · **[着手できるIssue](https://github.com/Aodaruma/mcmcp/issues?q=is%3Aissue+is%3Aopen+label%3Astatus%3Aready)** · **[初めての修正](https://github.com/Aodaruma/mcmcp/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)**

<details>
<summary>ビルド・技術文書・配布手順</summary>

`JAVA_HOME`にJava 25を指定して実行します。

~~~powershell
.\gradlew.bat test harnessTest adminBridgeTest verifyHarnessIsolation build
~~~

Linux/macOSでは`bash gradlew`を使います。検証用MODは本体JARから分離しています。

- [接続設定の詳細](docs/MCMCP_導入と接続ガイド.md)
- [公開API v2](docs/MCMCP_Public_API_v2_クイックガイド.md) · [ツールカタログ](docs/MCMCP_MCP_Tool_Catalog.json)
- [設計・仕様](docs/Minecraft_MCP_NeoForge_設計仕様書.md)
- [実験・検証記録](docs/experiments/)
- [配布・PDF生成](tools/release/README.md)

`v0.2.0-rc.1`のようなバージョンタグをpushすると、CIがテストとJAR・PDF・ZIPの生成を行い、非draftのReleaseを公開します。接尾辞付きのバージョンはPre-releaseです。PDF生成にTyporaは不要です。

</details>

## ライセンス

ソースコードは[MPL 2.0](LICENSE)で提供します。第三者素材の出典は[NOTICE.md](NOTICE.md)を参照してください。

Minecraft・Mojang・Microsoftの公式製品ではなく、これらの企業から承認・提携を受けたMODでもありません。
