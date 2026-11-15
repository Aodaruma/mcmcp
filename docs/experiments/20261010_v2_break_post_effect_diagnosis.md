# R18 破壊failed後の対象消失（修正候補・因果未確定）

2026-10-10 14:06 JST、地図アート担当の通常R18からの報告。証拠は `_PLAYING/map-art-MFwtpM21wWc-3x3/duper-operation/r18-lime-to-white-seed-20261010.json`。現場は復旧しており、再起動・追加JAR導入は不要との連絡。開発担当による通常ゲームへの入力は行っていない。

## 保存証拠で確認したこと

- Overworld `(-1969,-40,-1535)` を直前にobserveし、`known:true` の `minecraft:lime_concrete_powder` を確認。
- `agent_break_block`、`advance:false`、`max_blocks:1` のaction `9d71f2f2-c5cd-42f6-8c04-77f9e6f523d3` は1.2178秒・24操作後、`failed` / `block_runtime_failed`。`scanned_cells:0`、`broken_blocks:0`、公開resultはない。
- 直後の別のobserveでは対象が `known:true` の `minecraft:air`、直下が `minecraft:glass`。**job失敗でも、後続観測では対象の消失が確認された**。broken_blocks:0を「worldに変更がなかった」と解釈しない。
- 破壊は再送していない。そのairへの白色コンクリートパウダー設置action `9b54c7a1-cf01-43d8-914e-27dd0300ad32` は成功、`confirmed_count:1`、air→whiteの確定セルを返した。さらに別のobserveで白を確認した。
- 装置はこの間OFF。破壊失敗を遡って成功扱いせず、後続の観測と新規設置による復旧を別に記録する。

これは破壊によるworld変化があったという現場報告と整合するが、破壊ACKや内部例外が保存されておらず、今回の例外箇所・server側の因果関係までは確定しない。被害・ドロップ数量なども未確認。

## End北側の水補修での追加発生

14:47 JSTの `_PLAYING/map-art-MFwtpM21wWc-3x3/duper-operation/conversion-nw-water-repair-20261010.json` でも同じ種類の結果を確認した。装置OFFで、北扉 `(98,49,-3)` の破壊 `dac286ad-3d7f-4bf6-bc4f-622fa9c76dfe` と、試験後のmagenta_concrete `(98,49,-2)` の撤去 `b97d3fe8-1a3f-452b-849d-5bb243ae55ff` が `block_runtime_failed`。いずれも元の失敗を維持し、直後のfresh AIRを別の観測で確認して破壊は再送していない。後者の最終観測は `476627bc-deba-4eb9-b874-1e6971bebf71`。

落下する粉だけでなく扉・固体でも報告されたため、落下block固有の更新だけに原因候補を絞らない。例外段階と破壊ACKが保存されていない点は共通であり、同じ内部原因とまでは断定しない。[静止試験の結果](20261010_concrete_powder_code_review.md)と、破壊APIの成否は別に扱う。

## 当初の診断案（公開診断の拡張は未実装）

`V2BlockJobExecution.tick` は `driver.begin`／`driver.tick`／確認結果の保存などで生じた `RuntimeException | LinkageError` を、共通の `block_runtime_failed` にまとめる。破壊入力後、確認を終えるまでに例外が発生した場合もここへ入る。例外種別・実行段階は公開されず、この証拠だけでどの処理かは特定できない。

今後は、失敗段階と処理中の対象セル、入力を発行したか、既存の確定セルと未確認効果を有界に保持する診断を検討する。内部例外の任意本文を公開せず、例外直前・確認時・入力解放時を区別する。失敗を成功へ変換したり、未確認破壊を再送する対応は行わない。入力後にdriverが例外を出すモデル、取消、確認後の終了処理例外を試験し、隔離実機で落下ブロックの更新・server ACKとの順序を確認する。**この公開診断の拡張はまだ実装していない**。後述の予測air後の再攻撃防止とprivate例外ログは追加済み。

また、大きい上階目標では階段で止まり、1段ずつ短いwaypointへ分割すると体力20で到達したとの報告がある。これは運用上の回避であり、長い目標への経路計画修正が済んだことを意味しない。詳細waypointは上記証拠に保持されている。

## 2026-10-11の追加報告

地図アート担当から、黒の必要量充足後、装置OFFで北側5枠を `advance:false` で破壊した際の追加事例を受領した。Action `2aa994a8-0130-432e-8bf5-62b271bf83e0` は `block_runtime_failed` / broken0。一方、独立した観測では先頭の対象セルがair、下にpiston_headを確認した。残る4枠と他面は未破壊。灰色設置は `target_not_observed` で拒否され、欠けた状態では装置をONにしないとの報告。

証拠は担当の `gray-north-break-check-20261011.json` / `gray-north-seed1-20261011.json`。通常ゲームは同担当が所有し、開発担当は入力・差し替えを行っていない。足場上へのmoveが上面に留まらず下階へ落ちた事例も継続。今回の短時間Action結果返却・小数座標対応とは別に、破壊例外の因果確認と足場上保持の修正が必要。

## 2026-10-11の修正候補と隔離対照

`a8c1283` は、破壊中の各tickで元のblock stateも確認する。client予測でairになった後、crosshairが旧targetを指す間に `holdAttack` → `requireSource` が非air条件で例外になる経路を防ぐ。その間は攻撃入力を止め、同じ予測sequenceのserver ACKを待つ。ACK未確認の消失を成功へ変換せず、再送もしない。状態遷移の単体回帰試験を追加し、block jobの内部例外をprivateゲームログへ記録する。公開結果へ任意の例外本文を混入しない。

修正前の隔離R6で、静止した上向きpiston_head上の黒粉を破壊して灰粉へ戻す操作は、石床と足場上の両方で成功した。破壊IDは `7f2d0b99-892e-4287-bb74-ef5500e5d4df` / `5c8ed89a-6bd8-438b-90fe-7852ba33f8fe`、設置IDは `bbc0ee02-4683-4eb6-b140-4189db4f1c4e` / `f821d0f9-4f06-4695-8c5f-ac1b5d5ac96d`。ただし足場上の設置後はY104→103に下降した。設置時の自動SNEAKを抑止する修正を `02e2856` に追加した。

元の `block_runtime_failed` と `target_not_observed` はこの対照fixtureでは再現しなかった。通常サーバーでの原因確定・再発防止確認まで完了したとは扱わない。修正版の追試は[移動・結果返却の実機記録](20261011_v2_movement_results.md)へ集約する。
