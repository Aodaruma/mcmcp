# MCMCP

Client-only NeoForge mod with an embedded MCP server for bounded Minecraft automation.

MCMCPは、Minecraftのローカルプレイヤーを、型付きAction DSLとクライアント側の安全制御を通して操作するNeoForge MODです。MCP serverをMODと同じMinecraft JVM内で動作させ、Minecraft server側のplugin、capability確認、独自通信を必要としません。

## Target

- Minecraft 26.2
- NeoForge 26.2.0.59
- Java 25
- Prism Launcher「くらふとぶ！-v01.2」

## Documents

- [設計・仕様書](docs/Minecraft_MCP_NeoForge_設計仕様書.md)
- [MCP Tool Catalog](docs/MCMCP_MCP_Tool_Catalog.json)
- [Prism互換試験ベースライン](docs/MCMCP_Prism_互換試験ベースライン.json)

現在は設計完了・実装着手前です。規範的なTool Schemaと受入条件は上記文書を参照してください。

## Identity

- Repository: `mcmcp`
- Display name: `MCMCP NeoForge`
- Mod ID: `mcmcp`
- Java package: `dev.aod.mcmcp`
- Artifact: `mcmcp-neoforge-26.2-<version>.jar`

## Disclaimer

NOT AN OFFICIAL MINECRAFT MOD. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
