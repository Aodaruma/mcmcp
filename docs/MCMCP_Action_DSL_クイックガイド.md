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

各`visible_surface`はrequired nullableな`state`と`placement_item`を返します。`state={block,properties}`が非nullなのは、閉じた建築copy allowlist、既存support用の`minecraft:dirt` / `minecraft:grass_block` / `minecraft:obsidian`、精錬target用の`minecraft:furnace` / `minecraft:blast_furnace` / `minecraft:smoker`だけで、その場合は登録propertyを省略しない完全なBlockStateです。それ以外は見た目から判別不能なpropertyを渡さないため`state=null`です。`placement_item`が非nullなら`state`も必ず非nullで、建築コピーではそのsurfaceの`state`を`source_state`へ、`placement_item`を`item`へそのままコピーします。

`agent_get_state.standard_potions`は、自分のinventory内で標準Vanilla potionとcomponentが完全一致する1本stackだけを検証し、複数slot分を`{item,potion,count}`で集計します。従来の`inventory` item-ID集計も維持されますが、water / awkward / strength等は同じitem IDなので、醸造の宣言には`standard_potions`を使います。custom color / effect / name / lore等を持つstackや不可能な複数本stackは詳細を公開せず、この一覧から除外します。

村人の取引画面を現在開いており、そのScreen・world session・container ID・open packet revisionと最新のserver取引packetがすべて一致する間だけ、`agent_get_state.merchant_offers`が現れます。各取引はitem ID / count、使用回数、在庫切れ、merchant level / XPを返し、エンチャント本は登録済みstored enchantmentのIDとlevelだけを返します。raw slot、component / NBT、lore、表示文字列、解決不能なenchantment IDは返しません。このread pathは画面を開く、取引する、職業ブロックを壊す・置く、厳選を自動反復する操作を行いません。

現在開いているserver同期済みのVanilla 9x3純storage画面だけは、`agent_get_state.known_menu`に短寿命・single-useの`operation_ref`が現れます。各operationは表示された1 stack全量をstorageからplayer inventoryへ移すものだけで、raw slot番号、GUI座標、component / NBTは返しません。inventoryに全量の空きがないstackは候補から除外されます。

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
- `craft_known_recipe`: `{id,op,recipe_ref,recipe_fingerprint,goal:{item,stack_policy,minimum_inventory_count},station:{kind,target,expected_state},max_crafts}`
- `smelt_known_recipe`: `{id,op,recipe_ref,recipe_fingerprint,goal:{item,stack_policy,minimum_inventory_count},station:{kind,target,expected_state},fuel:{item,stack_policy},max_smelts}`
- `operate_known_menu`: `{id,op,operation_ref}`
- `brew_known_potion_batch`: `{id,op,target,expected_block,input:{item,potion,count},ingredient_item,fuel_item,expected_output:{item,potion,count}}`
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

## 精錬の最小slice

`smelt_known_recipe`は、最新`agent_get_state.recipes`の同じ結果から`recipe_ref`と`fingerprint`をコピーし、可視な`furnace | blast_furnace | smoker`で1個だけ精錬します。`station.target`と完全な`expected_state`は同じ最新surfaceからコピーし、`goal.stack_policy`と`fuel.stack_policy`は`default_components_only`、`max_smelts`は1固定です。開始時に空の正規menuとsingletonの材料・燃料を確認し、load後とresult回収後にclose/reopen full-content/data readbackを行います。raw slot番号やGUI座標は入力にも結果にも出しません。

このnodeはtop-level bodyの最後に1回だけ置き、`if` / `repeat`内や後続nodeを許可しません。120秒、2,400 ticks、camera最大540度、6 interactionsを確保し、distance / break / placementは0にします。途中状態の自動再開やblind retryはしません。

## 共通Menu操作の最小slice

`operate_known_menu`は、ユーザーが現在開いているexactなVanilla 9x3純storage画面について、同じ`agent_get_state.known_menu.operations`から1件の`operation_ref`を無変換コピーして使います。現在のoperationは`transfer_to_player`だけで、1 stack全量を通常のQUICK_MOVE経路で移し、fresh server slot差分、他storage slot不変、player inventoryの完全multisetとcomponent-exact個数を確認してから画面を閉じます。

このnodeはtop-level bodyの最後に1回だけ置き、30秒、600 ticks、1 interactionを確保します。raw slot / GUI座標を推測せず、refが期限切れ、別画面、別revision、source変更なら新しいstateを取得します。player 2x2、専用workstation、MOD固有profile、widget / canvas操作はまだこのsliceに含みません。

## 標準Potion醸造の最小slice

`brew_known_potion_batch`は、現在可視で通常reach内にある`minecraft:brewing_stand`を通常useし、catalogに列挙されたVanillaの一段recipeを1回だけ実行します。`target`は最新`visible_surface.position`、`expected_block`は`minecraft:brewing_stand`、`input`は自分の`standard_potions`証拠から、`expected_output`は目的recipeが定める標準identityから指定します。出力Potionを事前に所持している必要はありません。`standard_potions.count`はitem+potionごとの集計値なので、同数の丸写しではなく、宣言する1〜3本以上あることを確認します。`item`は`minecraft:potion | splash_potion | lingering_potion`、`count`は1〜3で入出力同数、`fuel_item`は常に`minecraft:blaze_powder`です。たとえばwater potion 3本とnether wartからawkward potion 3本を宣言します。通常potion→splashはgunpowder、splash→lingeringはdragon breathというcontainer変換も、catalogにある一段recipeとしてだけ利用できます。

醸造台中央までの片道`|yaw|+|pitch|`は、planner受付時と実行開始直前の両方で270度以下でなければなりません。270度を超えるheadingでは、同じ可視醸造台をtargetにした`face_known_position`を`brew_known_potion_batch`の直前へ置き、そのface node自身の時間・tick・camera costを70秒 / 1,400 tickの醸造node costへ加えてAction budgetを宣言します。醸造node自体は、受付済みheadingからの照準とそのheadingへの復元を合わせて最大540度のままです。

レシピ対応表の正本はcatalogの`$defs.brewingIngredient.description`です。特にVanilla 26.2の`addStartMix`系材料（breeze rod、slime block、stone、cobweb、magma cream、rabbit foot、sugar、glistering melon slice、spider eye、ghast tear、blaze powder）は、`water + 材料 -> mundane`と`awkward + 材料 -> 対応効果`の両方を持ちます。たとえば`awkward + breeze_rod -> wind_charged`であり、waterに対する同材料の結果はwind chargedではなくmundaneです。

Action開始時には醸造台menuの5 item slotがすべて空で、内部のbrew timeが0、fuel counterがVanilla範囲の0〜20でなければなりません。この値は通常menuを開いた後にruntimeだけが検証し、MCPには公開しません。fuel counterが1以上なら既存の1 useを使ってfuel item投入を省略し、0ならinventoryからblaze powderを1個だけ投入します。不一致時は入力値を反射しない固定diagnosticでreplanします。途中状態の再開、失敗したActionのreplay、任意slot操作、任意recipeは扱いません。`ingredient_item=minecraft:blaze_powder`のstrength recipeでは、precharged時はingredient 1個、fuel counterが0ならingredient 1個とfuel 1個の合計2個をinventoryに必要とします。slot内容やmenu dataをMCPへ公開せず、runtime内でserverのfull-content / data更新、材料の正確な差分、brew開始→完了、close/reopen readbackを確認します。成功時は出力を回収し、5 item slotが再びすべて空で、fuel counterが初期値に応じてちょうど1 use減ったことまで確認します。custom potion stackや宣言と異なる内容があれば、クリックを続けずfail closedにします。

照準と開始時viewへの復帰は、Action入場時のlocal camera速度設定を同じ上限として使います。失敗、期限超過、Escでも、Agentが開いたmenu、server cursor、selected slot、viewの解放が確認されるまではterminal結果を返さず、物理入力隔離を維持したままclient tickごとに有限cleanupを進めます。通常のEscはcleanup後にMCP ONの`READY`へ戻り、実際の解放faultだけがOFFになります。normal use送信後にcancelされた場合も、予約したopen期限まではscreen authorityを保持し、遅れて届いた一致OpenScreenをfull-contentで拘束してから閉じます。まだAgent-owned screenを一度も生成していない段階のpause画面やユーザーinventoryは閉じません。

このnodeはAction内で1回だけ、top-level bodyの最後に置きます。`if` / `repeat`内や後続nodeは不許可です。失敗時は未開始suffixを止め、brew開始後にEsc等で中断した場合も成功やrollbackとみなしません。screen / cursor / input ownerの解放を確認してからterminal結果を返します。terminal menu所有中のrecoveryはgameplay interactionを送らないため、Action全体のinteraction上限は16のままです。

## budgetと失敗時の直し方

budgetは成功予想ではなく、worst-caseを収める停止上限です。container操作には少なくとも30秒、600 ticks、camera 360度とschema記載のinteraction数を確保します。`operate_known_menu`は30秒、600 ticks、1 interactionで、distance / camera / break / placementは0です。精錬node 1回には120秒、2,400 ticks、camera最大540度、6 interactionsを確保します。醸造node 1回には70秒、1400 ticks、照準と受付済みheadingへの復元を合わせて最大camera 540度、16 interactionsを確保し、distance / break / placementは0とします。直前に`face_known_position`が必要なら、そのnodeのcostは別途加算します。`apply_known_block_plan`は1 entryごとに15秒、300 ticks、camera 80度、1 placementを確保し、8 entryなら120秒、2400 ticks、camera 640度、8 placementsとします。移動距離、interaction、breakは0です。他の8-target mutation batchには目安として120秒、2400 ticks、最大720 camera度と、処理に応じた8 interactions / breaks / placementsを確保します。targetは入力順に実行するため、その順序のworst-caseが720 camera度を超える場合はruntimeに並べ替えさせず、小さいbatchへ分割します。

schema違反はcatalog順に最大4件、budget不足は不足component名をまとめて返します。提出値や未知property名は診断へ反射されません。mutationやdrop生成後の`TARGET_UNKNOWN`をfield推測で直すのではなく、Actionを区切って新しいframeを観測してください。

具体的な最小JSONは`docs/action-templates/`にあります。template内の座標は例であり、実行時には必ず同じworld/sessionの最新policy-visible recordから置き換えます。
