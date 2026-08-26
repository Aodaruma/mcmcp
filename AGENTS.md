# Repository guardrails

- 文書とユーザー向け表示は日本語を基本とする。
- 対象をMinecraft 26.2、NeoForge 26.2.0.59、Java 25へ固定し、互換性確認なしに更新しない。
- `docs/Minecraft_MCP_NeoForge_設計仕様書.md`と`docs/MCMCP_MCP_Tool_Catalog.json`を規範とする。
- MODはphysical client専用とし、Minecraft server側MOD、capability handshake、独自payloadを要求しない。
- MCPはMinecraft JVM内で127.0.0.1だけにbindし、Origin検証とBearer認証を無効化しない。
- LLMへraw key、raw mouse、任意packet、任意command、任意コード実行を公開しない。
- 全周visual、Local Observation Volume、sound clueの許可範囲外にあるhidden world stateをruntimeへ渡さない。
- Actionは期限・静的budget・停止条件・Esc緊急停止・監査traceを持つ。
- test harnessのcommand実行能力はsingleplayer、integrated server、loopback認証、固定test profileだけへ限定し、release JARへ含めない。
- 元の「くらふとぶ！-v01.2」instanceを変更せず、永続的なMCMCP test profileを1つだけ使い回す。
- 実装phaseごとにbuild/testを通し、独立したcommitとしてmainへpushする。
- 既存の安全境界、入力検証、エラー処理、fixture isolationを簡略化しない。
