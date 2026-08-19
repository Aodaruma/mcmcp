# MCPインターフェース

## 方針

MCPには短時間で完了する、境界の明確なツールだけを公開します。長時間処理は非同期routineとして開始し、IDで状態確認・停止します。LLMへ生のキー状態を所有させません。

## Phase 1: 読み取りと制御

### `get_status`

引数なし。接続、lock、互換性、安全装置、Voice Chat、現在のroutineを返します。サーバーアドレスや認証情報は返しません。

### `get_snapshot`

```json
{
  "include_inventory": true,
  "include_visible_entities": false
}
```

自分の体力・空腹・位置・向き・選択スロット、クロスヘア対象、許可された範囲のインベントリ情報を返します。エンティティ一覧は既定で無効です。

### `list_routines`

現在のバージョンで許可されたroutineと、必要条件・制限を返します。

### `get_routine`

```json
{ "routine_id": "uuid" }
```

状態、開始時刻、経過時間、進捗、直近の安全チェック、終了理由を返します。

### `cancel_routine`

```json
{ "routine_id": "uuid", "reason": "user requested" }
```

指定routineを停止します。既に終端状態なら成功扱いにする冪等操作です。

### `emergency_stop`

```json
{ "reason": "operator stop", "lock": true }
```

全入力を解放し、現在のroutineを停止します。`lock=true`ならローカルUIで再解除するまで能動ツールを拒否します。

## Phase 2: 最初の能動routine

### `start_routine`

```json
{
  "kind": "stationary_break",
  "parameters": {
    "target_block": "minecraft:cobblestone",
    "max_duration_seconds": 60,
    "stop_if_inventory_full": true
  },
  "idempotency_key": "client-generated-uuid"
}
```

初期版で許可する`kind`は`stationary_break`だけです。開始条件:

- ローカルunlock済み
- ワールド接続済み、GUIなし、フォーカスあり
- クロスヘア対象が指定ブロックで、通常リーチ内かつ視線が通る
- Voice Chatミュート成功
- 既存の能動routineなし
- 時間上限が1〜300秒

成功時はすぐに`routine_id`を返します。HTTP接続の寿命とroutineの寿命は分離します。

## 内部専用プリミティブ

次の操作はルーチン実装内部に閉じ込め、通常のMCP tool listへ出しません。

- `look_at`
- `hold_attack` / `release_attack`
- `hold_forward` / `release_forward`
- `jump`
- `select_hotbar`
- `stop_all`

開発診断用に必要になった場合も、ローカル設定、単一プレイヤー、短いTTLの三条件を満たす明示的developer modeだけで公開します。

## 共通エラー

| code | 意味 |
|---|---|
| `locked` | ローカルUIで能動操作が許可されていない |
| `unsafe_state` | 体力、画面、対象、Voice Chat等の前提が不成立 |
| `busy` | 別routine実行中またはキュー上限 |
| `incompatible` | バージョン・adapter検査に失敗 |
| `invalid_argument` | schema、範囲、allowlist違反 |
| `timeout` | クライアントスレッド処理または操作リースが期限切れ |

エラー時は内部スタックトレースやローカルパスを返しません。追跡用の短いevent IDだけを返します。
