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
