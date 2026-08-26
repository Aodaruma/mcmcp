# MCMCP

Client-only NeoForge mod with an embedded MCP server for bounded Minecraft automation.

MCMCPは、Minecraftのローカルプレイヤーを型付きAction DSLとクライアント側の安全制御を通して操作するNeoForge MODです。MCP serverをMODと同じMinecraft JVM内で動作させ、Minecraft server側のplugin、capability確認、独自通信を必要としません。

## Target

- Minecraft 26.2
- NeoForge 26.2.0.59
- Java 25
- Prism Launcher「くらふとぶ！-v01.2」互換

## Status

設計を確定し、以前の同環境向け実装から、build、通常プレイヤー入力、観測、routine、test fixtureの検証済み基盤を履歴付きで移行中です。旧identityと旧MCP interfaceは規範文書に合わせて段階的に置き換えます。

## Documents

- [設計・仕様書](docs/Minecraft_MCP_NeoForge_設計仕様書.md)
- [MCP Tool Catalog](docs/MCMCP_MCP_Tool_Catalog.json)
- [Prism互換試験ベースライン](docs/MCMCP_Prism_互換試験ベースライン.json)

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
