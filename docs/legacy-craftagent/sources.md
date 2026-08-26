# 参照資料

確認日: 2026-08-20。技術判断は一次資料と実環境を優先しています。Minecraft Wikiはgameplay mechanicsとvanilla手段の参考に使い、最終的な26.2挙動は複製instanceで再検証します。

## MCP

- [MCP specification 2025-11-25: Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [MCP Java SDK releases](https://github.com/modelcontextprotocol/java-sdk/releases)
- [MCP Java SDK 2.0 migration guide](https://github.com/modelcontextprotocol/java-sdk/blob/main/MIGRATION-2.0.md)
- [MCP Java SDK: Server](https://java.sdk.modelcontextprotocol.io/latest/server/)
- [MCP Java SDK source](https://github.com/modelcontextprotocol/java-sdk)
- [MCP 2026-07-28 release announcement](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/blog/content/posts/2026-07-28-spec-ga/index.md)

PoCはJava SDK 2.0.0が追従するMCP 2025-11-25へ固定します。2026-07-28は公開済みですが、Java SDKと利用clientの正式対応・conformanceを確認するまで、POST-only/stateless coreや`server/discover`等の新仕様を前提にしません。

MCP 2025-11-25 Streamable HTTPでは、client messageはPOSTです。独立GET SSE streamを提供しないserverはGETへ405を返せます。PoCはserver pushへ依存せず、routine状態をtool pollingで取得します。

## NeoForge

- [NeoForge documentation](https://docs.neoforged.net/)
- [Sides](https://docs.neoforged.net/docs/concepts/sides/)
- [Events](https://docs.neoforged.net/docs/concepts/events/)
- [Key Mappings](https://docs.neoforged.net/docs/misc/keymappings/)
- [Jar-in-Jar](https://docs.neoforged.net/toolchain/docs/dependencies/jarinjar/)
- [ModDevGradle test metadata / displayTest](https://github.com/neoforged/ModDevGradle/blob/main/testproject/src/main/resources/META-INF/neoforge.mods.toml)

NeoForge documentationにはMinecraft 1.21系のversion表示が残るpageがあります。実装時は26.2 MDK、mapping、実際のNeoForge APIを正とし、古いsampleをそのままcopyしません。

## Minecraft mechanics

- [Iron Golem farming](https://minecraft.wiki/w/Tutorial%3AIron_Golem_farming)
- [Water-powered boat transportation](https://minecraft.wiki/w/Tutorial%3AWater-powered_boat_transportation)
- [Minecart tutorial](https://minecraft.wiki/w/Tutorial%3AMinecarts)
- [Tips and tricks: light and darkness](https://minecraft.wiki/w/Tutorial%3ATips_and_tricks)

これらは、iron farmがvillager、spawn surface、golem排出、chunk load等の複合条件を持つことと、boat/minecart/waterがvanillaのEntity搬送手段であることの参考です。万能搬送の根拠にはせず、Entity種別、server lag、追加MOD、26.2の挙動を実機testします。

## Simple Voice Chat

- [Official repository](https://github.com/henkelmax/simple-voice-chat)
- [Client configuration](https://modrepo.de/minecraft/voicechat/wiki/client_config)
- [Key bindings](https://modrepo.de/minecraft/voicechat/wiki/key_bindings)
- [VoicechatClientApi, branch 26.2](https://github.com/henkelmax/simple-voice-chat/blob/26.2/api/src/main/java/de/maxhenkel/voicechat/api/VoicechatClientApi.java)
- [ClientPlayerStateManager, branch 26.2](https://github.com/henkelmax/simple-voice-chat/blob/26.2/common-client/src/main/java/de/maxhenkel/voicechat/voice/client/ClientPlayerStateManager.java)
- [KeyEvents, branch 26.2](https://github.com/henkelmax/simple-voice-chat/blob/26.2/common-client/src/main/java/de/maxhenkel/voicechat/voice/client/KeyEvents.java)

## 設計review補助

- [Ponytail](https://github.com/DietrichGebert/ponytail)

Ponytailは実行依存ではなく、必要性、既存機能、標準機能の順に小さい実装を選ぶreview観点として扱います。安全、観測出所、postcondition、failureは簡略化対象外です。

## Local実環境

次をread-onlyで確認しました。個人path、account、server address、音声device名はrepositoryへ保存していません。

- Prism Launcher instance metadataとCurse manifest
- `mods`内のJAR filename
- `latest.log` / `debug.log`の起動・接続・Voice Chat状態
- Simple Voice Chat 2.6.22 JARの公開class signature
- Java runtime version
