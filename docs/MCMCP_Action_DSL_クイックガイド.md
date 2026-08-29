# MCMCP Action DSL クイックガイド

この文書は、公開MCP Toolだけを使って安全に複数段階の作業を組み立てるための説明書です。公開surfaceの正本は`MCMCP_MCP_Tool_Catalog.json`であり、この文書はfixture固有の座標、非公開alias、評価promptの答えを追加しません。

## 基本の考え方

1回の`agent_start_action`には、開始時点で根拠を確認できる同じ段階の処理だけを入れます。blockを変更すると新しいsurfaceやdropが生じるため、原則として「観測 → 同段階のbatch Action → terminal確認 → 再観測」を繰り返します。`apply_known_block_plan`内で明示した先行entryを後続entryのsupportにする場合だけ、閉じたdependency proofとして同じAction内の新設blockを利用できます。

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

各`visible_surface`はrequired nullableな`state`と`placement_item`を返します。`state={block,properties}`が非nullなのは、閉じた建築copy allowlistと既存support用の`minecraft:dirt` / `minecraft:grass_block` / `minecraft:obsidian`だけで、その場合は登録propertyを省略しない完全なBlockStateです。それ以外は見た目から判別不能なpropertyを渡さないため`state=null`です。`placement_item`が非nullなら`state`も必ず非nullで、建築コピーではそのsurfaceの`state`を`source_state`へ、`placement_item`を`item`へそのままコピーします。

## 座標を変換しない

| 用途 | コピー元 | Action側 | 禁止事項 |
|---|---|---|---|
| 移動 | `traversability.navigation_target` | `navigate_to_known.target` | `from` / `to`のfloor・round、surfaceから立ち位置を推測 |
| visible blockへの接近 | `visible_surface.position`と`block` | `approach_known_surface.target`と`expected_block` | block座標からfeet-spaceを推測、接近後の再観測を省略 |
| block操作 | `visible_surface.position` | 各block nodeの`target` / `support` | block座標を中心座標へ変換 |
| 建築copy state | `visible_surface.state`と`placement_item` | plan entryの`source_state`と`item` | `facing`、`axis`、`rotation`等をLLM側で回転・mirror変換 |
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
- `apply_known_block_plan`: `{id,op,anchor,transform:{rotation,mirror},entries:[{id,offset,source_state,item,support:{position,face,expected_state,dependency_entry_id}}]}`
- `collect_visible_item_batch`: `{id,op,targets:[{displayed_item,target}]}`

全nodeには一意の`id`が必要です。正規opcode、他の必須field、enum、上限、capabilityはcatalogの`inputSchema`をそのまま使い、aliasを推測しません。

## 建築コピーの最小slice

`apply_known_block_plan`は、移動や破壊を含まない1〜8 blockのstationaryなplace-only Actionです。`anchor`は設置先の基準block座標、各`offset`はコピー元構造内の相対整数座標（各軸-8〜8）です。`transform`は`mirror=none|x|z`を先に、`rotation=0|90|180|270`のY軸時計回り回転を後に適用します。offsetと完全BlockStateの向きはruntimeが同じ規則で変換するため、LLMは`source_state.properties`を書き換えません。

各`support`は、`position`から`face`方向へ1 block隣が当該entryの変換後targetになるよう指定します。`expected_state`と`dependency_entry_id`はどちらも必須nullable fieldで、次のどちらか一方だけを非nullにします。

- 既存blockをsupportにする: `state != null`である最新`visible_surface.state`を`expected_state`へコピーし、`dependency_entry_id=null`
- 同じplanの先行entryをsupportにする: `expected_state=null`、`dependency_entry_id`へ先行entry IDを指定し、`position`をその先行entryの変換後targetと一致させる

既存supportは、`placement_item != null`のcopy可能block、または`minecraft:dirt` / `minecraft:grass_block` / `minecraft:obsidian`だけを使えます。全supportはAction開始時のheadingからyawとpitchの合計40度以内である必要があります。向きが合わない場合は、同じ最新frameの可視supportをtargetにした`face_known_position`をplan直前へ置くか、planを小さく分割します。

entry IDと変換後targetはplan内で一意、処理順は`entries`の入力順です。開始時点で既にtargetが完成stateでもskipせず失敗します。その場合は未設置suffixだけで新しいplanを作り、既設blockを最新観測済み`expected_state` supportとして扱います。NBT、fluid、gravity block、container、portal、command block、既存blockの破壊・置換はこのsliceでは扱いません。途中失敗時は未開始suffixを実行せず、完了済み設置だけをtraceに残します。

## budgetと失敗時の直し方

budgetは成功予想ではなく、worst-caseを収める停止上限です。container操作には少なくとも30秒、600 ticks、camera 360度とschema記載のinteraction数を確保します。`apply_known_block_plan`は1 entryごとに15秒、300 ticks、camera 80度、1 placementを確保し、8 entryなら120秒、2400 ticks、camera 640度、8 placementsとします。移動距離、interaction、breakは0です。他の8-target mutation batchには目安として120秒、2400 ticks、最大720 camera度と、処理に応じた8 interactions / breaks / placementsを確保します。targetは入力順に実行するため、その順序のworst-caseが720 camera度を超える場合はruntimeに並べ替えさせず、小さいbatchへ分割します。

schema違反はcatalog順に最大4件、budget不足は不足component名をまとめて返します。提出値や未知property名は診断へ反射されません。mutationやdrop生成後の`TARGET_UNKNOWN`をfield推測で直すのではなく、Actionを区切って新しいframeを観測してください。

具体的な最小JSONは`docs/action-templates/`にあります。template内の座標は例であり、実行時には必ず同じworld/sessionの最新policy-visible recordから置き換えます。
