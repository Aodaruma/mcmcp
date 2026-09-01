# 簡易建築コピーの対話的E2E開発試験（2026-09-01）

- 実験ID: `simple-building-copy-dev-e2e-2026-09-01`
- 実装commit: `65dca0a470c2d255070fc53e4e0c0addc5f3ed4c`
- 実施時間: 2026-09-01 13:16〜19:30 JST
- artifact: `C:\Users\aod\Documents\GitHub\mcmcp\mcmcp-test-artifacts\2026-09-01_simple-building-copy-latest.log`
- 判定: **人手補正後の外観は概ね一致**。ただし開発途中の再起動、`/tp`、建材復元用`/give`、最後の入口補正があるため、非干渉・MCP-onlyの正式合格runではない

## 目的と対象

元建築はおおむね `x=10..14, y=56..59, z=-32..-28`、複製先は滑らかな石を目印にした `z+9` の位置である。元建築のBlockStateを観測し、完成品を直接編集する`/clone`、`/fill`、`/setblock`を使わずに、公開Actionで同じ見た目を再構成できるかを確認した。

開始時のplayer inventoryは空だった。材料chest `(5,56,-30)` の最初の正常な読取り結果は次のとおりだった。

| item | count |
|---|---:|
| diamond axe | 1 |
| glass | 64 |
| ladder | 64 |
| oak log | 64 |
| oak planks | 128 |
| torch | 64 |

## 回数

この試験は、完成まで続けた1本の対話的E2E開発試験である。session履歴から機械的に数えた内訳は次のとおり。

| 項目 | 回数 | 備考 |
|---|---:|---|
| Action program送信試行 | 434 | 受付前のschema・budget・evidence不成立も含む |
| 製品コードのpatch投入 | 10 | 原因別には7分類。途中の同一修正の分割patchを含む |
| Gradle検証 | 16 | targeted test 8、JAR build 6、full verification 2 |
| 修正版JAR反映のclient再起動 | 6 | 各回でPhase 5 fixture autorunが再適用された |
| 完成後の保存・再読込確認 | 1 | 最終表示位置への復帰を含む |
| `/tp`による開発中の位置復帰 | 34 | 建築block自体の変更には使っていないが、移動能力の評価を無効にする |
| `/give`による建材残数復元 | 8 | 4 itemを2回。資源取得・保存の評価を無効にする |
| `/clone` / `/fill` / `/setblock` | 0 | 建築blockの配置・撤去は公開Actionを使用 |
| `craft_known_recipe` | 0 | chestに完成建材があり、本runではcraftを実行していない |

最終full verificationは`clean check jar harnessJar runGameTestServer`成功、GameTest 8/8成功だった。

## 建築の進め方

1. `agent_get_observation`の`visible_surface.state`と`placement_item`を使い、元建築を面ごとに観測した。
2. 元座標から複製先へ一律`z+9`を適用した。logの`axis`、ladderの`facing`、wall torchの向きは手で変換せず、観測された完全stateをコピーした。
3. `inspect_known_container`と`take_known_container_stack`でchestを確認し、必要なwhole stackを取得した。
4. 床付近、壁、上周、屋根の順で、小さい`apply_known_block_plan`へ分割して配置した。1 Actionに多数の異なる視線方向を詰めるとcamera budgetまたは40度制約に掛かるため、`face_known_position`と短いplanを組み合わせた。
5. 高所は一時的なoak planks足場と`pillar_up_known`を使った。ladderとwall torchは支持面へのsurface attachmentとして別に配置した。
6. 一時足場は、遮蔽が解けるたびに再観測して`clear_known_block_plan`で外した。
7. 開発中の実装差し替え後は`/tp`で作業地点へ復帰した。これは配置を直接行うcommandではないが、本runを移動込みの正式E2Eにはできない。
8. 最後に保存・再読込して外観を確認した。入口2blockを一時作業口と誤認して埋め戻したため、利用者が手動で再び開けた。

## 躓いた点と修正

原因別の製品コード修正は7分類だった。

1. 最初のcontainer実装がsingle chest / barrel限定で、材料のdouble chestを拒否した。`generic_9x6`とdouble chest identityを同じ安全なreadback経路へ追加した。
2. ladderとwall torchがfull cube前提のconstruction allowlistから外れていた。安全なsurface attachmentとして許可し、wall torchのblock stateに対する配置itemを`minecraft:torch`へ対応付けた。
3. `pillar_up_known`で中央へ移動するとplayer自身が足元supportを遮蔽し、直前まで有効だったUP面evidenceが消えた。配達済みsupport witnessだけを最終centering中も保持し、実行直前のlive完全state検証は維持した。
4. pillarのsupport policyが通常の安全な設置支持blockだけを見ており、oak planks等の安全なconstruction block上で上れなかった。両policyの安全側和集合へ修正した。
5. jump後のpillar placementがgenericな呼出経路では安定しなかった。exact supportとitemを確認後、通常useを直接1回送る専用経路へ修正した。
6. `apply_known_block_plan`の40度判定が観測rayの偶然の端点を使い、同じ面でもedge hit位置により拒否した。宣言support faceの中心でplannerとruntimeを一致させた。
7. `clear_known_block_plan`にも同じedge-hit問題が残っていたため、撤去側も配達済み代表面の中心へ統一した。

実装不具合ではないが、計画上は次にも時間を使った。

- 高所ladderを最後に処理すると屋根上からUP面しか見えず、side-facing ladderを置けなかった。地上側へ戻り、側面が見える順序へ変更した。
- interior wall torchは元stateと複製先supportの両方が見える観測位置を探す必要があった。
- 一時足場は互いに遮蔽するため、一度に全撤去せず、fresh observationを挟んで層ごとに外した。
- 入口を「一時作業口」と誤認して埋めた。形状比較に開口部のair cellを含めていなかったことが原因で、利用者が2blockを手動補正した。

## inventoryの由来と汚染

### 赤石関連item

赤石関連itemは材料chest由来ではない。検証profileではPhase 5 fixture autorunが`generalization` modeで有効だった。client再起動ごとにfixtureがplayer inventoryを全消去し、次を投入する。

- redstone lamp 3
- lever 2
- glass 2
- redstone 1
- smooth stone 1

最終inventoryの同じ個数はこのfixture注入と一致する。建築開始時はinventoryが空であり、最初のchest読取りにもredstone、lever、redstone lamp、smooth stoneは存在しない。したがってcross-test contaminationである。

### diamond axe

斧は次の順序で失われた。

1. 08:23 UTCのchest再読取りではdiamond axe 1個だけが残っていた。
2. 08:25 UTC、`take_known_container_stack` Action `6958feaf-a36e-4af6-bb8c-968e78b7d5b7`が成功し、traceに`container_transfer=minecraft:diamond_axe`が残った。
3. 08:26 UTCの`agent_get_state`でplayer inventoryのdiamond axe 1個を確認した。
4. 08:45〜08:47 UTCに修正版反映のためclientを再起動した。
5. fixture autorunの`player.getInventory().clearContent()`が斧を含む全inventoryを消し、generalization用itemへ入れ替えた。
6. その後、残材としてoak log 17、oak planks 41、ladder 4、torch 4だけを`/give`で復元し、斧を復元しなかった。

したがって斧は使用で壊れたのでも、別slotに隠れたのでも、chestへ戻ったのでもない。fixture再適用で削除された。これは試験手順の不備である。

## 判定と次回条件

建築stateを公開Actionで配置・撤去でき、方向付きlog、ladder、wall torch、高所足場まで動作した点は確認できた。一方で、次の理由から正式な合格とはしない。

- 入口を利用者が手動補正した。
- `/tp`を34回使ったため、自律navigation込みの成功ではない。
- `/give`で建材を復元したため、資源取得・保存込みの成功ではない。
- `craft_known_recipe`を使っておらず、craft要件を検証していない。
- fixture autorunが別試験用inventoryを注入し、斧を削除した。

次回はbuilding用worldでPhase 5 fixture autorunを無効化し、T0のinventoryとchest内容を固定・記録する。再起動を挟まない完成版JARで、command補助なし、開口部のairを含むsource/destination完全state差分、craft実行trace、最終inventory収支を合格条件にする。
