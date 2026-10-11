# プレイヤー収納の同期診断（2026-10-11）

## 現場報告

nav-r14で携帯収納の確認・移送・終了は成功する一方、`agent_craft` のplan/executeと `agent_inventory operation=move` が `inventory_server_snapshot_required` で拒否された。swapは成功してもクラフトは復帰せず、利用者操作による切断・再接続後、新session・新しいrecipe参照でもplanが同じ理由で拒否された。確定した材料消費はない。現場の通常ゲームは別担当が所有し、この調査では入力・交換・再起動しない。

## 診断候補

クラフト・数量移送・整理は、プレイヤーmenu全体のserver受信記録とlive slot/cursorの一致を要求する。従来のエラーは記録欠落と内容不一致を区別しなかった。

- `agent_get_state({"sections":["inventory"]})` に読み取り専用の `inventory_sync` を追加。
- craft/move/sort/consolidateの開始時同期拒否では、`result.inventory_sync` にその時点の診断を保存する。後日のstate診断と失敗時の記録を区別できる。
- `reason` は `player_unavailable` / `snapshot_missing` / `menu_identity_mismatch` / `slot_count_mismatch` / `slot_mismatch` / `cursor_mismatch` / `matched`。
- `active_menu_is_player`、双方の枠数・cursor空状態、最後に受信したplayer full packetの枠数・tick、snapshotのtick・revision、cursor証拠revisionを返す。未受信のfull packetは枠数・tickが-1。
- 枠の不一致は総数と先頭最大8件を返し、打切りを明示する。各件はmenu内の枠番号・item ID・個数・item/components一致の真偽のみで、生のcomponents・NBT・表示名は返さない。menu枠番号はプレイヤー所持枠番号とは異なる。
- `matched` は内容の一致だけを示す。cursorが空でない場合、別menuを開いている場合、その他の受付条件を満たさない場合に行動を許可するものではない。

安全ゲート、server受信記録の作成条件、入力処理は変更していない。診断はserver状態の更新要求を送らず、live内容をserver証拠として登録しない。**この段階は原因を切り分ける候補であり、同期不具合の修正完了ではない。**

## 検証と次の確認

不足・menu/枠数/cursor/数量/components不一致・一致・打切りの単体試験と、公開schemaの件数上限・生components拒否を追加。Java 1,812、harness 14、admin 28、合計1,854件と必須build・harness隔離が成功した。

このPCの専用検証profileは、空き物理メモリ1,199 MiBのためbackup・設定変更・起動の前に準備を中止。通常ゲームには入力していない。利用者が継続許可したaod-mimoidの隔離Dockerで、起動直後・fixture設定後・クラフト／移送／バッグ開閉／次元移動後の公開診断を確認する。実機結果は追記する。通常worldやprivate logを試験資料へ流用しない。

## 隔離検証の結果（15:42 JST完了）

製品source `a60cc2d0af1904006717f75f614e00489c91b180`、CI 38118062236のJAR SHA256 `c0656cb4b72b6ea64d57f28861185556726a7cc47bb54f3182d99efd66068fea` をaod-mimoidで検証。公開16ツールの全定義がJAR内catalogと一致した。

- fixture前の初期診断はmatched、full packetは46枠・tick42、不一致0。
- 名前付き材料の2x2（板8個）・3x3（かまど1個）のplan/execute、原木3個の数量move、ケーキ2個のplan/executeとバケツ6個の返却、120枠バッグinspectの計8jobが成功。各終了後はmatched、cursor空、解放済み、体力20。
- End移動後は新sessionでfull packet tick3925、Overworld復帰後はtick4139。両方matched。次元移動は隔離fixtureによるもので、今回Portal操作を再検証したものではない。
- 収納はinspectによる開閉まで。take/store・バッグ自動補充後の同期は未検証。通常現場の不具合は再現せず、修正完了とはしない。
- 元66ファイル（world61）を全hash一致で復元し、fixtureを撤去。15:42 JSTまでにcontainer/Docker/WSL停止とlease解放、16:10の期限内完了を確認。

証跡はaod-mimoidの `F:/mcmcp-testlab/20260902-hard-building-v1/eval-artifacts/20261011-v2-inventory-sync`。ローカルの `artifacts/v2-inventory-sync-20261011/remote-verification.json` に公開job要約と最終receipt検証出力を回収した。診断候補は通常profileに未導入。利用者の後続指示で床延長のv2統合を優先し、同期調査はこの状態を保存する。
