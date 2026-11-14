# 既存セルを先頭に含む施工の停止（R24）

2026-10-13。通常R22bの施工担当から、切断後に新しく観測した既存black1マス＋未設置2マスを施工しようとすると、600 ticksの `duration_limit`、placed0／already_matching0で停止したとの報告を受けた。先行する15秒の `construction_deadline` と切断は[別の調査](20261013_v2_construction_deadline_triage.md)であり、今回の修正対象に含めない。

通常ゲームは施工担当が所有し、この開発チャットは照会・入力・再接続・再起動・交換を行わない。施工担当は既存1マスをfresh観測で個別に記帳し、支持中央へ移動後、次の2マスを新規Actionで4.505秒で完工した。保存済み `recovery-045563-verified-20261013.json` の成功結果と確定2座標を読み取り確認した。

## 原因と修正

`MinecraftV2PlaceDriver.beginObservedTarget()` は、新規設置に必要な支持面を探し、見つからなければWAITINGを返してから、対象が既に設計に一致しているかを判定していた。既存blockは隣の支持面を覆うため、対象自身の上面を観測できても、この既存一致判定に到達できない場合がある。近傍の床接近処理は支持の空きを待てる仕様なので、そのまま子のtick予算まで待つ。

R24は既存一致の判定を支持面探索より前に移す。current session・survival・loaded・interaction reach・frame存在・対象自身の現在LOS・状態一致・複数blockの相方確認を維持する。実際に新しく置く場合は従来の支持面・照準・予測・server確定を使用する。床延長優先とR22bの減速中の照準合わせも維持する。公開tool・引数・設定・期限は変更しない。

## 比較試験

許可済みaod-mimoid隔離Dockerで、現場と同じ相対位置の固定fixtureを使う。絶対座標を隔離領域へ移し、既存target `(312,120,310)`、続く未設置 `(311,120,310)`、`(310,120,310)`、player `(312.5596572615958,121,309.5364405872086)` とした。harnessの有界なphase記録とserver上の配置・所持品を照合する。

対照R23はR22bと同じplace driverで、親Action `e201b3ff-0030-450e-a76f-575c1571c2ad` が30.211秒で失敗。phase記録は `place.APPROACH=600`、確定設置・既存一致とも0、black64個不変、続く2マスはair、HP20・接地を確認した。R23の収納同期修正は変更せず、別のR24候補として扱う。

R24では同じfixtureの3マスを6.879秒で完工した。Action `7e05c69f-86f4-45d3-b379-4c275546bc16` は新規設置2／既存一致1／next_index3で、black所持数は64→62。接近は23 ticksで終わり、その後の通常の床延長で2マスを設置した。server上の3座標とHP20・接地を照合した。

| R24の条件 | 結果 | 新規設置／既存一致 | 所要時間 |
| --- | --- | ---: | ---: |
| 既存1＋新規2、advanceあり | 完工 | 2／1 | 6.879秒 |
| 既存1、advanceなし | 完了・材料消費なし | 0／1 | 0.252秒 |
| 既存3、advanceなし | 完了・材料消費なし | 0／3 | 0.349秒 |
| 対象が壁で隠れている | 期待どおり停止・変更なし | 0／0 | 4.182秒 |
| 既存対象が指定と違う色 | 期待どおり停止・変更なし | 0／0 | 4.199秒 |
| 通常の新規3床 | 完工 | 3／0 | 5.449秒 |

全6ケースでHP20・接地、終了時READY・Actionなしを確認した。hiddenケースは公開observeでも対象が `known=false` であり、透視して既存一致扱いしていない。全ケースのAction ID、数量、phase集計は `evidence/final-verification.json` に保存する。13:01 JSTに実行profileの製品・harness hashとsession `6ff2bba8-ace0-4d98-9a3f-8dc40b28dde4` を照合した。

Java 1,926件（unit1,884／harness14／admin28）、build、harness隔離が成功。source checks初回はPowerShell起動時の `InitializeDefaultDrives` に関するstderrが空出力検査に抵触した。製品・通信コードを変更せず再実行し、全source checksが成功した。初回ログも保持する。実機runnerの最初のobserveは `report` の名前付き引数漏れでscript_invalidとなり、world操作0のまま停止した。runner修正後に別のfixture要求番号で比較を開始した。

## 配布と限界

成果物は親mcmcpディレクトリの `artifacts/v2-existing-placement-20261013-r24/`。通常profileへ入れるのは `product-r24.jar` のみ。

- 製品SHA-256：`CEA58400CB5583C0036B0A2F8ACB9ABF37DF494A84561B35C8824E47B40D18F3`
- 隔離専用 `harness-r24.jar`：`3D68320BD67CA8FE7F7D7726BFD439367AE1C44AE7B4CDFA8A54BC26AFD15338`
- R23からの製品差分：`product-r23-r24.diff`。対応ソース・検証・復旧記録はsource ZIP、evidence ZIPとmetadataに保存する。

R24はR23のバッグ同期修正とR22bの高速化を含む別候補。凍結済みR23のJAR・source ZIP・証拠ZIPは変更していない。通常ゲームのR22bはこのチャットでは更新せず、施工担当が安全な区切りで導入を調整する。今回の修正は既存一致の前で待ち続ける問題に限定し、先行する15秒の `construction_deadline`、切断原因、失敗結果の診断情報不足は未解消。通常serverでの長時間運用や複数block設置の追加実機試験は行っていない。

隔離ゲームは保存終了し、world/session・実行中Actionがないことを確認。13:05 JSTに元66ファイル（world61を含む）のhash一致で復元した。13:07 JSTにDockerプロセス0・Ubuntu／docker-desktop両WSL停止・lease解放を確認した。証拠は `evidence/restoration.json`、`shutdown.json`、`complete.json` に保存する。Docker終了コマンドは完了まで約2分を要したが正常終了しており、強制終了は行っていない。
