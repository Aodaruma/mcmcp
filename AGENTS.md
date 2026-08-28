# Repository guardrails

- 文書とユーザー向け表示は日本語を基本とする。
- 対象をMinecraft 26.2、NeoForge 26.2.0.59、Java 25へ固定し、互換性確認なしに更新しない。
- `docs/Minecraft_MCP_NeoForge_設計仕様書.md`と`docs/MCMCP_MCP_Tool_Catalog.json`を規範とする。Tool catalogを公開surfaceの正本とし、runtime、`tools/list`、schema test、固定hashを常に同期する。
- Action DSLは任意の複数nodeを組める閉じた文法のまま保ち、`agent_start_action`の公開descriptionだけで必須node `id`、正規opcode・field・capability・座標形式とschema-validな最小例を発見できるようにする。固定action化、非公開alias、評価promptへの文法追記で補わない。
- Tool catalogでrequiredかつnullableなfieldは、値がない場合もHTTP `structuredContent`へ明示的な`null`として残し、transport serializationで省略しない。
- Tool入力schema違反はcatalog validatorが見つけた先頭のpathと固定reasonだけを短く返し、小さなprimitive enumに限り件数・値長・総長を制限したcatalog由来の許可値を示してよい。入力値や未知property名を応答へ反射せず、診断用にAction DSL文法を手書きで二重管理しない。
- MCP serverとgameplay runtimeはMOD内で完結するphysical client専用とし、Minecraft server側MOD、capability handshake、独自payload、server側の許可・capability確認を要求しない。
- MCPはMinecraft JVM内で127.0.0.1だけにbindし、Origin検証とBearer認証を無効化しない。
- LLMへraw key、raw mouse、任意packet、任意command、任意コード実行を公開しない。
- 全周visual、Local Observation Volume、sound clueの許可範囲外にあるhidden world stateをruntimeへ渡さない。
- Actionは期限・静的budget・停止条件・Esc緊急停止・監査traceを持つ。
- test fixtureはdev-onlyの分離JARとし、command実行能力をsingleplayer、integrated server、loopback認証、固定test profileだけへ限定する。release JARへ含めず、MCMCPを自動armしない。
- fixtureの再準備は冪等にし、前回runのcontainer内容物やworkspace内の落下itemを開始inventoryへ混入させない。block置換は作物等のitem entityを同期生成し得るため、combined wheatはlayout・container設定後にもworkspaceを最終purgeする。評価T0前に空inventory・所定のchest内容・落下itemなしを確認する。
- 元の「くらふとぶ！-v01.2」instanceを変更せず、NeoForgeを使うPrism Launcher profile `MCMCP-Validation`の1つだけを永続的に使い回す。
- 実装phaseごとにbuild/testを通し、独立したcommitとしてmainへpushする。
- 既存の安全境界、入力検証、エラー処理、fixture isolationを簡略化しない。

## Production評価

- 完成目標は、画面・座標・過去の操作contextをLLMへ渡さず、公開MCP Toolだけで課題を完遂するMCP-only運用とする。`computer-use`はT0前のMinecraft起動、対象worldへのlogin、MCPの手動ONだけに使用できる。
- fresh評価のproduction promptは「チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか」とbyte-for-byte同一にし、prefix、suffix、追加contextを付けない。
- T0後からrun終了まではoperatorによる画面観測、Minecraft/MCP操作、追加入力を禁止し、モデルのMCMCP Tool callだけを機械的に転送する。
- direct MCP bridgeは、effective configでMCMCPが未登録（`config.mcp_servers`が0件）と確認できた評価runだけのfallbackとし、production runtimeや通常接続方式にしない。
- 評価bridgeはMCMCPのrate limit（20 requests/s、burst 40）を越えないよう全requestを単調時計でpacingし、HTTP 429を一般transport障害と混同しない。失敗artifactには固定分類・診断code・HTTP statusだけを残し、response bodyや例外messageを保存しない。
- fresh評価で同じschema値・入力上限・再取得手順の推測失敗が反復した場合、評価promptへ答えを足さず、公開Tool descriptionとcatalog由来の非反射診断だけで正解を簡潔に発見できるよう改善する。
- fresh MCP-only評価は`docs/experiments/MCMCP_fresh_MCP-only_評価protocol.md`、小麦実験の経緯と判定は`docs/experiments/02_wheet/`を参照し、ディレクトリ名の`wheet`を互換性なく変更しない。
- 実worldを変更する前に対象save、baseline/復元識別子、player・inventory・world・fixture状態を確認して保存する。変更後は全Actionのterminal状態、安全なcontrol/fixture値、world・inventoryの事後条件を確認し、save、log、監査artifactを保全してから終了する。
