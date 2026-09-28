# 公開API v2 クイックガイド

開発中のv2用。[基本操作](experiments/20260928_public_api_v2_local.md)と[所持収納・使用中停止](experiments/20260929_v2_storage_stop_local.md)の実機smokeを通過しました。未実装機能と追加検証は[進捗メモ](PUBLIC_API_V2_STATUS_20260928.md)を参照し、配布済みv1と区別してください。旧`agent_start_action`／JSON Action DSLは公開しません。

## 公開ツール

| ツール | 用途・現在の範囲 |
|---|---|
| `agent_get_state` | 引数なしでプレイヤー状態と手元9枠。詳細な所持品などは`sections`で指定 |
| `agent_get_mcp_status` | MCPのON/OFF、session、最新frame、実行中actionの確認 |
| `agent_get_observation` | 引数なしで最新のblock・entity・危険を最大64件。返らない場所は未知であり、空気とは限らない |
| `agent_move` | 座標または方向へ移動。未観測の目的地も指定でき、MODが局所観測しながら進む |
| `agent_break_block` | Vanillaの指定座標・直方体を破壊。条件は任意、`advance:true`で接近しながら続ける |
| `agent_place_block` | Vanillaの指定blockを座標・直方体へ設置。向きなどの`properties`は任意 |
| `agent_interact` | blockのstate変更、手持ちitemの使用、観測したentityへの通常操作。menu操作は未対応 |
| `agent_inventory` | 所持品の確認・hotbarとの交換・投棄、対応収納の確認・数量移送 |
| `agent_click` | 左・右・中クリックを有限回実行。意味的な作業成功までは保証しない |
| `agent_input_sequence` | 論理キー／マウスを同時・順番に入力。時間・間隔・反復・到達停止条件を指定 |
| `agent_run_script` | 名前付き引数、変数、条件分岐、有限loop、小関数で行動を組み合わせる |
| `agent_get_action` | 返された`action_id`の進行確認。詳細結果は`include_result:true` |
| `agent_cancel_action` | 指定した`action_id`へ取消要求。入力と所有menuの終了処理後に停止が確定 |

## 開始・確認・取消

行動8ツールは共通のjobを返します。開始応答の`state:"queued"`は完了ではありません。同じIDを`agent_get_action`に渡し、terminalまで確認します。`wait_timeout_ms:25000`は最大25秒待ち、時間内に終わらなくても現在の進行を返します。

```json
{"x":4,"y":65,"z":8}
```

これは`agent_move`への引数です。停止するには`agent_cancel_action({action_id:"返されたID"})`、結果を読むには`agent_get_action({action_id:"返されたID",include_result:true})`を使います。通信が途切れた行動を自動で再送しないでください。取消は既に確定した設置・破壊・数量移送を巻き戻しません。Esc、MCP OFF、world変更、危険などでも停止します。

`agent_interact({target:"item",item:"minecraft:milk_bucket",result_item:"minecraft:bucket"})`はミルクを使い、手持ちが空のバケツになったserver更新を待ちます。`item`省略時は現在の手持ちを使います。`result_item`省略時の成功は使用受付・server処理・長押し終了までの確認です。固有のworld効果とは区別し、詳細結果の`effect_confirmed`を確認してください。

`agent_interact({target:"entity",entity_ref:"観測で得た参照",item:"minecraft:bucket",result_item:"minecraft:milk_bucket"})`は、手の届く対象へ照準を合わせて操作し、手持ちのserver更新を待ちます。`item`省略時は現在の手持ち、`item:"minecraft:air"`なら空手です。entity操作で`result_item`を省略した成功はclientの操作受付を条件とします。PASSだけを返す操作は`result_item`を明示してserver更新を確認します。`confirmation:"client_dispatch"`と`"server_held_item"`を区別してください。遠方への接近・menu操作は未対応で、確認できない操作は再送しません。

## 範囲・収納・合成

範囲は`x,y,z`から`x+dx,y+dy,z+dz`までの両端を含む直方体です。省略した差分は0、負数も使えます。任意形状は今後の拡張です。

```json
{"x":4,"y":65,"z":8,"dx":3,"dy":1,"advance":true}
```

これは`agent_break_block`への引数です。`agent_place_block`なら`block:"minecraft:stone"`などを加えます。指定範囲と条件の内側で、見えるようになった対象を順に処理します。

`agent_inventory`でチェスト・樽・銅チェストを調べる例:

```json
{"operation":"inspect","target":"container","x":4,"y":65,"z":8}
```

所持する収納itemなら`target:"storage",storage_slot:2`を指定します。`storage_slot`はプレイヤー所持枠です。収納間の数量移送には`operation:"transfer",direction:"take"`または`"store"`と`item,count`を指定します。収納providerは現在Sophisticated Backpacks 3.25.90／Core 1.4.99の検証済みJARに対応。他MODにはprovider／同期profileの追加が必要です。新品backpackは通常UIで一度開いて個体IDを初期化してください。初期化済みのhotbar slot 2でinspect・store3・take2と再開封照合を実機確認しました。数量確認・部分結果・終了処理は共通化しています。

`agent_run_script`の`source`には、次のような同期scriptを渡せます。

```js
for (let i=0; i<2; i++) {
    breakBlocks(x=4+i, y=65, z=8, dy=1, advance=true);
}
move(x=4, y=65, z=8);
```

行動関数は前の行動の終了を待ち、失敗・取消で停止します。ファイル、network、Java、module、`async/await`にはアクセスできません。raw入力もscriptも共通の入力所有・実行上限・停止処理を通ります。

正確な引数・上限は公開Tool Catalog、未実装範囲と設計はrepositoryの`docs/PUBLIC_API_V2.md`を参照してください。v1用の施工runner、capability gate、`container-inspect-recovery`評価は対応するv1 checkoutとJARでのみ使用します。
