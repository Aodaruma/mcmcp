# Container preflight 拒否の診断追加（2026-09-06）

## 記録から確認できたこと

対象は rc.1 の整理操作。operator が保存したローカル記録
`C:/Users/aod/Documents/Codex/2026-09-05/mine-2/work/sort-live-checkpoint.json`
（保存時刻 2026-09-06 00:01:07 JST）の必要な項目だけを集計した。

- `ledger` は396件。成功306件、Action開始後の失敗21件、開始前のエラー69件。
- 開始前のエラーのうち、複合メッセージ付き `SAFETY_PRECONDITION` は52件（take 32件、store 20件）。`TARGET_UNKNOWN` は16件、その他1件。
- 最後の成功は **2026-09-05 23:58:33 JST** の `illagerinvasion:primal_essence` store（ledger index 380）。その後の記録15件は `SAFETY_PRECONDITION` 14件、`TARGET_UNKNOWN` 1件。
- 保存時は READY、非pause、health 20、hunger 19。overworld、位置 `(158.5885406288177, 64, -301.47594193654163)`、client tick 60488、world revision 615066。
- operator は fresh inspect/take/store での持続的な拒否と navigate の成功を報告している。ただし、このledgerの通常項目はtake/storeであり、報告された全inspect/navigateを記録件数へ加算していない。

旧メッセージは「world / local control / pose / observation / policy のいずれかが変化した」としか記録していない。そのため、このcheckpointから実際に失敗した条件を特定することはできない。

## 空腹度と未確定の候補

`admissionFenceCurrent` の旧実装は空腹度の値を直接比較していない。`policySnapshot` はHUNGERを提供するが、admissionはDSLのif条件が参照する値だけを使い、最初に実行するprimitiveが変わった場合に拒否する。`LocalObservationProjector.currentSafety` は局所hazard、衝突・移動中和、load/fluidの不明状態を評価し、空腹度を参照しない。したがって、hungerが20から19へ減ったことだけでは、無条件のinspect/take/store一律拒否を説明できない。

未確定の候補は次のとおり。いずれも今回の根因と断定していない。

- poseの厳密一致、control epoch、position correction、局所安全状態のcaptureから予約までの変化。
- 配送済み静的表面のrevision barrier更新や、予約時の有効な表面不足。
- capture時の内部再観測では有効だった表面が、予約tickではrenderer fog欠測のため再観測できなくなる可能性。`reobserveForPlanning` の新しいrecordはそのplanning view限りであり、欠測時には元のrecordのtick/revisionを保つ。描画とtickの位相でこの経路が起こり得るが、保存記録にfog sampleの有無がなく、今回発生したかは不明。

## 次のJARに含める診断

複合booleanを `admissionFenceFailure` に変更し、既存の全条件と評価順序を維持したまま、最初の失敗を固定reasonで返す。world/session、player、control mode/epoch/capabilities、pose、local safety、camera/multiplayer policy、observation、position correction、predicate policy/branch、route、known target/facing surface/surface、visible item/batch、break preconditionを区別する。

予約時は既存 `unsafe_state` → 公開 `SAFETY_PRECONDITION` とrecoverableを維持し、メッセージ末尾へ `Reason: known_surface_changed.` 等を追加する。実行開始直前は既存 `WORLD_CHANGED` を維持し、evidenceへ `admission_known_surface_changed_before_execution` 等を入れる。reasonは固定enum由来で、入力値、座標、例外本文を反射しない。

`McmcpRuntimePublicErrorContractTest` に、全reasonが既存の公開エラー変換を通過して区別でき、メッセージとevidenceの長さが有界であることを確認する回帰テストを追加した。担当agentは `git diff --check` のみ実行し、Gradle・ゲーム操作はしていない。統合担当でcompileJava成功を確認済み、統合testは実行中。

次回は差し替え後に最小のinspectを行い、固定reasonから原因を絞る。根因未確定の段階では、fog鮮度、revision、pose、安全条件を緩める変更や盲目的な自動retryを追加しない。
