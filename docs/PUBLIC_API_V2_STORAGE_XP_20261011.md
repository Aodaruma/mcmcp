# 設置収納への操作と経験値

2026-10-11の開発版。公開ツールは16個のまま。設置したチェスト・バレル・対応済みSophisticated Backpacksは `agent_interact`、手持ちの整理・携帯バッグは `agent_inventory` で扱う。

## 設置収納

`agent_interact` で開く・確認する・閉じるまでを1つのActionとして実行する。

```json
{"target":"block","operation":"inspect_storage","x":305,"y":101,"z":303,"block":"sophisticatedbackpacks:netherite_backpack","advance":false}
```

数量指定の取り出し・預け入れも同じツールを使う。`direction` は `take` / `store`、`count` は1～896個。

```json
{"target":"block","operation":"transfer_storage","x":305,"y":101,"z":303,"block":"sophisticatedbackpacks:netherite_backpack","direction":"take","item":"minecraft:white_concrete","count":65,"advance":false}
```

チェストなら `block` を `minecraft:chest` にする。既存の `agent_inventory` の `target:container` / `operation:inspect|transfer` も互換性のため維持し、同じ実行処理を使う。`advance` 既定true、`max_distance` 既定64、`max_ticks` 既定1200。移動・視認・届く距離・対象条件を操作時点で確認する。

開始応答の `action_id` を `agent_get_action` に渡し、`include_result:true` で結果を読む。`result.operation` は互換経路と共通の `inspect` / `transfer`。成功はメニュー解放確認後に確定する。

- 確認結果の `items` はアイテムID別の合計。`item` を指定した確認ではそのIDだけを返す。
- Sophisticated収納では `slots` に枠番号・ID・個数、`storage_slot_count` / `occupied_storage_slots` / `empty_storage_slots` に全体の枠数・使用枠・空き枠を返す。枠数は `item` フィルターによって変わらない。
- 空き枠は、アップグレード・フィルター・アイテムごとの上限を含めた「あと何個入るか」の保証ではない。内容も取得時点のスナップショットで、マグネット等による後続の増減は再確認する。
- 移送は `requested_count` / `confirmed_count` / `unconfirmed` / `effects` で照合する。失敗・取消は実施済みの変更を巻き戻さない。`unknown` や解放待ちの操作を再送しない。

設置バッグは位置・BlockEntity・内容UUIDと、受信メニューのBlockContextを照合する。通常のメインハンド操作で開き、液体容器などバッグの別操作を起こす手持ちは選ばない。NBTを在庫情報として使わず、開いたメニューのサーバー同期内容だけを返す。バッグを破壊・回収する操作は行わない。安全な手持ちがなければ `storage_open_unavailable` で開始前に終了する。

対応確認の対象はSophisticated Backpacks 3.25.90 / Core 1.4.99の既知JAR。現行の収納実行器はSurvivalで使用する。入れ子・リンク収納・別版や別MODの収納、未知のGUIは一律には対応しない。従来の汎用 `target:menu` を使ったMOD枠の直接クリックより、この専用操作を使用する。

## 経験値

引数なしの `agent_get_state` で `player.experience` を返す。`sections:["world"]` を指定した場合は `world.experience` にも同じ情報を返す。

```json
{"level":30,"progress":0.0,"points":1395,"points_to_next_level":112,"points_source":"vanilla_level_and_progress"}
```

`points` は同期済みのレベルとゲージ進捗からVanillaの経験値曲線で計算した現在値。過去の累計取得値 `totalExperience` ではない。レベル消費後も現在のレベルから計算し直す。HUD値が不正なら `experience:null`、正確なJSON整数の範囲を超える合計は `points:null`。MODが経験値曲線を変更する場合には、そのMOD独自の残高と異なる可能性がある。

Waystonesの `inspect_destinations` 結果には、サーバーが返した場合に `available_xp_points` / `available_xp_levels` を追加する。費用は従来通り `xp_points` / `xp_levels`。残高フィールドがない場合は不明であり0ではない。ポイントとレベルを混同せず、往復の各費用と余裕を確認する。`selectable:true` はその行の片道利用条件で、復路の費用や安全な往復を保証しない。

検証範囲・配布物は [検証記録](experiments/20261011_v2_placed_storage_xp.md) を参照。
