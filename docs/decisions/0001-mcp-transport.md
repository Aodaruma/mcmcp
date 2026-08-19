# ADR 0001: MCP transportをゲーム内へ埋め込む

- 状態: 採用（PoCで依存隔離を検証）
- 日付: 2026-08-19

## 決定

クライアント専用NeoForge MOD内に、公式MCP Java SDKのStreamable HTTP serverを埋め込みます。外部sidecarは使いません。エンドポイントは`127.0.0.1`上の単一`POST /mcp`です。

## 理由

- Minecraft状態へのアクセスをクライアントスレッドへ安全にdispatchしやすい。
- Python/Nodeの別プロセス、IPC、追加ランタイム管理が不要。
- 公式SDKにprotocol negotiation、schema、Streamable HTTP実装を任せられる。
- MOD JARの追加・削除だけで導入とrollbackができる。

## 影響

- SDKとHTTPコンテナの依存が増えるため、shade + relocateと起動試験が必要。
- MCPクライアントのprotocol互換性を固定バージョンごとに試験する。
- HTTP threadからMinecraft APIを直接呼ぶことをコードレビューで禁止する。
