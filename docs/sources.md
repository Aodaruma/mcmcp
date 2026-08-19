# 参照資料

確認日: 2026-08-19。技術判断は一次資料と実環境を優先しています。

## MCP

- [MCP specification: Streamable HTTP 2026-07-28](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/basic/transports/streamable-http.mdx)
- [MCP Java SDK: Server](https://java.sdk.modelcontextprotocol.io/latest/server/)
- [MCP Java SDK source](https://github.com/modelcontextprotocol/java-sdk)

## NeoForge

- [NeoForge documentation](https://docs.neoforged.net/)
- [Sides](https://docs.neoforged.net/docs/1.21.1/concepts/sides/)
- [Events](https://docs.neoforged.net/docs/1.21.11/concepts/events/)
- [Key Mappings](https://docs.neoforged.net/docs/1.21.3/misc/keymappings/)
- [Jar-in-Jar](https://docs.neoforged.net/toolchain/docs/dependencies/jarinjar/)
- [ModDevGradle test mod metadata and displayTest explanation](https://github.com/neoforged/ModDevGradle/blob/main/testproject/src/main/resources/META-INF/neoforge.mods.toml)

NeoForgeの概念ページにはMinecraft 1.21系の版表示が残るものがあります。実装時は26.2 MDKと実際のNeoForge APIを正とし、古いサンプルをそのままコピーしません。

## Simple Voice Chat

- [Official repository](https://github.com/henkelmax/simple-voice-chat)
- [Client configuration](https://modrepo.de/minecraft/voicechat/wiki/client_config)
- [Key bindings](https://modrepo.de/minecraft/voicechat/wiki/key_bindings)
- [VoicechatClientApi, branch 26.2](https://github.com/henkelmax/simple-voice-chat/blob/26.2/api/src/main/java/de/maxhenkel/voicechat/api/VoicechatClientApi.java)
- [ClientPlayerStateManager, branch 26.2](https://github.com/henkelmax/simple-voice-chat/blob/26.2/common-client/src/main/java/de/maxhenkel/voicechat/voice/client/ClientPlayerStateManager.java)
- [KeyEvents, branch 26.2](https://github.com/henkelmax/simple-voice-chat/blob/26.2/common-client/src/main/java/de/maxhenkel/voicechat/voice/client/KeyEvents.java)

## 設計レビュー補助

- [Ponytail](https://github.com/DietrichGebert/ponytail)

Ponytailは実行依存ではなく、必要性、既存機能、標準機能の順に小さい実装を選ぶレビュー観点として扱います。

## ローカル実環境

次を読み取り専用で確認しました。個人パス、アカウント、サーバーアドレス、音声デバイス名は本リポジトリへ保存していません。

- Prism Launcherのinstance metadataとCurse manifest
- `mods`内のJARファイル名
- `latest.log` / `debug.log`の起動・接続・Voice Chat状態
- Simple Voice Chat 2.6.22 JARの公開class signature
- Java runtime version
