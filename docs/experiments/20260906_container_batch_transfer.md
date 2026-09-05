# コンテナまとめ移送 / Bounded container batch transfer

倉庫整理で同じ品目を複数stack移すたびに開封・再開封していたため、通常QUICK_MOVEを同じ所有menu内で順に実行する方式へ変更した。Shift＋double click相当の効率を、有限予算とserver確認を保って実装する。

また大チェストに既に丸石2,464個がある場合、2個を追加する絶対goal 2,466を入力上限2,304が拒否していた。store goalを54×64=3,456に広げ、takeはplayer36slotの2,304を維持する。

## 公開契約 / Public contract

| 入力・予算 / Field | 値 / Value |
| --- | --- |
| `max_stacks` | 1〜14、既定1 / default 1 |
| `max_transfer_count` | 1〜896、既定64×max_stacks / default 64×max_stacks |
| 絶対goal / Absolute destination goal | take 2,304、store 3,456以下、実menu容量も検査 |
| interaction予約 / Reserved interactions | 2 + max_stacks |
| Action時間 / Action ticks | 600 + 60×(max_stacks−1)、msは50倍 |
| 内部操作時間 / Operation ticks | 400 + 60×(max_stacks−1) |

引数省略時は従来の1stack・64個上限を保つ。item IDとstack_policy、配送済み照準点・camera・world/画面所有権の条件は維持する。

## 実装 / Implementation

- 初回server同期でsource slotとitem/componentsを固定する。自動補充によるstackは計画へ追加しない。
- 各stack全量が移送先と残る個数予算へ収まることを確認し、通常のQUICK_MOVEを1回だけ送る。
- server側のslot差分からsource全量消失と同じ成分のdestination増加、他slot不変、空cursorを確認する。次のクリックは別client tickで行う。
- 60ticks以内に確認できないクリックを再送しない。同じcontainerの最終full-content再開封で結果が確定する場合だけ回収する。
- 最終readbackは全batchで1回。途中で停止した場合、確認済みprefixはCONFIRMED、未確認の末尾1クリックだけUNKNOWN。読み戻していないafter値を生成せず、cleanup再試行で重複記録しない。

The batch fixes its source slots from the initial server snapshot. Every ordinary QUICK_MOVE requires a complete matching server delta before another click, and one final reopen validates the batch. Interrupted batches preserve a confirmed prefix separately from the last unknown click. Unknown results are never blindly retried.

## 検証 / Validation

### 実ゲームで見つかった内部上限 / Internal goal limit found in game

大チェストの丸石2,464個へ2個を足す目標2,466で、DSL受理後のtick1に`PhaseFiveRequest`の旧2,304上限が例外となった（Action `c30e0921-0664-4da6-a2b5-60aa8394c756`、interaction/effectなし）。送り元からのtake2だけが適用され、2個は後で確認付きstoreにより送り元へ戻された。

内部requestも`transfer_items`かつ`player_to_container`だけ3,456を許可するよう揃えた。take・他操作は2,304のままにし、未知directionで上限を広げない。目標2,466と3,456がRoutine経由でserver確認後に完了するテスト、および上限超過・他direction/操作の拒否テストを追加した。

The DSL accepted a destination goal of 2,466, but the internal request still rejected values above 2,304 before dispatch. The internal ceiling now matches 3,456 only for transfers into containers; player destinations and other operations retain their existing limits.

修正後の統合検証はunit 1,189件・harness 13件・admin bridge 21件、計1,223件、build/isolationが成功した。実ゲームでの2,466目標の再確認は次JAR引き渡し後に行う。

候補2の実ゲームで、独立inspectによる送り元2個・大チェスト2,464個の確認後、take2→store2→再inspectにより大チェスト2,466個・手持ち0個を確認した。store Actionは`b2c1f7ec-677e-41b2-9721-8f0bd5ee904c`でCONFIRMED、cleanupも正常にREADYへ戻った。

### 初回まとめ移送 / Initial batch verification

境界値、旧入力互換、DSL往復変換、静的予算、複数stackと端数、成分混在、選択外変更、補充、fresh packet revision、部分確定とUNKNOWN、cleanup再試行の回帰テストを追加した。`test` 1,135件・`harnessTest` 13件・`adminBridgeTest` 21件、計1,169件が成功し、`verifyHarnessIsolation`と`build`も通過した。

`0.1.0-rc.2-SNAPSHOT` JARのSHA-256は`B8C4E411966A7208F31DF3225C1C14AB8389C24731277AB634BFAEE988793DBA`。実ゲームの再検証は別の倉庫整理タスクへ固定コピーを引き渡して行う。開封前の持続拒否は根因未確定のため、同JARに固定reasonを追加した（[診断記録](20260906_container_preflight_diagnostics.md)）。

実ゲーム担当から、`polished_diorite` 108個の2stack take（Action `b5f404c3-9617-45e3-927d-da42a02cd40a`）が52ticks・4interactionsで成功し、続くstoreも108個CONFIRMED、個人所持差分0との報告を受けた。記録上の全54収納・117 item IDsの照合でも個数差0だった（最終再inspectは未了）。その後、`deepslate`12個のstore（Action `b172beaa-04b9-41d7-a842-09dacfb07026`）ではtick60/interactions1で終了処理が停止し、操作OFF・cleanup失敗の反復が発生したため整理を中断した。まとめ移送の機能確認と終了処理の安定性は区別し、後者を修正してから再開する。
