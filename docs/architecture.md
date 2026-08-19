# アーキテクチャ

## 基本方針

LLMは「何をするか」を決め、Minecraftクライアント内の決定論的コードが「各tickでどう安全に実行するか」を担当します。HTTPスレッドからMinecraftの状態へ直接触れません。

```text
MCP client / LLM
      |
      | POST /mcp (127.0.0.1, Origin validation, Bearer token)
      v
MCP transport + schema validation
      |
      v
Command inbox (bounded queue, CompletableFuture)
      |
      | Minecraft client thread only
      v
Routine supervisor -----> Safety controller -----> Emergency stop
      |                         |
      v                         v
Tick executor             Voice Chat adapter
      |
      v
Vanilla input / interaction path -> server-authoritative result
```

## コンポーネント

### MCP transport

- 公式MCP Java SDKのStreamable HTTP providerを使い、MCP本体を独自実装しない。
- 最小の組み込みServletコンテナで`POST /mcp`だけを提供する。
- SDKとHTTP依存はshade + relocateし、他MODのJackson/Reactor/Servlet実装と衝突させない。
- `127.0.0.1`だけにbindし、固定ポートが使用中なら自動的に外部公開せず起動失敗にする。
- MCPプロトコル版はビルドで固定し、クライアントとの互換試験後にだけ更新する。

NeoForgeのJar-in-Jarは候補ですが、共有依存のバージョン交渉が起こるため、MCP/HTTPスタックは隔離を優先します。PoCのビルド・起動試験でshadeが成立しない場合のみ、Jar-in-Jarまたは小さな独自transport adapterを再評価します。

### Command inbox

- HTTPスレッドは入力検証、認証、キュー投入までを行う。
- キューは小さな上限を持ち、満杯なら`busy`で拒否する。
- Minecraftの状態読み取りと操作はすべてクライアントスレッドで実行する。
- 1リクエストの待機時間には上限を設け、長時間処理はroutine IDへ切り離す。

### Observer

公開する状態は、通常のクライアントが既に持つ情報に限定します。

- プレイヤーの体力、空腹、位置、向き、選択スロット
- インベントリの通常表示情報
- クロスヘアが指している対象
- 描画・同期済み範囲内の可視エンティティ
- 現在の画面、接続、Voice Chat、routine状態

未ロードチャンク、壁越し情報、seed推定、サーバー内部状態は取得しません。

### Routine supervisor

- 同時に能動routineは1つだけ。
- routineは有限状態機械として実装し、`STARTING / RUNNING / PAUSED / STOPPING / SUCCEEDED / FAILED / CANCELLED`を持つ。
- 各操作は最大2秒のリースで、tickごとの安全確認に合格した場合だけ更新する。
- ルーチン失敗後に自動で別作戦を試さない。理由を返し、LLMまたはユーザーが再計画する。

### Tick executor

- 通常のKeyMapping・攻撃・使用経路を使う。
- プレイヤー座標、速度、インベントリ、サーバー同期値を直接書き換えない。
- GUIが開いている間は能動操作を禁止する。
- `stop_all`は保持したすべての入力を必ず解放し、複数回呼んでも安全にする。

## ライフサイクル

1. 物理クライアント専用エントリポイントで初期化する。
2. タイトル画面ではMCPを起動しても、能動ツールはlocked状態にする。
3. ワールド参加後に互換性、Voice Chat、安全設定を検査する。
4. ユーザーがローカルUI/キーでautomationを明示的にunlockする。
5. routine開始前にVoice Chatをミュートし、成功を確認する。
6. 各tickで安全条件を検査しながらルーチンを進める。
7. 完了・失敗・手動入力・切断など、どの終了経路でも入力解放とミュート復元を行う。
8. クライアント終了時はHTTPサーバーをgraceful shutdownする。

## 依存関係

Simple Voice Chatは`compileOnly`相当の任意依存とし、専用adapter以外から参照しません。adapterがロードできない場合はコアMODをクラッシュさせず、対象packの既定ポリシーでは能動自動化だけをfail closedにします。
