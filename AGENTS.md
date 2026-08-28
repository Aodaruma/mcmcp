# Repository guardrails

- 文書とユーザー向け表示は日本語を基本とする。
- 対象をMinecraft 26.2、NeoForge 26.2.0.59、Java 25へ固定し、互換性確認なしに更新しない。
- `docs/Minecraft_MCP_NeoForge_設計仕様書.md`と`docs/MCMCP_MCP_Tool_Catalog.json`を規範とする。Tool catalogを公開surfaceの正本とし、runtime、`tools/list`、schema test、固定hashを常に同期する。
- MCP serverとgameplay runtimeはMOD内で完結するphysical client専用とし、Minecraft server側MOD、capability handshake、独自payload、server側の許可・capability確認を要求しない。
- MCPはMinecraft JVM内で127.0.0.1だけにbindし、Origin検証とBearer認証を無効化しない。
- LLMへraw key、raw mouse、任意packet、任意command、任意コード実行を公開しない。
- 全周visual、Local Observation Volume、sound clueの許可範囲外にあるhidden world stateをruntimeへ渡さない。
- Actionは期限・静的budget・停止条件・Esc緊急停止・監査traceを持つ。
- test fixtureはdev-onlyの分離JARとし、command実行能力をsingleplayer、integrated server、loopback認証、固定test profileだけへ限定する。release JARへ含めず、MCMCPを自動armしない。
- 元の「くらふとぶ！-v01.2」instanceを変更せず、NeoForgeを使うPrism Launcher profile `MCMCP-Validation`の1つだけを永続的に使い回す。
- 実装phaseごとにbuild/testを通し、独立したcommitとしてmainへpushする。
- 既存の安全境界、入力検証、エラー処理、fixture isolationを簡略化しない。

## Production評価

- 完成目標は、画面・座標・過去の操作contextをLLMへ渡さず、公開MCP Toolだけで課題を完遂するMCP-only運用とする。`computer-use`はT0前のMinecraft起動、対象worldへのlogin、MCPの手動ONだけに使用できる。
- fresh評価のproduction promptは「チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか」とbyte-for-byte同一にし、prefix、suffix、追加contextを付けない。
- T0後からrun終了まではoperatorによる画面観測、Minecraft/MCP操作、追加入力を禁止し、モデルのMCMCP Tool callだけを機械的に転送する。
- direct MCP bridgeは、effective configでMCMCPが未登録（`config.mcp_servers`が0件）と確認できた評価runだけのfallbackとし、production runtimeや通常接続方式にしない。
- fresh MCP-only評価は`docs/experiments/MCMCP_fresh_MCP-only_評価protocol.md`、小麦実験の経緯と判定は`docs/experiments/02_wheet/`を参照し、ディレクトリ名の`wheet`を互換性なく変更しない。
- 実worldを変更する前に対象save、baseline/復元識別子、player・inventory・world・fixture状態を確認して保存する。変更後は全Actionのterminal状態、安全なcontrol/fixture値、world・inventoryの事後条件を確認し、save、log、監査artifactを保全してから終了する。
