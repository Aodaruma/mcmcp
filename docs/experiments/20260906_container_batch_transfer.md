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

境界値、旧入力互換、DSL往復変換、静的予算、複数stackと端数、成分混在、選択外変更、補充、fresh packet revision、部分確定とUNKNOWN、cleanup再試行の回帰テストを追加した。`test` 1,135件・`harnessTest` 13件・`adminBridgeTest` 21件、計1,169件が成功し、`verifyHarnessIsolation`と`build`も通過した。

`0.1.0-rc.2-SNAPSHOT` JARのSHA-256は`B8C4E411966A7208F31DF3225C1C14AB8389C24731277AB634BFAEE988793DBA`。実ゲームの再検証は別の倉庫整理タスクへ固定コピーを引き渡して行う。開封前の持続拒否は根因未確定のため、同JARに固定reasonを追加した（[診断記録](20260906_container_preflight_diagnostics.md)）。
