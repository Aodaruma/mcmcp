# 公開API v2：今回の対応範囲と試用メモ

2026-10-10。公開15ツール。入力の正本は[Tool Catalog](MCMCP_MCP_Tool_Catalog.json)、詳細は[追加機能ガイド](PUBLIC_API_V2_PRIORITIES_20261009.md)、実機の確認範囲は[検証記録](experiments/20261010_v2_priorities_local.md)を参照。

取引に加え、エリトラ装備時の段差移動、レバー／ピストン観測、同一接続のディメンション移動でのMCP ON引継ぎ、scriptの `report(value=...)` を実装・隔離検証し、通常profileへ導入済み。[追加修正の仕様と残件](PUBLIC_API_V2_MAP_ART_FIXES_20261010.md)。

後続の `ladder-r3` では、梯子への下降開始と、高低差11ブロックの上下移動を修正し、差し替え担当が通常profileへ導入した。その後、現場の壁に囲まれた入口で停止する別の問題を再現した。追加版 `ladder-field-r7` は梯子の側面へ身体が当たる前に横位置を合わせ、隔離Dockerの8ケースに合格した。06:03 JSTに差し替え担当が通常profileへ導入・サーバー再接続・MCP ONまで確認し、現場確認は地図アート担当へ引き継いだ。[梯子の検証記録](experiments/20261010_v2_ladder_docker.md)。

| 優先項目 | 現在の範囲 |
| --- | --- |
| 1. クラフト | 手元2×2・作業台3×3、数量計画、名前・耐久値・エンチャント付き材料、戻り容器、途中取消。特殊なデータ転写レシピ・連鎖クラフトは対象外 |
| 2. 製錬・醸造 | `agent_interact`の`smelt`・`brew`。既存の通常かまど／醸造台による単工程。MOD専用設備や複数工程の自動計画は未対応 |
| 3. 取引・金床・エンチャント | 候補確認・費用上限付き取引を実装し、隔離実機で検証・通常profileへ導入済み。金床・エンチャントは未実装。[取引ガイド](PUBLIC_API_V2_MERCHANTS_20261010.md) |
| 4. MODブロック破壊 | 登録済みblockを通常攻撃で破壊。硬度・道具・server更新を確認。実機はSophisticated Backpacksで確認。独自の分解・レンチ操作は未対応 |
| 5. 水中・乗り物 | 水中の上下・出入口・酸素退避、通常ボート／トロッコ／操縦可能な馬の移動。降車には静止と安全な乾いた足場が必要 |
| 6. インベントリ整理 | 同じデータのstack統合、指定枠のsort、満杯での交換、装備・オフハンドへの数量移送 |
| 7. MOD収納 | 対応providerのSophisticated Backpacks／Core。全MOD収納への一般対応、新品収納の自動初期化は未対応 |
| 8. 条件・スクリプト | all/any/not、player・観測済みentity、観測／行動結果による分岐、外部source・JSON/CSV。未知の情報は未知として扱う |
| 9. 保存・再開 | PC側の施工runnerと分割blueprintのcheckpoint。確定済み設置を飛ばして続行。script変数・実行位置自体の永続化は未対応 |

移動は既定で経路を再計画する。`auto_replan:false`で無効化できる。障害物の破壊・足場補充は任意の`clear_path`で、既定false。視点だけを向ける`agent_look`、はしご・通路・設備への接近、creative/spectatorの飛行も先行実装を含む。

## 使い方の入口

- 製作：`agent_get_state`でレシピを調べ、`agent_craft`に参照・数量・作業場所を渡す。[生産系ガイド](PUBLIC_API_V2_PRODUCTION_20261009.md)
- 整理：`agent_inventory`に`{"operation":"sort"}`、または`{"operation":"consolidate","slots":[0,1,2,9,10,11]}`。
- 外部データ：ゲーム内の`mcmcp/scripts`へsourceとJSON/CSVを置き、`agent_run_script`の`source_file`／`data_files`へ相対pathを渡す。設定でresource rootや容量を変更可能。データ中の文字列をコードとして実行しない。
- 128×128×9の地上絵：`tools/building/compile_v2_blueprint.py`で分割し、`Invoke-McmcpV2Blueprint.ps1`で施工。147,456セルは既定36分割。全量の計画生成は検証済み、全量のゲーム内施工は未検証。

操作は`action_id`で追跡し、`agent_cancel_action`で取消できる。確認できない変更は自動再送せず、停止理由と確認済み数量を返す。酸素退避は元の目標の成功とはせず`air_supply_retreat`となる。終了後は通常の沈下が再開するため、続けて岸へ移動するか手動操作へ移る。

通常導入済みR7では、梯子で別の階へ移るときは先に梯子近くの既知の足場を目標にし、その後で遠い目標へ移動する。遠い下階を直接指定した際に上階を探索し続ける問題は、未導入候補R8で修正・隔離検証済み。次の機能候補と合わせて引き継ぐ。

## 導入状況

**最新は `ladder-field-r7`（2026-10-10 06:03 JST確認）。** SHA-256は `ee1a94e94cecdf3962b1cdc05fef0433b2836c8a2ae124399a6ce0d626aaa57c`。差し替え担当の旧版バックアップ・物理path/hash・交換後起動・新session・READY/jobなし・15ツール全schema照合の記録は `artifacts/v2-ladder-field-install-20261010`。Prism GUI起動・サーバー接続・MCP ONまで完了し、追加移動せず地図アート担当へ所有を戻した。以下は先行版の導入履歴。

`ladder-r3` は05:27 JSTまでに導入・再接続・MCP ONを確認。SHA-256は `55f1703ed44cd1cec6fd8b199d2702e5964a3278a26161352d9c4008aaea0a94`。記録は `artifacts/v2-ladder-install-20261010`。

**2026-10-10 03:38 JST、通常の「くらふとぶ！-v01.2」をR7から `merchants-r4` へ更新済み。** 先行R7は実機13ケースに合格後、検証profileの292ファイルを復元して導入した。今回もPrismが起動済みであることを確認して旧JARを退避し、物理path・SHA-256・製品JARが1個だけであることを確認して交換した。検証用harness／admin bridgeは通常profileへ入れていない。

通常profileをPrism GUIから起動し、03:40 JSTに公開15ツールの全入出力schemaがcatalogと一致することを確認した。endpoint所有JVMの作業ディレクトリと交換後の起動時刻も照合済み。タイトル画面・MCP OFFで引き渡し。試用時は利用者がworldへ入り、MCPをONにする。導入JARのSHA-256は `3a6e9fe6028b5e7a4d9d0cd509fef395ad306407c3679ab1769b44dacf9bd720`。旧R7のバックアップと導入・API照合記録はローカル `artifacts/v2-merchants-install-20261010` に保存した。

先行のaod-mimoid Docker環境も、2026-10-10 02:34 JSTまでに元world・設定・JARの66ファイルをハッシュ一致で復元し、検証containerとDocker Desktopの停止を確認した。

main統合・タグ・Release公開は行わず、Draft PR #85を維持する。
