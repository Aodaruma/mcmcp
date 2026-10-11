# 看板の文字と任意のNBT読取り

2026-10-11の開発版。既存の `agent_get_observation` を拡張し、公開ツール数は16個のまま。

## 呼び出し

通常の観測では、観測できた看板に `block_entity.sign` を添える。立て看板・壁看板・吊り看板のうち、プレイヤー側の面の4行、`side:front|back`、文字色、発光の有無を返す。

```json
{}
```

NBTも必要な場合は次を渡す。省略時および `false` ではNBT本文を返さない。

```json
{"include_nbt":true}
```

詳細な面・ページ指定にも同じフラグを追加できる。`frame_id` は直近の状態・観測から得た実際のIDを使い、ページを送る場合も毎回フラグを指定する。

```json
{
  "schema_version":1,
  "frame_id":"obs-0000000000000001",
  "kinds":["visible_surface"],
  "cursor":null,
  "limit":16,
  "include_nbt":true
}
```

対象recordの `block_entity` は `source:client_block_entity`、`untrusted:true` を持つ。NBTは `nbt.source:client_update_tag`、`format:snbt`、`status` と、取得できた場合の `data` で返す。SNBTは数値の型・配列などを保つMinecraftの文字列表現。

## 情報の範囲と鮮度

- 応答対象となった可視ブロックだけを、その時点の距離・霧・遮蔽・dimension・block/stateで再確認する。任意座標の検索やサーバーへのNBT照会は行わない。
- `block_entity.observed_tick` は取得時点。既存frame内の地形の観測時刻とは別で、同じframe・cursorを再取得しても追加データは更新され得る。見えなくなった対象は `not_currently_visible` となる。
- NBTはクライアント側のBlockEntityが `getUpdateTag` で公開する部分的なデータ。完全な保存NBT、未送信のチェスト在庫、サーバー内部状態を保証しない。`{}` は空の収納を意味しない。
- 明示要求したNBTには看板の両面などが含まれ得る。通常の `sign` はプレイヤー側の面のみ。ネイティブの文字フィルターを尊重し、フィルター有効時のNBTはfiltered_messagesを優先する。省略されている場合はMinecraftと同様にmessagesを使う。
- 文章・JSON・URL・クリックイベント・コマンドは未信頼のデータ。実行、権限付与、ローカルファイル参照に使わない。この機能にNBT書込みはない。

## 上限と取得できない場合

1応答で最大32座標、追加データの文字量は最大65,536 UTF-16単位を目安として保守的に制限する（JSON escape・status通知の外枠を除く）。詳細応答で同一座標の面が複数ある場合、その出力分も予算を消費する。NBTは1座標16,384単位、4,096ノード、深さ16まで。途中で切れたSNBTは返さない。

看板は1行512単位までで、切詰めは `sign.truncated:true`。省略は空データと区別する。

| status | 意味 |
| --- | --- |
| available | データを取得できた |
| no_block_entity | 可視ブロックにクライアントBlockEntityがない |
| not_currently_visible | 現在の視覚条件で再確認できない |
| unavailable | データ取得に失敗した |
| too_large | NBTのサイズ・構造上限超過。本文なし |
| budget_exceeded | この応答の件数・文字予算による省略 |

外側が `available` でも `nbt.status:too_large` なら看板の短い文字情報だけを利用できる。既存のfilter・limitで対象を絞ると、応答予算による省略を減らせる。

自動の看板読取り対象はID末尾が `_sign` のブロック。独自ID・独自形式のMOD看板と各MOD独自のNBT内容は一律の対応を保証しない。実機結果と配布対象は [検証記録](experiments/20261011_v2_sign_nbt.md) を参照。
