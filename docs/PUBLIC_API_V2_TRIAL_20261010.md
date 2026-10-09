# 公開API v2：今回の対応範囲と試用メモ

2026-10-10。公開15ツール。入力の正本は[Tool Catalog](MCMCP_MCP_Tool_Catalog.json)、詳細は[追加機能ガイド](PUBLIC_API_V2_PRIORITIES_20261009.md)、実機の確認範囲は[検証記録](experiments/20261010_v2_priorities_local.md)を参照。

| 優先項目 | 現在の範囲 |
| --- | --- |
| 1. クラフト | 手元2×2・作業台3×3、数量計画、名前・耐久値・エンチャント付き材料、戻り容器、途中取消。特殊なデータ転写レシピ・連鎖クラフトは対象外 |
| 2. 製錬・醸造 | `agent_interact`の`smelt`・`brew`。既存の通常かまど／醸造台による単工程。MOD専用設備や複数工程の自動計画は未対応 |
| 3. 取引・金床・エンチャント | 追加開発で候補確認・費用上限付き取引を実装し、隔離実機で検証。通常profileはまだR7のため取引は未導入。金床・エンチャントは未実装。[取引ガイド](PUBLIC_API_V2_MERCHANTS_20261010.md) |
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

## 導入状況

**2026-10-10、通常の「くらふとぶ！-v01.2」へ最終候補R7を反映済み。** 実機13ケースに合格後、検証profileの292ファイルを復元。Prismを先に起動して元JARを退避し、物理path・SHA-256・製品JARが1個だけであることを確認した。検証用harnessは通常profileへ入れていない。

通常profileをPrism GUIから起動し、公開15ツールの全入出力schemaがcatalogと一致することも確認済み。タイトル画面・MCP OFFで引き渡し。試用時は利用者がworldへ入り、MCPをONにする。導入JARのSHA-256は `90a63cabbf3a1358ee176e06a218565fc108b3996ec3cd48c64242d2d1fbd69f`。

先行のaod-mimoid Docker環境も、2026-10-10 02:34 JSTまでに元world・設定・JARの66ファイルをハッシュ一致で復元し、検証containerとDocker Desktopの停止を確認した。

main統合・タグ・Release公開は行わず、Draft PR #85を維持する。
