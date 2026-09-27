# 水平のかがみ端設置（Issue #37）

利用者からの「Shiftで移動しながら端へ設置する」依頼をownerの変更意図として記録する。`extend_known_floor`を固定5 Tool内の単独DSL nodeとして追加した。20秒・400tick・移動2 block・camera 720度・設置1個を上限に、元の支持を保持して移動し、実際に見える側面へ通常設置した後、serverのblock・inventory確認を待って新しい床へ進む。

中心座標のfloorセルと身体を支えるブロックは区別する。端では中心が隣のairセルに出ても、幅0.6の身体は元の支持に残る。設置adapterの例外はこの内部操作だけに限定し、実AABBが元の支持と両軸0.07以上重なること、有限の移動範囲内であること、設置先が身体と交差しないことを準備時・use直前に再確認する。通常constructionの足元保護は維持する。

支持と材料は監査済みのfull cube、摩擦0.6・速度係数1の床に限定する。速度強化、特殊な滑り、落下、濡れた床、移動障害、支持変更、位置補正、damage、可視threat等では停止する。階段・slabをこの足場操作に流用しない。横方向4種はDSL試験で確認した。

## 検証

- Java 25: `test harnessTest adminBridgeTest verifyHarnessIsolation build` PASS。
- `tools/check-source.ps1 -SkipJava`: transport 14件と既存capability mock PASS。追加のgate読込み回帰試験PASS。
- catalog schema、manifest、固定hash同期、trace auditor SelfTest 66/66 PASS。
- 独立したCodex CLIの読み取り専用レビューで、実AABBの最小重なり不足を修正。最終差分は追加所見なし。subagentは使用していない。

## Dockerの短い実機試験

指定SSH先のPrism/Minecraft Docker、複製済みsave、開発用fixture `construction-edge`を使用。通常プロフィールは変更していない。fixtureはT0前だけ適用し、実行中は固定5 Toolの決定的なcapability runnerだけを使用した。fresh LLM受入評価ではない。

最終product JAR SHA-256: `38d6e67437952e86fa611eee074f08fa720dd3cf819899d9151e0ac744762ca4`。
fixture SHA-256: `6e684a35b7ea63bebb55ad8b00e5d4a5d58674aa1d30cc740df570d51ec7a6d9`（11 command、変更宣言712 block、上限1000）。

| run | 結果 |
| --- | --- |
| run01 | 中心のfloorセルを足元禁止対象としたため設置前停止。設置0・材料消費0。身体支持の明示証明へ修正。 |
| run02 | 1個成功後、2個目の惰性移動で上限0.73に達して停止。安全範囲を広げず入力解放を早めた。 |
| run03 | 東→東→北→西の4連続設置とqueue中cancel PASS。 |
| run04 | 同じ4連続設置を再現し、実移動中のcancelもPASS。 |

run04はsnow/black wool各2個を設置（各64→62）。全てY=61を維持し、新床中心との差は約0.029以内。設置Action IDは順に `0f5fe3e7-b0f3-4043-860c-ff44595a60a7`、`14eab4ab-b58a-4c7d-8a94-2416d9573a93`、`b5995ad7-c594-4ffa-a7ad-2b066249d2b9`、`7b95db1b-f9c1-40fb-9395-ff6a4ca4a7b9`。

cancel `87403b72-784a-42f5-8981-535f0d4ba23a` は16tick・移動0.188、camera90度でcancelled。設置0・材料消費0、`resume_requires_reobservation=true`。最後に全Action terminal・公開状態READYを確認した。公開Toolは入力ownerを直接公開していないため、その直接観測とは区別する。terminal前のowner解放はruntime契約試験でも確認した。

remote artifactは`20260914-construction-phase2-r1/run01..04`に保持。元saveとphase開始前saveを別々に保全し、各runのterminal後に保存・通常終了した。長距離128×128施工、全方向の実機網羅、設置送信直後の回線断はこの短い試験の合格範囲に含めない。
