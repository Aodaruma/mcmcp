# MCMCP

<img src="docs/assets/readme/mcmcp-logo.png" width="180" alt="MCMCPロゴ：ロボットとピッケル">

Client-only NeoForge mod with an embedded MCP server for bounded Minecraft automation.

MCMCPは、Minecraftのローカルプレイヤーを型付きAction DSLとクライアント側の安全制御を通して操作するNeoForge MODです。MCP serverをMODと同じMinecraft JVM内で動作させ、Minecraft server側のplugin、capability確認、独自通信を必要としません。

## Target

- Minecraft 26.2
- NeoForge 26.2.0.59
- Java 25
- Prism Launcher「くらふとぶ！-v01.2」互換

## Status

Phase 1のControl / Navigation MVPを終え、Phase 2の最初のsliceである、可視・既知の3段oak幹を型付きDSLから破壊する実ワールド木こりgateに合格しました。

- `agent_start_action`はclient threadでimmutable snapshotを取得し、期限・cancel・探索量を制限したHTTP workerでplanningした後、client threadで実行条件を再検証します。
- Actionは内部で`UNCONFIRMED`として予約され、HTTP responseの送信成功後にだけconfirmされます。未confirm中と実行直前再検証の合格前は入力を出しません。
- 溶岩、溺水、危険落下は安全状態が連続確認されるまでrecovery latchを維持し、cactus、wither rose、成長済みsweet berry bushを`contact_damage`として扱います。
- program全体に加え、repeatやreplanをまたぐ各logical primitive occurrenceにも静的cost上限を適用します。
- multiplayerは既定OFFで、ゲーム内警告を物理承認した正確な接続先だけをローカルallowlistへ記憶します。テキスト設定やサーバーへの確認通信は行いません。
- DSL例は[`docs/action-templates/`](docs/action-templates/)に置き、custom programと同じ検証経路を通します。
- `break_known_face`は宣言したblock・面・axeを直前再検証し、Vanilla prediction ACKとauthoritative airが揃った場合だけ成功・破壊数を記録します。
- `break_known_block`はcurrentな可視面の完全BlockState、tool、期待drop、絶対inventory目標を宣言し、閉じた安全表（oak/birch log＋Vanilla axe、cobblestone＋iron pickaxe）だけを有限attack leaseで破壊します。成功にはACK・authoritative air・実inventory増加がすべて必要です。
- 最初の木こりgateは地上から届く3段の既知oak幹に限定し、隠れた幹の探索、drop回収保証、植林はまだ対象外です。
- 2026-08-27、Prismの単一検証profileでAction `32a87494-4768-445a-a142-3b688566bbbb`が`SUCCEEDED`（3 blocks、73 ticks、camera 59.63°）。fixtureも`phase5.tree.gate=PASS`（柵・支持面・player位置を保持、axe damage 3）を確認しました。
- full-block 1段分の上下移動edge自動生成は未実装です。該当経路は推測せず`TARGET_UNKNOWN`または`NO_KNOWN_PATH`でfail-closedにします。

## MCP利用の要点

- `agent_get_state`の`observation.latest_frame_id`を`agent_get_observation`へ渡します。告知済みframe IDはidle 60秒、最大16件まで保持されます。
- `visible_surface`はblockごとの代表面に圧縮されます。`filter`で小麦、成熟作物、落下item、単一の整数座標範囲など必要なrecordだけへ絞れます。
- `navigate_to_known.target`には、連続値`from / to`を丸めず`traversability.navigation_target`をそのまま使います。
- 2〜8対象の農作業はmutation batch、2〜8件の現在可視dropは`collect_visible_item_batch`を優先します。新しい証拠が必要になった時点でActionを区切り、再観測します。

## Documents

- [画像付き導入ガイド](docs/MCMCP_配布用README.md) / [PDF版](docs/MCMCP_配布用README.pdf)
- [導入・Codex / Claude接続ガイド](docs/MCMCP_導入と接続ガイド.md)
- [設計・仕様書](docs/Minecraft_MCP_NeoForge_設計仕様書.md)
- [Action DSLクイックガイド](docs/MCMCP_Action_DSL_クイックガイド.md)
- [MCP Tool Catalog](docs/MCMCP_MCP_Tool_Catalog.json)
- [Prism互換試験ベースライン](docs/MCMCP_Prism_互換試験ベースライン.json)
- [実ワールド検証記録](docs/MCMCP_実ワールド検証記録.md)

## Identity

- Repository: `mcmcp`
- Display name: `MCMCP NeoForge`
- Mod ID: `mcmcp`
- Java package: `dev.aod.mcmcp`
- Artifact: `mcmcp-neoforge-26.2-<version>.jar`

## Development

Prism Launcherと同じJava 25を`JAVA_HOME`へ設定して実行します。

```powershell
.\gradlew.bat test harnessTest verifyHarnessIsolation build
```

test harnessは別JARとして生成し、singleplayerの複製検証profileだけで使用します。release用MCMCP JARへcommand fixtureを含めません。

## Disclaimer

NOT AN OFFICIAL MINECRAFT MOD. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
