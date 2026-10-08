# 公開API v2 生産系ツールの設計案と実装順序

2026-10-09。クラフトを最優先とし、製錬・醸造、取引・金床・エンチャントへ広げる。クラフトには新しい `agent_craft` を設け、設備・相手への操作は既存の `agent_interact` を拡張する。インベントリ整理は `agent_inventory` の責務を維持する。

**本書は承認された段階的な実装計画。未実装の引数・操作も含むため、そのまま現在の仕様として扱わない。** 最初のクラフト・製錬・単工程醸造・数量移送を実装し、15ツールになった。[現時点の使い方と制限](PUBLIC_API_V2_PRODUCTION_20261009.md)、現行の [Tool Catalog](MCMCP_MCP_Tool_Catalog.json)を参照。以下の調査時点の基準は `63a8037`。利用者の「鋳造」は「醸造（ポーション作り）」と確認済みで、MODの金属鋳造は今回の範囲に含めない。

## 設計着手時点の公開定義（`63a8037`）

| 対象 | 公開済みの内容 | 未定義の実行契約 |
| --- | --- | --- |
| クラフト | `agent_get_state` の `query` でクライアントが知るレシピを検索できる | 作る物・数量・作業場所を指定して材料配置から完成品回収まで実行するツール |
| 製錬・醸造 | レシピ表示や標準ポーションの情報。一部の旧内部実行器も残る | v2で設備へ接近し、材料・燃料投入、待機、回収まで行う引数 |
| 取引 | `agent_get_state(sections:["merchant_offers"])` は現在開いて同期した取引画面の内容を返す | 相手を開いて取引候補を取得する専用操作と、選んだ取引の確定実行 |
| 金床・エンチャント | 通常のブロック使用などの入口 | 入力アイテム・費用・表示された候補の照合と確定操作 |
| 汎用メニュー | `agent_interact(target:"menu")` の開封・照合・限定収納の全スタック移動・閉鎖 | 加工結果スロットや専用ボタンを扱う型付き操作 |

旧 [production autonomyのロードマップ](MCMCP_Production_Autonomy_Roadmap.md) と内部DSLには `craft_known_recipe`、`smelt_known_recipe`、`brew_known_potion_batch` がある。ただしv2の公開開始ツールからは呼べない。旧DSLを再公開せず、既存処理の有効な部分をv2の共通jobへ接続する。2×2クラフトは内部でも拒否されており、3×3の接続だけでは解決しない。

レシピ検索の既存 `supported` は内部レシピ処理の対応判定であり、「v2から実行可能」の保証ではない。クラフト公開時に説明を明確化し、現在の実行器・作業場所での可否と `unsupported_reason` を整合させる。

## ツールの役割

| 公開先 | 追加する操作案 | 責務 |
| --- | --- | --- |
| `agent_craft` 新規 | `operation:"plan" / "execute"` | 手元2×2・作業台3×3での製作。材料と完成品の数量、戻り容器、配置場所を確認する |
| `agent_interact` 拡張 | `target:"block", operation:"inspect_menu" / "smelt" / "brew"` | 設備の確認、製錬・醸造、完成品回収 |
| 同上 | `target:"entity", operation:"inspect_offers" / "trade"` | 相手の取引候補の確認と、指定した取引の実行 |
| 同上 | `target:"block", operation:"preview_anvil" / "anvil" / "preview_enchant" / "enchant"` | 投入する品に対する候補・費用の確認と、確定実行 |
| `agent_inventory` 拡張 | 既存の移送・交換を拡張 | 分割、任意枠への数量移送、装備、対応MOD収納。製作や取引はここへ混在させない |
| 共通の照会・取消 | `agent_get_action` / `agent_cancel_action` | 各操作の進行・結果・取消。専用の取引用jobは増やさない |

クラフトは手元でも行える独立した行動なので名前付きツールとする。`agent_interact` は対象に働きかける既存の役割を保ち、取引・設備の操作を `operation` で見つけられるようにする。この案で追加するトップレベルのツールは1個で、全段階の実装後は現在の14個から15個になる。

既存呼出しで `operation` を省略した場合の挙動は変えない。新しい分岐だけに必須引数を設け、`target` と `operation` の組合せごとの閉じたschemaにする。`agent_click` や汎用menuの生スロット指定を、未対応の加工処理の代用にはしない。

## クラフトの契約案

`agent_craft` は `operation`、`station`、`recipe_ref`、`recipe_fingerprint`、`count`、`max_crafts`、`max_ticks` を基本とする。`station.kind` は `inventory_2x2` または `crafting_table`。作業台には座標とblock条件を持たせ、接近の `advance` と移動距離予算も扱う。材料は最初はプレイヤー所持品から使い、近隣チェストの自動探索・回収は行わない。

`count` は「このjobで新たに作って回収する完成品数」で、既存の所持数を含めない。1回に4個できるレシピで5個を要求するような場合は、既定では入力前に数量不整合を返す。`allow_surplus:true` のときだけ8個までの製作を許し、計画と結果に実数を示す。`max_crafts` は製作回数の上限であり、完成品数とは分ける。

`plan` は製作操作をせず、既知レシピ、材料不足、必要回数、完成品・戻り容器の収容可否を返す。作業台が未観測なら実行可能とは断定せず、現地で必要な確認を返す。`execute` は同じ条件を実行直前にも検証する。計画から実行までの材料予約や、計画結果だけによる実行許可は行わない。

```json
{
  "operation": "execute",
  "station": {"kind": "inventory_2x2"},
  "recipe_ref": "<レシピ検索で得た参照>",
  "recipe_fingerprint": "<同じレシピのfingerprint>",
  "count": 8,
  "max_crafts": 2,
  "max_ticks": 1200
}
```

これは1回4個の完成品を得るレシピの例。レシピ参照は現在のworld sessionとレシピ更新に結び付け、失効時に別レシピへ置き換えない。材料の代替候補が複数ある場合は計画で選択内容を返し、利用側が明示的に制約できるようにする。

最初の段階で通常の定形・不定形レシピを2×2・3×3の両方へ接続する。続いてバケツなどの戻り容器、データを持つ材料、連鎖クラフトを同じ優先枠で拡張する。連鎖クラフトは `recursive:false` を既定とし、有効時には中間材料・総消費・段数・総製作回数を事前計算する。材料不足、循環、複数の解釈があるレシピは実行前に返す。MODレシピはMOD名だけで可否を決めず、標準の表示・操作・同期で扱えるものから検証する。

## 製錬と醸造の契約案

製錬は `agent_interact(target:"block", operation:"smelt")` へ座標、設備block、レシピ参照、完成品数、燃料の種類と使用上限を指定する。かまど・溶鉱炉・燻製器の適合を照合する。最初は空で使用可能な設備を対象にし、他の材料や完成品が入っていれば内容を報告して停止する。投入済み設備との共用は別の追加段階とする。

醸造は同じツールの `operation:"brew"` とし、醸造台、入力ポーション、目的ポーション、瓶数、使用材料・燃料の上限を指定する。アイテムIDだけではポーションを区別できないので、種類・延長・強化・容器を型付きの情報で照合する。標準の既知レシピを有限の工程へ展開し、各工程のserver更新を待ってから次へ進む。

両方とも投入・待機・回収を同じjobで扱う。取消後も設備がゲームの通常動作として加工を続ける場合があるため、「入力を止めたこと」と「設備内の処理が止まったこと」を分ける。結果には回収済み数量、残った投入物、確認できた処理状態と未確定部分を残す。取消を材料返却やロールバックの保証にしない。

## 取引の契約案

取引は `agent_interact` の延長にする。既存の `target:"entity"` に `operation:"inspect_offers"` と `"trade"` を追加し、利用側に画面内の座標・スロット・ボタン番号を組み立てさせない。

```json
{
  "target": "entity",
  "operation": "inspect_offers",
  "entity_ref": "<観測で得た相手の参照>",
  "advance": true,
  "max_ticks": 1200
}
```

このjobが相手へ接近し、通常操作で取引画面を開き、serverから届いた候補を取得して閉じる。`agent_get_action(include_result:true)` に価格、必要な品、完成品、在庫と新設の `offer_ref` を返す。現在の `merchant_offers` は「その画面が開いている間だけの情報」なので、閉鎖後に取得できるjob結果への保存を追加する必要がある。

```json
{
  "target": "entity",
  "operation": "trade",
  "entity_ref": "<同じ相手の有効な観測参照>",
  "offer_ref": "<inspect_offersの結果から得る新しい参照>",
  "count": 3,
  "max_total_cost": [{"item": "minecraft:emerald", "count": 9}],
  "max_ticks": 1200
}
```

`count` は取引回数で、完成品数とは分ける。この例の予算は3回の合計でエメラルド9個まで。2種類の支払品がある取引では両方を指定し、指定のない品を消費しない。種類が同じでもデータの異なる品を混同しないよう、候補参照が支払品と完成品の詳細な同一性を内部で保持する。

新設の `offer_ref` は、相手の同一性・world session・dimension・候補の内容・期限に結び付ける。既存の `known_menu.operation_ref` は開いたmenuに結び付いた別契約なので流用しない。再開封して得た新しい情報と照合し、最初の実行前に価格・在庫・候補が変わっていれば停止して再取得を促す。

反復中は自分が確定させた取引による在庫等の変化だけを追跡し、毎回の価格・予算・収容先を再確認する。通信応答が不明な確定操作は再送しない。相手が見えなくなった、死亡した、参照が失効した場合も、近くの別個体へ自動的に取引を振り替えない。

候補確認と実行はMCPの呼出しとして分けるが、毎回人間に確認ダイアログを出す設計にはしない。依頼を受けたLLMが、利用者の条件内で両方を実行できる。

## 金床とエンチャントの契約案

`preview_anvil` と `preview_enchant` は、指定アイテムを通常操作で投入して、表示可能な結果・選択肢・費用を取得するjobにする。単なる読み取りとは異なり、所持品の移動と画面操作を伴う。確定ボタンは押さず、投入物とcursorの返却・同期を確認して閉じる。返却先を確保できなければ投入しない。

入力品は所持枠に加え、server同期済みの品の同一性を示す新しい `stack_ref` で特定する。item IDだけで同種の別の剣や本を選ばない。金床は合成・修理・名前変更、経験値の上限、結果条件を明示する。名前は通常ゲームへ渡す文字列として扱う。

プレビューが返す候補参照を `anvil` / `enchant` へ渡す。実行時には再投入後の表示・費用を照合し、変更されていれば確定しない。金床では確定時の設備破損も結果に含める。エンチャントでは要求レベルと消費レベル・ラピス数を区別する。

エンチャントのプレビューは通常画面に表示される手掛かりまでに限る。隠れた乱数や未表示の付与内容を予測して保証しない。確定後に実際の付与結果を確認する。特定エンチャントが出るまで繰り返す処理は初期対応に含めない。

## 共通の結果と実装境界

新しいplan・inspect・previewも、実行する操作も、既存と同じ `action_id` と有限予算を使う。結果は `agent_get_action(include_result:true)` で取得する。新しいscript命令も各機能と同時に追加し、`craft(...)` と既存 `interact(...)` から同じ検証・実行器へ接続する。動的な観測値を使うscriptの拡張は後の段階でも、固定引数での合成は最初から可能にする。

生産系結果には、少なくとも次を機械可読で残す。正確なfield名・上限は各実装時のschemaで固定する。

- 要求数、確定した操作回数、回収済み完成品数、消費を確認した材料・燃料・経験値。
- 設備内の残留物、操作前後の所持品配置の変化、未確定の送信と数量。
- 画面・cursor・入力の解放状況、停止理由、再観測または再計画が必要な箇所。

参照はclient側で実際に取得した情報にだけ発行し、内部の生NBTや任意packetの指定を公開しない。計画・プレビューは確定実行前に再検証し、数量や費用の確定は新しいserver同期を根拠にする。停止では新たな製作・確定を止め、所有する入力と画面を解放してからjobを終える。解放確認が取れなければ、その問題を保持して次の作業を開始しない。

材料配置や結果回収に必要なslot計画・同一性照合・cursor後始末を最初のクラフトで整える。ただし、汎用GUIフレームワークや完全なインベントリ整理を先に作ることは着手条件にしない。完全な永続化は後段でも、確定済み・未確定・未実行を区別する結果は最初から必要になる。

## 実装順序と受入条件

各段階で公開catalog・runtime・schema test・利用ガイド・script接続を一緒に揃える。使えない操作を先に実行可能として広告しない。

| 順序 | 範囲と公開先 | 最低限確認する動作 |
| --- | --- | --- |
| 1 | `agent_craft`。通常2×2・3×3 → 戻り容器・データ付き材料 → 有限の連鎖クラフト | 定形・不定形、複数回、材料不足、満杯、余剰数、材料の変更、取消・応答不明時に二重製作しない |
| 2 | `agent_interact` の製錬 → 醸造 | かまど・溶鉱炉・燻製器、燃料不足、投入後取消、ポーションの種類・工程・瓶数、残留物と回収数 |
| 3 | 同ツールの取引 → 金床 → エンチャント | 価格・在庫・相手の変更、2種類の支払、品の取り違え、経験値不足、候補変更、費用上限、途中取消 |
| 4 | `agent_break_block` のMODブロック対応 | 固定namespace制限を実際の破壊可否と通常入力へ置換。対象MOD、道具、保護・server拒否、block entity、複数cell構造の影響を確認 |
| 5 | `agent_move` の水中移動 → 乗り物 | 呼吸・浮上先・水流・陸水の遷移。搭乗/降車は `interact`、移動は `move`。ボート・トロッコ・騎乗動物を同じ移動モデルと仮定せず段階対応 |
| 6 | `agent_inventory` の整理 | 正確な数量移送、分割、任意枠、装備・offhand。同じIDで異なるデータ、満杯、取消時のcursor回収 |
| 7 | MOD収納 | 既存backpackの初期化・応答遅延・upgrade付き収納を確認し、利用対象MODごとにproviderを追加。未知menuを既知profileとして扱わない |
| 8 | 条件・script | 有限のAND/OR/NOT、可視entity状態、client tickで取得した値の読み取り、構造化した操作結果と限定的な失敗分岐。未確定操作の自動再送を避ける |
| 9 | 保存・再開 | 設置用runnerを生産・移動・採掘へ拡張。確定記録と未確定送信を保存し、session変更時に参照と現状を取り直す。未確定の取引・製作を再送しない |

水中・乗り物対応にエリトラ飛行を自動的に含めない。MOD収納やMOD固有の破壊挙動は対象MODの選定が必要で、全MOD対応を一括で完了としない。再開も在庫差分だけで過去の取引成功を証明できるとは限らず、証拠不足なら要確認として停止する。

## クリエイティブを別枠で進める範囲

飛行移動は実装済み。残る破壊・設置・ブロック操作は、生産系の公開設計と独立して進める。現状は入口3か所だけでなく、`MinecraftStationaryBreakPort` 内部にもサバイバル限定条件がある。収納では接地・ゲームモードの条件も残り、入口の判定変更だけでは完了しない。

実装順は、既存の専用APIで接地中のcreative操作を成立させ、飛行姿勢での照準・設置・操作、収納の順に確認する。材料数が減らない設置、通常のcreative破壊可否、途中のモード変更、意図しない飛行下降、server拒否を検証する。スペクテイターでのworld変更を許可するものではない。creativeの未所持アイテム取得は、破壊・設置のモード対応と別に能力・契約を定義する。

利用者のPCを占有しないコード変更・単体/ハーネス試験を先行させ、実ゲームは許可済みの隔離Dockerで行う構成とする。repo方針に従いsubagentは使わず、段階ごとに作業する。通常profileの画面操作とJAR差し替えは、この設計作業には含めない。

## 変更対象と検証の境界

| 対象 | 主な接続先 |
| --- | --- |
| 公開・受付 | `mcp/McpToolCatalog`、`McpToolSchemas`、`McpRuntimePort`、`McmcpToolRegistry`、Tool Catalogと固定hash・schema tests |
| v2実行 | `runtime/McmcpRuntime`、既存のv2引数・job・menu ownershipと同期処理、`agent/script` の命令検証・結果受渡し |
| クラフト・加工 | `observation/ClientRecipeCatalog`、`routine/MinecraftPhaseFiveInventoryPort`、`MinecraftKnownFurnacePort`、`MinecraftKnownBrewingPort`。旧routineの前提をそのままv2仕様にはしない |
| 取引・特殊設備 | `runtime/MerchantOfferSignals` / `MerchantOfferView`、新しい候補参照、通常menu操作とserver確認。金床・エンチャントは個別adapterを追加 |
| 利用と実機 | v2クイックガイド、対応する隔離fixture・runner、成功/停止/部分結果と復旧の実験記録 |

実装時はrepo所定の `test harnessTest adminBridgeTest verifyHarnessIsolation build` とsource checksを実行する。各機能で通常成功だけでなく、取消・Esc・UI OFF・world変更・遅延・満杯など、その操作に関係する境界を隔離環境で確認する。ビルド成功、実ゲーム合格、試用JARの導入確認は別々に記録する。

## 現行実装への参照

- [公開ツール定義](MCMCP_MCP_Tool_Catalog.json)、[v2の目標と現状](PUBLIC_API_V2.md)
- [現在のinventory操作](../src/main/java/dev/aod/mcmcp/runtime/V2InventoryRequest.java)、[menu引数](../src/main/java/dev/aod/mcmcp/runtime/V2MenuArguments.java)、[entity引数](../src/main/java/dev/aod/mcmcp/runtime/V2EntityInteractArguments.java)
- [内部のクラフト条件](../src/main/java/dev/aod/mcmcp/routine/InventoryParameters.java)、[既知レシピ](../src/main/java/dev/aod/mcmcp/observation/ClientRecipeCatalog.java)、[取引内容の型](../src/main/java/dev/aod/mcmcp/runtime/MerchantOfferView.java)
