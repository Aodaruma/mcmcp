# 2026-10-10 収納の同期不一致と終了待ちの修正

## 発端と原因

通常R15の地図アート担当から、バッグの食料take2が `known_menu_update_deadline_exceeded`、`confirmed_count:0`、`unconfirmed:true`、`release_pending:true`、`release_fault:true` のまま残る報告を受けた。状態取得・MCP状態・1回の取消も `SAFETY_PRECONDITION` で拒否され、`get_action` の保持結果だけ読めた。takeは再送していない。通常ゲームの操作権は地図アート担当が保持する。

コード上は、保留中の緊急停止が同epochで後から届いた状態取得・取消まで毎tick破棄する問題と、収納の終了処理がcursor回収にも移送計画の全slot一致を要求する問題を確認した。並行在庫変化によって数量確認だけでなくcursor解放まで待ち続ける場合があった。

現場の食料減少が自動給餌によるものだったかは未確定。給餌upgrade付きでも在庫が安定したtake2は修正前後で成功した。今回は固定fixtureで操作途中の在庫変化を注入し、同じ同期期限エラーを再現した。

## 修正後の契約

- 停止保留中も新しい同epochの `agent_get_state`、`agent_get_mcp_status`、`agent_cancel_action` と内部routine取消を受け付ける。旧epoch、world変更、期限切れ、shutdownの拒否を維持し、新規行動は通さない。
- 移送数量の照合は厳密なまま。終了時だけ、同じ所有menuの新しいserver証拠から、予測とitem・components・数量が一致するcursorを元slotへ1回返せる。元slotのitem不一致・容量不足・古い証拠・予想外のcursorでは返さない。
- cleanup clickも再送しない。新しいserver証拠でcursorが空になったことを確認して画面を閉じる。この確認だけで移送を成功にしたり `confirmed_count` を増やしたりせず、未知のeffectsと `unconfirmed:true` を保持する。
- cursorが本当に不明な場合の `release_pending` は維持する。強制解放や未確認takeの自動再送は行わない。

公開15ツールの名前・入出力schemaは変更していない。

## 隔離Dockerの結果

新しいバックアップ `20261010-v2-storage-recovery` を作成し、aod-mimoidの既存隔離profileで確認。通常profileは操作していない。製品R17と最終候補R18は同じJARで、R18では試験専用harnessにcursor不一致ケースを追加した。

| ケース | 結果 |
| --- | --- |
| 給餌upgrade付きバッグ、食料668からtake2 | succeeded、バッグ666・手元2、cursor空、体力20 |
| 最初のpickup中にfixtureが元食料を1個減らす | 約3.40秒でfailed、数量未確定を保持、release_pending=false／release_fault=false。server側はバッグ667・手元食料0・cursor空・体力20 |
| 448個移送のexact_update中に取消 | cancelled、cursor回収・再openの収支・upgrade／タブ設定保持を確認 |
| 32倍バッグ、pickupタブ開放でtake24／store16 | 成功、再openの数量保存・cursor空・体力20・タブ保持 |
| pickup後のcursorを予測と違う数量へ変えるfixture、Esc停止 | UNKNOWN／release_pendingを維持したまま状態取得とMCP状態が成功。1回の取消を受け付け、cancel_requested=true |
| 解放できない状態からGUIのMCP OFF→通常のワールド終了 | world_boundaryでfailed、UNKNOWNを保持、release_pending=false。タイトル画面でOFF／worldなし／jobなし |

cursor不一致fixtureはserverでcursorから1個を元slotへ戻す固定操作で、総数を維持する。実際の給餌をこのタイミングで発火させた試験ではない。最後のケースでは画面閉鎖で残り63個が手元へ戻ったため、要求2個の成功とは扱わない。

Java 1,707＋harness 14＋admin bridge 28＝1,749件、build、harness隔離が成功。追加単体試験は停止保留中の診断・取消、旧world／epoch／期限拒否、在庫変化後のcursor回収と数量未確定の保持、古いcursor証拠・違うitem／数量・容量不足の拒否を確認した。

最初の取消runnerは試験ディレクトリにcatalogがなく `catalog_unavailable` で開始前に失敗した。正本catalogを配置した再実行が上表の合格結果。失敗runを製品不具合や取消成功に数えない。

製品SHA-256: `6ebd98e76dadc0e6333a9879138064a8494e3b10d663f83fe2a5f68092dea13a`。
試験harness SHA-256: `5ebfd35d0997869ef2dfa448e4d341b0bc4351343f6ad16d2181128033b7fa86`。harnessは通常profileへ入れない。

11:35 JSTに元66ファイル（world 61件を含む）のhash一致を確認し、fixture除去・container停止まで完了した。証拠はローカル `artifacts/v2-storage-recovery-20261010/r18-evidence.zip`、復元証跡は同ディレクトリの `restoration.json`。

## 通常R15の復旧と導入

修正JARを準備しても停止中のR15 JVMへ後付けできない。現行公開APIで安全に強制解放する経路は確認できていない。通常の復旧・導入はまだ実施していない。

1. 地図アート担当の停止・装置OFFと、差し替え担当への操作権引継ぎを確認する。
2. 未確定jobのID・元数量・effectsを保存し、take2を再送しない。許可された差し替え担当がGUIのMCP操作をOFFにしてから、通常の画面閉鎖・サーバー切断を行う。Escだけでは終了待ちが残り、通常のmenu clickも遮断され得る。画面閉鎖でcursorが手元へ戻ることがあるため、要求数量どおりとは仮定しない。強制終了・稼働中JARの上書きは行わない。
3. 通常終了を確認し、Prism起動下で元JARを退避して製品JARだけを更新。再接続・MCP ON後、新JVM/session、候補hash、公開15ツール、READY／jobなしを確認する。
4. 地図アート担当へ操作権を返し、fresh state／inventoryとバッグinspectで実数を照合する。今回の取引は数量不明のまま区切り、取得済み分を再送しない。体力・食料・装置OFFも再確認する。

通常サーバーでの復旧、通信遅延下の同時更新、任意upgrade・手動cursor操作は未検証。上段chestへの接近が屋上を選ぶ問題、被ダメージ原因の診断、scriptの停止child結果保持は別の残件。
