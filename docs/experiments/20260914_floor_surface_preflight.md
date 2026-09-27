# 端設置の支持面preflightと描画欠測（Issue #46）

## 報告と切り分け

元現場では、支持のsnow_block/upを新規観測した直後の`extend_known_floor`が、受付前に`SAFETY_PRECONDITION` / `known_surface_changed`で拒否された。直前の床設置は確認済み、pendingなし。再観測・新しい1セル計画で成功した後にも再発した。施工担当が安全な作業床へ帰還して新設を停止し、保守担当は元ゲームへ並行操作を行っていない。

保存された拒否にはfogの有無や表面barrierの値がないため、元現場の根因を断定していない。global revisionの増加だけでは、同じ支持面が失効した証拠にならない。

コードでは、配送済み表面を内部再観測した一時的なplanning viewが、後続tickで現在の描画fogを取得できない場合には古い配送証拠へ戻る。このとき表面barrierより古ければ既知表面の検査で拒否される。container/approachには元配送lease付きの有限待機があるが、端設置の支持は対象外だった。productionのinbox・証拠store・再raycast seamを使う回帰試験で、欠測時に待機せず拒否する経路を確認した。

## 修正とローカル検査

`SurfacePreflightRecovery`の対象に端設置の支持を追加した。CAPTURE、COMMIT、DISPATCH、未dispatchのJITで元配送leaseを保持し、元のHTTP締切・配送期限・20秒/400tick内で描画回復を待つ。復帰後の実再観測、位置・面・完全state・item・shape、fog/LOS、姿勢・安全・補正の検査を維持する。routine開始後の毎tick安全検査・通常入力・設置と材料のserver確認も変更しない。

描画待ちを消費済みtickへ加えた後、さらに400tick全体をJIT予約してしまわないよう、最初の単独端設置の時間予算を共有する。距離・camera・interaction・破壊・設置の予約は削減しない。上位runtimeの元Action期限・tick上限による停止も維持する。期限直前の復帰では残時間が短く、施工完了前に通常のbudget停止になる可能性がある。

- 対応製品ソース: `a7502a6005fd14f83185e4de589ff704e919b5aa`。
- 製品JAR SHA-256: `4c2df7f17cb0cfae3640861a3f8ae261ee473dfb564bde02c9ba73e698d968f6`。
- 新しい描画欠測の回帰試験2件は旧コードで失敗、修正後に成功。
- 支持変更・遮蔽・実fog距離不足で操作しないこと、元leaseの対象外を認可しないこと、待機期限と総予算も確認した。
- `pwsh -File tools/check-source.ps1`合格。Java unit/harness/admin/isolation/build、Python transport 14件、building ledger/recovery、capability mocksを含む。
- 独立した読み取り専用Codex CLIレビューで確認済みブロッカーなし。subagentsは使用していない。

## Docker実機

ユーザー指定のaod-mimoidにある検証cloneを使用した。Minecraft 26.2、NeoForge 26.2.0.59、Java 25、`construction-edge` fixtureと既存`Invoke-McmcpFloorExtensionCapabilityGate.ps1`。maxFpsを10、VSyncをOFFとして、描画sampleのないtickを生じさせた。旧test-harness JARはバックアップ照合後に一時除外し、fixture adminだけを有効にした。

準備はT0前に行い、T0からterminalまで固定5 Toolだけで材料を取得し、端設置と取消を実行した。元ゲームや評価中の画面・入力へ介入していない。

| 確認 | 旧版 run01 | 修正版 run02 | 修正版 run03 |
|---|---:|---:|---:|
| 東へ雪床 | 42tick / 成功 | 44tick / 成功 | 46tick / 成功 |
| 東へ黒羊毛床 | 34tick / 成功 | 37tick / 成功 | 37tick / 成功 |
| 北へ雪床 | 47tick / 成功 | 50tick / 成功 | 50tick / 成功 |
| 西へ黒羊毛床 | 47tick / 成功 | 50tick / 成功 | 50tick / 成功 |
| 端へ移動中の取消 | 16tick / 取消 | 19tick / 取消 | 19tick / 取消 |

各設置で材料1個の消費、対象ブロックの再観測、目標床上への接地を確認した。取消は実移動を観測した後に行い、材料を消費しなかった。両修正版runで全Action terminal・READYを確認した。公開Toolはinput ownerを直接公開しないため、その直接検査とは区別する。

修正版の床4件と取消1件すべてに`RENDERER_RECOVERY`の`missing=...;revalidated=...`が記録された。run02の最初はdispatch、それ以外はcommitとdispatchで回復している。run03は全件commitとdispatchで回復した。代表Actionはrun02 `8fb797bb-a052-46ec-93a3-e614cd588730`、run03 `10187727-ee60-471e-b8db-89c3515c5f00`。

旧版もこの標準試験では成功したため、元現場の停止条件をDockerで再現した結果ではない。コード上の拒否経路の回帰試験と、修正版の実際の描画待機・再検証・端設置が成立した結果を分けて記録する。初見LLMの自律施工試験でもない。

## 復旧と記録

元save 61ファイル、製品・開発MOD群、instance.cfg、options.txtをバックアップとhash照合して復旧し、検証containerを停止した。元containerは停止のまま維持した。今回追加したfixtureがあった場合だけ除去した。

remote記録は検証labの`eval-artifacts/20260914-floor-surface-preflight-r1`。run01は旧版、run02/03は上記修正版JARであり、合否を混在させない。元現場の拒否JSON、回帰試験の旧版/修正版結果、成功runと復旧receiptはローカルartifactにも保存した。公開資料には秘密・元save本体を含めない。
