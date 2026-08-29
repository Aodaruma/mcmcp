# MCMCP Action DSL クイックガイド

この文書は、公開MCP Toolだけを使って安全に複数段階の作業を組み立てるための説明書です。公開surfaceの正本は`MCMCP_MCP_Tool_Catalog.json`であり、この文書はfixture固有の座標、非公開alias、評価promptの答えを追加しません。

## 基本の考え方

1回の`agent_start_action`には、開始時点で根拠を確認できる同じ段階の処理だけを入れます。blockを変更すると新しいsurfaceやdropが生じるため、原則として「観測 → 同段階のbatch Action → terminal確認 → 再観測」を繰り返します。

畑仕事の場合は、次の段階を混ぜずに進めます。

1. containerを観測し、鍬と種を取り出す
2. 指定区画内の耕作可能な土を観測し、最大8件ずつ入力順で耕す
3. farmlandを再観測し、最大8区画ずつ種を植える
4. 代表区画の成熟を有限期限で待つ
5. 成熟した小麦を再観測し、最大8件ずつ収穫する
6. policy-visibleになった小麦と種のdropを観測し、1件は単体node、2〜8件はbatchで回収する
7. 収穫済み区画へ再播種し、inventoryの小麦絶対個数が目標へ達するまで反復する

`batch`はMCP往復を減らしますが、hiddenな対象を自動探索する高水準Actionではありません。対象は先行するpolicy-visibleな観測から明示し、runtimeは提出順を変えず、途中で証明できない対象があれば未開始suffixを実行しません。

## 観測を絞る

`agent_get_observation.filter`は、既にpolicy-visibleな同一frameから不要なrecordを削るdelivery-only filterです。record kindに適用可能な複数条件はANDで適用され、観測範囲やAction認可を拡張しません。

- 作物: `block_ids=["minecraft:wheat"]`と`crop_mature=true|false`
- 落下物: `entity_types=["minecraft:item"]`と`displayed_items`
- 指定区画: 単一のinclusive `position_bounds={dimension,min_x,min_y,min_z,max_x,max_y,max_z}`

`position_bounds`は任意center/radius scanではありません。surfaceはblock position、entity等は`floor(position)`、traversabilityは返却済み`navigation_target`を基準に除外します。

## 座標を変換しない

| 用途 | コピー元 | Action側 | 禁止事項 |
|---|---|---|---|
| 移動 | `traversability.navigation_target` | `navigate_to_known.target` | `from` / `to`のfloor・round、surfaceから立ち位置を推測 |
| visible blockへの接近 | `visible_surface.position`と`block` | `approach_known_surface.target`と`expected_block` | block座標からfeet-spaceを推測、接近後の再観測を省略 |
| block操作 | `visible_surface.position` | 各block nodeの`target` / `support` | block座標を中心座標へ変換 |
| drop回収 | `visible_entity.position`と`displayed_item` | collect nodeの連続値`target` | XYZのround、非公開entity IDの追加 |

移動用の整数feet-space座標と、block座標と、item entityの連続座標は別の型です。

## 頻出nodeの必須field

- `navigate_to_known`: `{id,op,target,tolerance}`
- `approach_known_surface`: `{id,op,target,expected_block}`
- `inspect_known_container`: `{id,op,target,expected_block}`
- `take_known_container_stack`: `{id,op,target,expected_block,item,stack_policy,minimum_inventory_count}`
- `till_known_batch`: `{id,op,targets:[position],expected_block,hoe_item}`
- `plant_known_wheat_batch`: `{id,op,targets:[{target,support}],seed_item}`
- `harvest_known_wheat_batch`: `{id,op,targets:[position]}`
- `collect_visible_item_batch`: `{id,op,targets:[{displayed_item,target}]}`

全nodeには一意の`id`が必要です。正規opcode、他の必須field、enum、上限、capabilityはcatalogの`inputSchema`をそのまま使い、aliasを推測しません。

## budgetと失敗時の直し方

budgetは成功予想ではなく、worst-caseを収める停止上限です。container操作には少なくとも30秒、600 ticks、camera 360度とschema記載のinteraction数を確保します。8-target mutation batchには目安として120秒、2400 ticks、最大720 camera度と、処理に応じた8 interactions / breaks / placementsを確保します。targetは入力順に実行するため、その順序のworst-caseが720 camera度を超える場合はruntimeに並べ替えさせず、小さいbatchへ分割します。

schema違反はcatalog順に最大4件、budget不足は不足component名をまとめて返します。提出値や未知property名は診断へ反射されません。mutationやdrop生成後の`TARGET_UNKNOWN`をfield推測で直すのではなく、Actionを区切って新しいframeを観測してください。

具体的な最小JSONは`docs/action-templates/`にあります。template内の座標は例であり、実行時には必ず同じworld/sessionの最新policy-visible recordから置き換えます。
