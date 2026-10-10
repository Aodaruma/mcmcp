# 2026-10-11 公開API v2: 設置収納と経験値

Issue #84 / Draft PR #85。看板・NBT対応 `a3e3593` の後続。[利用ガイド](../PUBLIC_API_V2_STORAGE_XP_20261011.md)。公開ツール数は16個を維持。

## 問題と変更

設置したSophisticated Backpacksは、座標指定収納のVanilla専用経路では `unsupported_container`、汎用メニュー経路でも `menu_context_changed` となり、在庫を読めなかった。携帯バッグで使っている既知MODメニュー実行器に、設置位置・BlockEntity・内容UUID・BlockContextを照合する入口を追加した。通常のブロック使用で開き、液体容器や独自の先行use hookを避けたメインハンドを選ぶ。移送は既存の数量計画・server同期・再開封readback・cursor解放を使う。

利用者の希望により、設置収納の確認・数量指定移送を `agent_interact target:block operation:inspect_storage|transfer_storage` にまとめた。チェストと設置バッグで同じ形を使い、既存 `agent_inventory target:container` も共通処理へ接続して互換維持。携帯バッグは従来通り `agent_inventory target:storage`。

stateに現在のXPポイント・レベル・進捗を追加した。累計取得値を残高として扱わず、同期HUDからVanillaの曲線で現在値を再構成する。Waystonesのコスト情報から従来は捨てていた `available` も、単位別に公開する。値がない場合は0としない。

## ローカル検証

`gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build` 成功。製品Java 1,776、harness 14、admin bridge 28、計1,818件。追加7件で、XP曲線の境界・不正/過大値・公開schema、サーバーの費用/残高と複数費用、収納interactionの入力と旧inventory経路との同一性を確認した。

`pwsh tools/check-source.ps1 -SkipJava -PythonExecutable python` のtransport・mock・source検査も成功。変更範囲をレビューし、対象の同一性、数量照合、取消・解放、不明残高と0の区別を確認した。

## aod-mimoid隔離Docker

最終候補 `placed-storage-xp-r2`、専用validation profile、Minecraft 26.2 / NeoForge 26.2.0.59。通常profileの操作・差し替えは行っていない。最終候補で27項目の実機検査が成功した。

| 確認 | 結果 |
| --- | --- |
| 設置netherite backpack、120枠、stack tier2×2+tier1、pickup+advanced magnet | server同期から白コンクリート2,048個・黒64個を取得 |
| 設置バッグの枠数 | 全120 / 使用2 / 空き118。フィルター指定時も全体枠数を維持 |
| 白65個take、黒32個store | 数量確認・再開封readback・メニュー解放成功 |
| 再読取り・fixture側照合 | 白1,983 / 黒96、バッグ本体が残り、cursor空 |
| 旧inventoryの座標指定inspect | 同じ設置バッグの確認成功 |
| block条件不一致・在庫にないアイテムのtake | 未移送、unconfirmed=false、解放完了で失敗終了 |
| 携帯バッグのinspect | 従来経路で成功、120/2/118枠も取得 |
| チェストのinteract inspect→diamond3個take | 7→4個。旧inventory inspectでも一致 |
| stateの30レベル・進捗0 | 現在1,395pt、次レベル112pt。player/world一致 |
| Waystonesのserver残高 | 1,395pt。Overworld試験行0pt、End試験行27pt |
| 27ptでEndへ実転送後 | 新session、READY、29レベル・進捗0.74766356・現在1,368pt。server HUDと一致 |
| 設置バッグopening中の取消 | cancelled、release_pending=false、在庫不変、cursor空、READY |

取消検査ではメニューが短時間で閉じるため、同じ接続設定と認証を保持するtransportでopeningを捕捉した。最初の低頻度監視では確認処理が成功終了してしまい、取消合格には数えていない。

公開16ツールの定義・source catalog・製品JAR内catalogが完全一致すること、製品にfixture/admin bridgeが混入していないことも確認した。

## 成果物

製品JAR: `mcmcp-neoforge-26.2-0.1.0-SNAPSHOT.jar`

SHA-256: `65476399492848ea093daf38f216af8a56e3efbafd9c8d82f834cc500e0d14e4`

ローカルの `artifacts/v2-placed-storage-xp-20261011/release/` に試験済みJAR・catalog・利用ガイド・hash/ソース対応情報をまとめる。認証情報、world、private log、画面画像はcommitしない。通常profileの導入・起動は利用者側で行う。main統合・タグ・Release公開は行わない。

MCP OFF・セーブ終了後にcontainerを停止し、元world 61、設定3、元JAR2の計66ファイルをSHA-256一致で復元した。追加fixture除去、worldファイル数とMOD集合も照合した。2026-10-11 04:59 JSTにDocker Desktop関連プロセス0、検証lease解放を確認した。

## 未確認・残件

外部マルチプレイ、未知のMOD/別版、リンク・入れ子収納、Creative/Spectator収納、稼働中マグネットへの連続流入と移送の競合は今回の実機対象外。空き枠は格納可能個数の保証ではない。並行更新で数量が確定しなければ成功とせず、再観測が必要。通常環境の従来の収納解放待ち全体を解決したという扱いにはしない。

追加報告として、足場上昇のmove停止、設置時のしゃがみによる足場下降、comparator上の低い段差での移動停止、破壊失敗・broken_blocks=0でもfresh観測ではairになっていた事例を残件として保持する。今回これらの現地操作や修正はしていない。破壊・移送の不明結果は再送せず、現在の観測結果を基に別の計画を立てる。
