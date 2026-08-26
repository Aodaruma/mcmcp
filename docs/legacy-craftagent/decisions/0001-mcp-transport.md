# ADR 0001: MCP transportをゲーム内へ埋め込む

- 状態: 採用（PoCで依存隔離と相互運用を検証）
- 日付: 2026-08-19
- 更新: 2026-08-20

## 決定

Client-only NeoForge MOD内に公式MCP Java SDKのStreamable HTTP serverを埋め込みます。外部sidecarは使いません。

PoCの固定値:

- MCP Java SDK 2.0.0
- MCP protocol 2025-11-25
- endpoint: `http://127.0.0.1:<port>/mcp`
- JSON-RPC message: HTTP POST
- 独立GET SSE stream: 提供せず405
- server-initiated notification: 必須にしない
- routine update: `get_routine` polling

MCP 2026-07-28は公開済みですが、現行Java SDK 2.0.0が追従するprotocolは2025-11-25です。SDKと利用clientの正式対応・conformanceを確認するまで、2026-07-28固有のPOST-only/stateless core、header routing、`server/discover`等を設計前提にしません。

## 理由

- Minecraft状態へのaccessをclient threadへ安全にdispatchしやすい
- Python/Node別process、IPC、追加runtime、起動順序が不要
- Minecraft停止中にMCP serverだけを維持する価値が小さい
- 公式SDKへprotocol negotiation、schema、Streamable HTTPを任せられる
- MOD JARの追加・削除だけで導入とrollbackができる
- STDIOのようにMinecraft processをMCP clientの子processとして起動せずに済む
- Minecraftの大量logとMCP messageを同じstdoutへ混在させない

## 影響

- SDKとHTTP container依存が増えるため、shade + relocateと起動試験が必要
- SDK/client/protocolの組を固定して相互運用testする
- HTTP threadからMinecraft APIを直接呼ぶことをcode reviewで禁止する
- MCP transport障害がgame client全体をcrashさせないexception boundaryが必要
- Streamable HTTPのOrigin validation、loopback bind、auth、protocol header検証を実装する
- 将来2026-07-28へ移行する際は本ADRを更新し、transport conformanceを再実行する

## 却下した案

### 外部sidecar

依存隔離には有利ですが、追加runtime、process lifecycle、IPC、設定が増えます。embedded方式で解決できない衝突が実測された場合だけ再検討します。

### STDIO

MinecraftをMCP clientがlaunchする必要があり、stdoutもMCP専用にできないため不採用です。
