# R18 破壊failed後の対象消失（調査待ち）

2026-10-10 14:06 JST、地図アート担当の通常R18からの報告。証拠は `_PLAYING/map-art-MFwtpM21wWc-3x3/duper-operation/r18-lime-to-white-seed-20261010.json`。現場は復旧しており、再起動・追加JAR導入は不要との連絡。開発担当による通常ゲームへの入力は行っていない。

## 保存証拠で確認したこと

- Overworld `(-1969,-40,-1535)` を直前にobserveし、`known:true` の `minecraft:lime_concrete_powder` を確認。
- `agent_break_block`、`advance:false`、`max_blocks:1` のaction `9d71f2f2-c5cd-42f6-8c04-77f9e6f523d3` は1.2178秒・24操作後、`failed` / `block_runtime_failed`。`scanned_cells:0`、`broken_blocks:0`、公開resultはない。
- 直後の別のobserveでは対象が `known:true` の `minecraft:air`、直下が `minecraft:glass`。**job失敗でも、後続観測では対象の消失が確認された**。broken_blocks:0を「worldに変更がなかった」と解釈しない。
- 破壊は再送していない。そのairへの白色コンクリートパウダー設置action `9b54c7a1-cf01-43d8-914e-27dd0300ad32` は成功、`confirmed_count:1`、air→whiteの確定セルを返した。さらに別のobserveで白を確認した。
- 装置はこの間OFF。破壊失敗を遡って成功扱いせず、後続の観測と新規設置による復旧を別に記録する。

これは破壊によるworld変化があったという現場報告と整合するが、破壊ACKや内部例外が保存されておらず、今回の例外箇所・server側の因果関係までは確定しない。被害・ドロップ数量なども未確認。

## ソース上の候補と未実装

`V2BlockJobExecution.tick` は `driver.begin`／`driver.tick`／確認結果の保存などで生じた `RuntimeException | LinkageError` を、共通の `block_runtime_failed` にまとめる。破壊入力後、確認を終えるまでに例外が発生した場合もここへ入る。例外種別・実行段階は公開されず、この証拠だけでどの処理かは特定できない。

今後は、失敗段階と処理中の対象セル、入力を発行したか、既存の確定セルと未確認効果を有界に保持する診断を検討する。内部例外の任意本文を公開せず、例外直前・確認時・入力解放時を区別する。失敗を成功へ変換したり、未確認破壊を再送する対応は行わない。入力後にdriverが例外を出すモデル、取消、確認後の終了処理例外を試験し、隔離実機で落下ブロックの更新・server ACKとの順序を確認する。**この改善はまだ実装していない**。

また、大きい上階目標では階段で止まり、1段ずつ短いwaypointへ分割すると体力20で到達したとの報告がある。これは運用上の回避であり、長い目標への経路計画修正が済んだことを意味しない。詳細waypointは上記証拠に保持されている。
