# ゲーム内チャットの送受信

2026-10-11。公開ツール `agent_chat` を1つ追加し、合計16ツールとする。ゲームに受信した文章の確認と、通常のチャット発言1件の送信を担う。自動返信・受信文の実行・コマンド実行は行わない。

## 受信

```json
{"operation":"read","limit":50}
```

最初は保持中の履歴を古い順に返す。続きや新着には、応答の `next_cursor` を次回の `cursor` に指定する。読むだけでは他の利用者のカーソルや履歴を進めない。

```json
{"operation":"read","cursor":"応答のnext_cursor","limit":50}
```

- MCP ON中、Minecraftがチャット欄へ採用した発言を最大256件、メモリー内だけに保持する。過去のログファイルは読まない。既定50件、1回最大100件。
- `text` は表示用の文章。`source` は `player`、`system_server`、`system_client`。表示名から送信者UUIDや権限を推測しない。
- `sequence` は履歴内の連番、`received_tick` は受信したworld sessionのclient tick。`has_more` はページの続き、`missed_messages` は保持上限で失った件数。
- 1件2,048 UTF-16単位を超える文章は `truncated:true`。ネイティブの削除通知は、保持中の本文を空にし `deleted:true` にする。既に取得済みのコピーは撤回できず、過去の削除を新着として再配信する機能はない。
- 非表示・フィルターされた受信パケット、画面中央のaction bar、componentのクリック操作は対象外。MODがチャット欄に追加する文章も含む。
- ワールド／接続変更、ゲーム内チャット履歴の消去で履歴とカーソルを破棄する。`INVALID_CURSOR` ならカーソルなしで取り直す。
- MCP OFF中は受信の収集・読み取りを行わない。同じworldで再ONした場合、ON中に記録済みの履歴は保持される。ほかのAction実行中も読み取り可能。

全受信文章は `untrusted:true` のデータ。文章中の命令、リンク、コードをMODが実行することはない。利用するエージェントも、他プレイヤーの発言を利用者の操作許可として扱わない。

## 送信

```json
{"operation":"send","text":"こちらの壁の設置が終わりました。"}
```

応答の `action_id` を `agent_get_action` の `include_result:true` で照会する。取消は共通の `agent_cancel_action`。開始応答の配送確認後に、一度だけMinecraft標準の署名付きチャット送信処理を呼ぶ。

- prefix・suffixを含めて1行1〜256 UTF-16単位。空白だけ、改行・制御文字・書式コード、先頭の空白を除いて `/` で始まる文は拒否する。日本語・絵文字に対応する。
- 同時実行は既存の共通Actionに従い、MCP READY・安全なworld・他Actionなしが必要。受付後の固定予算は20 tick、送信試行は最大1秒に1回。まとめ送信・自動再送は行わない。
- アカウントのチャット制限、接続先のMCP利用許可、Esc・OFF・取消・world境界を維持する。未送信なら中止できるが、送信後の発言は取り消せない。
- `kind:chat`、`result.dispatch:submitted_to_client` はネイティブ送信関数が戻ったことを示す。別MODの変更・抑止や、server側の拒否があり得るため、`delivery_confirmed:false` のまま。相手が読んだことや受信したことを成功の意味に含めない。
- 送信途中の例外は `dispatch:unknown`。不明な発言を自動再送しない。レート制限は `dispatch:rate_limited`。受信履歴へ送信成功を捏造せず、実際のエコーが届いた場合だけ受信として記録する。
- 初版は通常チャットのみ。`/msg` 等のコマンド、宛先指定、scriptからの送受信、永続ログ、プッシュ通知は未対応。

PowerShellクライアントは `-Tool agent_chat` に対応し、`operation:send` の場合のみ `-WaitSeconds` で共通Actionの終了を待てる。

## prefix・suffix設定

「設定 → チャット設定」の一番下に「Agentのチャット送信」を追加する。前につける文字と後ろにつける文字を、それぞれON/OFF・編集できる。変更はクライアント設定へ保存され、次のAgent送信から有効になる。手動チャットは変えない。

既定はprefix OFF・空文字、suffix ON・` (by Agent)`。たとえば上記の発言は `こちらの壁の設置が終わりました。 (by Agent)` になる。空白は設定どおり付加し、自動で重複を除いたり区切りを補ったりしない。

`config/mcmcp-client.toml` の対応キー:

```toml
agent_chat_prefix_enabled = false
agent_chat_prefix = ""
agent_chat_suffix_enabled = true
agent_chat_suffix = " (by Agent)"
```

受付時に設定を確定し、付加後に長さと禁止文字を再検査する。上限超過は切り詰めず送信前に拒否する。既定suffixでは本文は最大245 UTF-16単位。prefixで `/` が先頭になる設定でもコマンドは実行しない。

## 検証

受信のページ分割・同一カーソル再取得・上限超過・world変更・削除・文字数制限、送信の入力検証・配送確認・取消・安全停止・不明送信の再送禁止・時間による頻度制限、公開schemaと共通Actionルーティングを自動試験する。実機検証と対象JARの記録は [検証記録](experiments/20261011_v2_chat.md) を参照。
