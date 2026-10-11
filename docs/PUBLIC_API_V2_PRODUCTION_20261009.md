# 公開API v2：クラフト・製錬・醸造

> 材料データ、MOD破壊、水中／乗り物、整理、条件・外部ファイルの追加実装は[2026-10-09の追加ガイド](PUBLIC_API_V2_PRIORITIES_20261009.md)を参照。最新の実機結果と導入状況は[試用メモ](PUBLIC_API_V2_TRIAL_20261010.md)を参照。

2026-10-09時点の生産系実装を説明する。[隔離Dockerの9ケース・24 jobと検証範囲](experiments/20261009_v2_production_docker.md)を参照。以降の追加実装と通常profileへの導入状況は上記の試用メモを参照。[Tool Catalog](MCMCP_MCP_Tool_Catalog.json)が入力schemaの正本。

## 操作と責務

| ツール | 今回使える操作 | 数量の意味 |
| --- | --- | --- |
| `agent_craft` | `plan`／`execute`。手元2×2または座標指定の作業台3×3 | `count` は新たに作る完成品数、`max_crafts` は製作回数 |
| `agent_interact` | `target:"block", operation:"smelt"`／`"brew"` | 製錬は新規完成品数、醸造は瓶数1～3 |
| `agent_inventory` | `operation:"move"`。手元・main inventory間の数量指定移送 | `count` は移送数1～64、枠番号0～40（装備slot制約あり） |

開始応答の `action_id` を `agent_get_action(include_result:true)` で照会する。取消は `agent_cancel_action`。固定引数の `craft(...)`／`interact(...)`／`inventory(...)` をscript内でも使える。確認できない操作を自動で再送しない。

## クラフト

まず `agent_get_state` の `query:{kind:"result_item",item:"minecraft:oak_planks"}` でレシピを取得する。`recipe_ref` と `fingerprint` を次の呼出しに使う。`v2_craft_supported` は現在の製作処理で扱える表示かを示す。従来の `supported` は旧adapterの判定なので、戻り容器のレシピでは両者が異なる。

```json
{"operation":"execute","station":{"kind":"inventory_2x2"},"recipe_ref":"<取得した参照>","recipe_fingerprint":"<取得したfingerprint>","count":8,"max_crafts":2}
```

`operation:"plan"` で製作せずに材料と数量・容量を調べられる。作業台の場合は `station:{kind:"crafting_table",x:305,y:101,z:303}`。planだけでは現地の台・画面を確認しないため、`station_verified:false` と実行前の確認事項を返す。

1回4個のレシピで5個を指定すると既定では拒否する。`allow_surplus:true` のときだけ8個を許可する。既存所持数は `count` に含めない。`max_crafts` は既定64・上限4096。`max_ticks` は既定1200。作業台への接近は `advance:true`、`max_distance:64` が既定。

通常の定形・不定形、item ID条件に適合するデータ付き材料と決定的な完成品を対象にする。牛乳バケツ等の既知の戻り容器は、完成品との収容容量とserver差分を照合して回収する。結果の `confirmed_crafts`、`confirmed_count`、`consumed`、`produced_remainders` を参照する。特殊レシピ・連鎖クラフトは追加実装待ち。近隣収納からの材料自動回収はしない。

## 製錬・醸造

製錬は、対応するかまど・溶鉱炉・燻製器のレシピ参照を使う。空で使用可能な設備を指定し、材料と燃料は所持品から必要なstackへ分ける。

```json
{"target":"block","operation":"smelt","x":307,"y":101,"z":303,"block":"minecraft:furnace","recipe_ref":"<取得した参照>","recipe_fingerprint":"<取得したfingerprint>","count":4,"fuel":{"item":"minecraft:coal","max_count":1},"max_ticks":2400}
```

`max_smelts` は既定・上限64。完成品1 stack以内、default componentsの材料・燃料を扱う。戻り容器を伴う燃料や、食材としても投入できて通常Shiftクリックの行先が曖昧な燃料は拒否する。`fuel.max_count` は消費の上限。未使用燃料は回収し、実際に照合した材料・燃料を `consumed` に返す。既定 `max_ticks:15000`。

醸造は標準の既知の1工程を指定する。ポーションは容器のitem IDと `potion` の両方を照合する。

```json
{"target":"block","operation":"brew","x":309,"y":101,"z":303,"block":"minecraft:brewing_stand","input":{"item":"minecraft:potion","potion":"minecraft:water"},"output":{"item":"minecraft:potion","potion":"minecraft:awkward"},"ingredient":"minecraft:nether_wart","count":3,"fuel":{"item":"minecraft:blaze_powder","max_count":1},"max_ticks":2400}
```

台の燃料が残っていれば再利用できる。複数工程の自動計画は未実装なので、現時点では1工程ごとに呼ぶ。入力と出力の種類がその材料で変換できなければ実行前に拒否する。

## 停止と未確認事項

取消・期限・Esc・UI OFFでは新規操作を止め、所有するキー・画面・cursorの解放確認後に終了する。材料移送や完成済みの加工を巻き戻さない。数量移送の途中終了でも、確定した移送数を保持する。

設備は閉じても通常ゲームとして加工を続け得る。`processing_may_continue`、`last_station_slots`、`station_observed_tick` を見て再観測する。slot記録は最後の観測であり現在の保証ではない。解放を証明できない場合は `release_pending:true` を保持し、次のjobを開始しない。ワールド切替で証拠を取得できない場合は `release_evidence_unavailable` を返す。

取引・金床・エンチャント、連鎖クラフト、複数工程醸造等は[承認済みの実装計画](PUBLIC_API_V2_PRODUCTION_PLAN_20261009.md)の後続範囲。全MODや通信拒否の全組合せを実機合格とは扱わない。
