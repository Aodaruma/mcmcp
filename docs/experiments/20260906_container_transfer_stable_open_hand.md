# 最大container取得後の安定した開封手（2026-09-06）

## 報告と原因

木製チェスト間の整理で、空きhotbarを1枠用意して`take_known_container_stack`を最大14 stack / 896個で実行すると、その枠へ取得品が入り、続く`inspect_known_container` / `store_known_container_stack`が`inventory_safe_open_hand_required`で停止した。従来の選択は空きhotbarを最優先し、offhandを候補外としていたため、通常のserver `QUICK_MOVE`が最後の空き枠を埋めた後に開封手を再確保できなかった。

## 修正

- 副作用のないhotbar itemを最優先し、次に空または同じ安全条件を満たすoffhand、最後に空きhotbarを選ぶ。
- offhandでも`CONSUMABLE` / `EQUIPPABLE` / `BLOCKS_ATTACKS` / `KINETIC_WEAPON`と独自useを持つsubclassを拒否する。
- offhandを選ぶ場合も現在の選択hotbar slotとviewの所有権を維持し、normal `useItemOn`、exact crosshair、server menu/full-content ACK、cleanupを変更しない。
- 候補不足の固定診断をhotbarまたはoffhandの不足として更新した。

これにより、空のoffhandまたは安全なoffhandがある通常状態では、唯一の空きhotbarが取得品で埋まっても、同一Actionの読み戻しと次のcontainer開封をoffhandで継続できる。危険なoffhandしかなく、安全なhotbar itemも空きhotbarもない場合は従来どおりfail closedとする。

## 回帰検証

Java 25で`test harnessTest adminBridgeTest verifyHarnessIsolation build -Pmod_version=0.1.0-rc.3-SNAPSHOT`が成功した。

- unit 1,202件、harness 13件、admin 21件、計1,236件。失敗・error・skipは0件。
- 空きhotbarが1枠だけの状態で、最大14 stack / 896個の取得によりその枠が埋まった後も、空offhandを次のcontainer開封手として再選択できる回帰試験を追加した。
- MCP catalog、Action診断、harness分離、release tool 3件も成功した。

JAR: `mcmcp-neoforge-26.2-0.1.0-rc.3-SNAPSHOT.jar`  
SHA-256: `7548ddd2009bf3a1772adf91910dd2dbc0dad58a20ffcea976eac9cb10369648`

自動試験は実ワールドのE06-C05-L02からE05-C05-L01への残作業完了を意味しない。JAR差し替え・Minecraft再起動後に、同じ場所で実際のtake→storeを再確認する。

## 実機確認の追記: 不合格・公開保留

2026-09-06、保守担当から差し替え後の実機不合格が報告された。`inspect_known_container`単体と、896個取得後のstore前段inspectで`SERVER_DENIED_OR_DESYNC` / `container_open_prediction_unavailable`となり、開封前に停止した。`interactions=0`、`effects=[]`で、この失敗時のアイテム移動は報告されていない。`ba327f8`と上記SHAのJARをReleaseへ昇格させない。

追加のコードレビューでは、当該エラーは`useItemOn`より前のprediction begin/sequence取得失敗であり、これだけでVanillaのoffhand拒否とは断定できない。一方で別の問題として、Vanilla 26.2のclient/serverは通常blockの`useWithoutItem`をMAIN_HANDの場合だけ呼び、ChestBlockの開封はそこにある。NeoForge 26.2.0.59のpatchもこの分岐を変更していない。空offhandの選択テストが通ることは、通常チェストの開封成功を保証しない。

次の修正ではprediction bridgeの登録・無効化・lifecycleを固定診断で切り分け、MAIN_HANDを維持する方針と通常開封経路の回帰を検証する。直接openMenuを呼ぶ代替、未解決prediction ledgerの破棄、UNKNOWNの再送で回避しない。今回の追記は保守報告とソースレビューの記録であり、新たな実機操作は行っていない。
