# 建築材料のホットバー同期（Issue #43）

主インベントリにある松明を使う`apply_known_block_plan`が、camera=0、placements=0のまま300 tickで予算超過した。Minecraft 26.2の通常SWAPはclient予測とserver結果が一致するとslot応答を省略できる。一方、建築準備は選択slotの新しい受信revisionを待っていたため、両者が噛み合っていなかった。

## 修正と自動検証

- 既存のcontainer移送と同じく、通常のSWAPを予測差分なしで1回だけ送る。送り元・送り先の両方のserver payloadでitem・count・componentsを照合し、選択slotの所有権と材料総数が変わっていないことも確認する。
- 待機上限は既存の60 tickとAction予算。同期不足・不一致を固定診断にし、観測・足場・照準の待機とtraceで区別する。未知送信を再送・逆SWAPしない。
- 持ち替えは`inventory_swap`として設置effectと区別する。確認済みは材料名・個数のbefore/afterを記録し、取消・期限・不明送信はUNKNOWNと空のafterを一度だけ記録してから追跡を閉じる。遅延slot応答は通常のinventory同期として適用され得るため、再開には再観測が必要。raw slot番号は公開しない。
- 確認済みreceiptの時計は再検証時のclient tickとglobal reconciliation revision。未確認receiptは送信境界の時計を保持する。
- 固定5 Toolは維持し、`agent_get_action`のeffect schemaと固定catalog hashを同期した。
- Javaの全test・harnessTest・adminBridgeTest・verifyHarnessIsolation・build、transport 14件、building ledger/recovery、全capability mockを通過。片側・順序逆・無関係・不一致payload、session変更、取消とeffectの二重回収防止、実際のport出力と公開schemaの接続を検証した。

## 指定Dockerでの比較

指定SSH先の既存Prism/Minecraft Dockerと削除可能なclone saveを使用した。固定5 Toolによる決定的capability試験であり、fresh LLM受入評価ではない。T0前のfixture準備とterminal後の復旧を除き、画面観測・admin操作・追加の物理入力を行わない。

fixtureは雪床とchest、松明の見本を持つ。chestから雪64個を9回取得してhotbarを満杯にした後、松明16本を取得して主インベントリへの収納を強制する。検証座標のchunkが未読込みなら、bootstrap fixtureでcloneの地表Y=56へ移動し、読込み後に本fixtureを適用する。本fixtureは19 command、宣言586 block、上限1000、SHA-256 `78390e26583a74669bd764bbc4c6ba1996c3ff8e338ae9b80456c3678782d74e`。

旧版`f9f48c83`（JAR SHA-256 `8cf05db25d1a71eddd6c5b7edd17589a4ea68e2ed4fea69be04fa3b3d0502a1f`）では、Action `43c0cc60-acfc-48a1-9323-7b1437ade4b1`が300 tick、camera=0、placements=0、`BUDGET_EXCEEDED`となり、実件を再現した。終了後はREADY・全Action terminalを確認した。

途中の修正版では持ち替え同期を通過したが、追加effectのslot項目によるledger拒否、次に公開schemaへの追加漏れを検出した。前者は材料名・個数だけの公開へ修正し、後者はcatalogの同期と実出力のschema回帰試験を追加した。公開結果の取得に失敗したrunも、事後の`agent_get_state`でAction成功・READYへの復帰を確認してから再起動した。

最終製品コード`29f614ac0790b4ef4835412ac4743db624fe02d7`、JAR SHA-256 `45723a7427f835f47a2e7b2ed9bcac39b5f0fb72f0a25291bea2dbe902c9ddd1`で`fixed-run03`がPASSした。

| 条件 | Action ID | 材料before→after | 所要tick |
| --- | --- | --- | --- |
| 主インベントリの松明 | `3d0cf337-d94b-4be4-96a1-1c53723ccb8d` | 16→15 | 7 |
| hotbarの松明 | `a44c3575-9db1-41f3-9fba-0272ceaa75a1` | 15→14 | 5 |
| hotbarの雪ブロック | `ede86e1c-e7cc-43fd-ab4d-df0eade1540c` | 576→575 | 5 |

最初のActionは`inventory_swap`と`block_place`各1件、残りは`block_place`各1件で、全てCONFIRMED・unknown=0。配置後の公開観測でも対象blockを確認した。全Action terminal、control.mode=ready、非pauseを確認した。入力owner自体は公開Toolに露出していない。

remote artifact `20260914-torch-staging-r1`に各runのtrace・結果、最終receipt、変更後saveと復旧記録を保存した。terminal後にclone save全61ファイル・元MCMCP JAR・instance.cfgをバックアップとのSHA一致で復旧し、検証containerを停止した。元containerは停止したまま維持した。通常の「くらふとぶ！」プロフィールは操作していない。

## 確認範囲

この試験は主インベントリからの持ち替えを伴う床置き松明、既にhotbarにある松明、通常雪ブロックを対象とする。実サーバーでのQR全施工・全建材・通信遮断を伴う実機取消・水中移動の合格を意味しない。通常プロフィールの導入と実施工は、それぞれ差し替え担当と施工担当が行う。
