# 水入り葉・落下パウダー・End台再生成のコード確認

対象は手元のMinecraft 26.2とNeoForge 26.2.0.59。通常ゲームの入力・再現試験・設備変更は開発担当では行っていない。現場観測と静止試験は地図アート担当からの報告として区別する。

## 硬化する順序

`FallingBlockEntity.tick` の空中での水判定は、基本的にはentityの `blockPosition` にあるfluid。高速時（速度の二乗が1を超える場合）は前位置から現在位置へのCOLLIDER／SOURCE_ONLYのrayも使う。**隣接する水入り葉の横を通っただけでは、周囲の水を走査して粉entityをコンクリートへ変える処理にはならない**。

着地または水の検出後は、着地先blockが置換可能で、落下blockがその位置で存在でき、下が落下可能な空間ではないこと等を確認する。水中のコンクリートパウダーには下の支持条件の例外がある。`setBlock` に成功した後で初めて `ConcretePowderBlock.onLand` が呼ばれる。

`onLand` は着地先の元fluidまたは周囲の水を調べ、硬化できれば対応色のconcreteへ置換する。周囲判定は軸に沿う隣接cellで、通常は横4方向と直上。下に水があるだけの状態、斜め・斜め上の水は代用にならない。隣接する水の側の面が `isFaceSturdy` でないことも必要。

`LeavesBlock.getFluidState` はwaterlogged=trueのとき水源を返す。`getBlockSupportShape` は空なので、この隣接硬化判定のsturdy面条件に阻まれない。葉の衝突形状と、ここで使う支持面の判定は別である。**水入り葉に面で隣接するairへ粉を正常に設置・着地させる方式はコードと整合する**。葉のcellそのものを粉で置換できるという意味ではない。

NeoForge版は水判定を `canBeHydrated` に接続するが、着地先の置換→成功後のonLandという順序と、隣接面の確認は維持している。サーバー固有のMOD・設定・パッチによる変更は別途未確認。

## 粉アイテムになる2つの経路

| 経路 | 判定と結果 |
| --- | --- |
| 着地時に置けない | `canBeReplaced`／存在・支持条件に失敗した場合、またはsetBlock失敗時のdrop分岐ではonLandを通らず、元の粉をアイテム化し得る。dropItemとENTITY_DROPS等の条件に従う。 |
| 粉として置かれた後、台が再生成される | 水に隣接しなければ正常着地しても粉blockのまま。その後のEnd入場で台が再生成され、粉blockが破壊・回収される。水際で硬化済みならconcreteを回収する。 |

松明・糸は見た目の空間と置換可否が別。26.2の通常torch／tripwire登録はnoCollisionだがreplaceableを付けていない。着地先cellがそれらで埋まっている場合、非衝突だから粉を置けるとは限らない。通常の床の上のairなら、床だけで即アイテム化するわけではない。まず粉blockまたはconcreteとして着地し、後続の台再生成による破壊と区別する。

## End台の範囲と破壊

`ServerLevel.END_SPAWN_POINT` は `(100,50,0)`。End向けの `EndPortalBlock.getPortalDestination` は1つ下の `(100,49,0)` を中心として `EndPlatformFeature.createEndPlatform(..., true)` を呼ぶ。

生成関数はdx/dzを-2～2、dyを-1～2で走査し、dy=-1は黒曜石、それより上の3層はairにする。既に目的のblockなら変更せず、異なるblockにはtrue引数の場合 `destroyBlock(pos,true,null)` を呼んでから置換する。

- 床: `x=98..102, y=48, z=-2..2` の25cellを黒曜石。
- その上: 同じx/z、`y=49..51` の75cellをair。
- 外周 `x=97/103`、`z=-3/3` はこの範囲の外。そこにある葉や扉はこの再生成処理の直接対象ではない。

このため、内側y49～51へ恒久的な葉や水を足しても次回の再生成対象になる。外側の水際へ着地させ、次の入場で硬化済みblockを回収する設計と、中心で粉のまま回収される失敗経路を区別して調べる。

## 配布配置との比較と現場の静止試験

担当が取得した配布litematicのオフラインparsed.jsonを確認した。`simple 7gt concrete collection` と `576k concrete collection` は共に黒曜石25個、床の1つ上の高さに水入りoak_leavesを四辺各5個・計20個配置している。作者欄はGaRLic_BrEd_。参照先は `_PLAYING/map-art-MFwtpM21wWc-3x3/duper-operation/reference-concrete-factory`。ゲームworldのNBTは使用していない。

現場報告では北西角 `(98,49,-2)` の西 `(97,49,-2)` と北 `(98,49,-3)` が扉で、水が欠けている。斜め上の葉はこの角の隣接水にはならない。一方、固体black `(102,49,0)` とgray `(100,49,-2)` はそれぞれ外側水際と隣接する座標で、硬化したことと整合する。

その後、地図アート担当が装置OFFの静止対照試験を報告した。同じmagenta粉を北西角 `(98,49,-2)` に設置すると粉のまま、南の水際 `(99,49,2)` ではmagenta_concreteへ即硬化。fresh observe `e58aa5ac-6fef-4a57-9998-720be299bf8c` で両方known:trueを確認したとのこと。**この2点の硬化差は現場確認済み**。ただし実際のduperが北西角／中央へ何個着地するか、粉混入全量の原因、長時間の生産比率は未確認。報告時点で試験品2個を回収中、葉・扉・機構は変更していない。

API上も水際では設置previewが完成後のconcreteになる。担当は `block:magenta_concrete` と `item:magenta_concrete_powder` の組で設置成功を確認した。粉を完成blockとして指定したときの事前拒否は、このpreview契約と整合するため新規不具合には数えない。

## 北側の水を補った後の同じ角での再試験

2026-10-10 14:47 JSTの担当保存記録 `conversion-nw-water-repair-20261010.json` を確認した。装置OFFのまま、北側の扉 `(98,49,-3)` を下段oak_leaves・waterlogged=true、上段 `(98,50,-3)` を同じ葉・waterlogged=falseへ変更し、西側の扉 `(97,49,-2)` は維持している。この変更と入力は地図アート担当が実施した。

同じ北西角 `(98,49,-2)` へのmagenta粉の設置は今度はmagenta_concreteへ硬化した。設置 `1dcdee02-7c69-4ad0-b427-9b199218bc53` はconfirmed_count:1、fresh観測 `e2261d11-5ce2-4907-aaef-5f8068709a10` も固体を確認。撤去後は `476627bc-deba-4eb9-b874-1e6971bebf71` で対象airと葉の上下のwaterlogged状態、西扉の維持を再確認している。**同じ角でも、北に隣接水を足した前後で硬化結果が変わった**。静止設置の確認であり、実際のduperの着地分布・粉混入全量・長時間の変換率は依然として未確認。

水入り化には、`agent_interact` のwater_bucketで失敗した後、担当が `agent_look` と対象blockを限定した `agent_click` の右1回を使い、別のfresh観測でwaterlogged=trueと空bucketを確認した。[操作経路のコード調査](20261010_v2_bucket_interact_diagnosis.md)に記録する。北扉と試験solidの破壊は元の失敗結果を維持し、fresh AIRを別に確認して再送していない。[破壊失敗後の効果の記録](20261010_v2_break_post_effect_diagnosis.md)も更新した。

供給bag8へのstore192、葉2個と水bucket1個のtakeは、保存結果がそれぞれconfirmed_count:192/2/1、release_confirmed:true。これらの成功で過去の別の収納UNKNOWNを再分類しない。

## 根拠の保存

手元のJARをJDK25のjavapで読み取り、上記class／methodの分岐を追った。bytecodeとNeoForge拡張sourceの作業用記録は `artifacts/v2-powder-code-20261010` に保存し、原ソース全体はこの文書へ転載しない。

- vanilla client 26.2 JAR SHA-256: `40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290`
- 開発用NeoForge patched JAR SHA-256: `da4bc9940d74841663f3670036ea0b5794d29beeba9474df12f1143857433be2`

動画の置換案の優劣や、稼働中worldでの修復効果を検証した記録ではない。
