# Waystones操作：開発候補の契約

2026-10-10。ローカル実装・単体試験の段階。aod-mimoidのCloudflare再認証待ちで、実機未検証・通常profile未導入。現在の通常版にこの操作があるという案内ではない。

## 公開入力

`agent_interact`に、検証対象Waystones 26.2.0.9のワープストーン用操作を追加する。MOD版とJAR SHA-256が一致する場合にのみproviderを使う。固定のnative操作だけを扱い、任意のpacketや未確認のwaystone UUIDを受け取らない。

```json
{"target":"item","item":"waystones:warp_stone","operation":"inspect_destinations","max_ticks":600}
```

通常のitem使用で選択画面を開き、今回serverから受信した候補を読む。結果の`destinations`は`destination_ref,name,dimension,xp_levels,xp_points,selectable,requirements`を含み、画面を閉じて終了する。候補名はデータとして扱う。未ロードの周囲のblockやMODの全登録地点databaseを読むものではない。

```json
{"target":"item","item":"waystones:warp_stone","operation":"select_destination","destination_ref":"abcdefghijklmnopqrstuvwx","max_xp_levels":3,"max_xp_points":0,"max_ticks":600}
```

`destination_ref`は実際の一覧結果を使う。ワールドsession・出発block座標・手に持つitemとcomponentへ結び付け、2400client tickで期限切れにする。保持は最大256件。再開封時に候補・地点・費用・利用可否を照合し、費用上限を守って参照を一度だけ消費する。費用上限の既定値はどちらも0。現在は経験値費用に対応し、item支払い・未知の要件は選択しない。クールダウン中の候補は再度inspectして確認する。

## 選択と到着

費用上限は、再開封時に受信した費用に対する送信前の検査である。Waystonesのnative選択packetには上限を渡す欄がなく、serverが選択後に再計算した費用を原子的に制限する契約ではない。可変費用のserverではこの点を踏まえて使用する。

`selection_dispatched:true`は選択要求の送信を試みたことを示し、**転送完了を意味しない**。結果は`effect_confirmed:false`、`verification:pending_transfer...`と期待する候補名・dimensionを返す。転送の準備中にもserverが持ち物を確認するため、選択後はワープストーンの枠を維持する。

選択後は`agent_get_state`でdimension・位置を確認し、転送が確認できるまで次の入力を送らない。通信切断・server拒否・転送準備の失敗はあり得る。確認不能な選択を自動再送せず、停止して状態を確認する。通常の画面が閉じたことだけでも到着とは判定しない。取消やUI OFFは送信済み転送の巻き戻しを保証しない。

## 停止処理と実機で確認する項目

保持useは1200tick以内、開封待ちは1280tick以内かつ全体予算内。入力lease、既存の予測ACK、画面所有、server cursor証拠を使う。item使用の初回ACKは、後のserver tickで開く画面がないことを保証しない。開封前の取消では使用解除の後に一度だけ通常の破壊中止packetを送信し、その順序付きACKまで遅れて届く画面の後始末権限を残す。ブロックを破壊・使用する処理や、未知のcursorを移すclickは行わない。

ローカル試験は参照の期限・一度限りの使用、session／出発地点／item変更、行き先・費用・利用可否の変更、上限超過、閉じた入力schema、遅延した0slot画面の所有と空cursor証拠を対象にする。

Java 1,702件、harness 14件、admin bridge 28件、計1,744件とbuild・harness隔離チェックが成功。PowerShellの共通smoke runner 7ケースおよび追加runnerの構文・LibraryOnly読込みも成功。MODのローカルJARの型定義と照合し、Shogiの経験値points残高はlong、levels残高はintとして扱う。これらはWaystones実機操作の成功証拠ではない。

実機では、一覧を開いて閉じる、保持use中の取消、費用上限拒否、古い参照の拒否、Endへの選択後に到着を別途照合、クールダウン、Overworld帰還、UI OFF／world変更との競合を確認する。未知の費用、他のWaystones版、scroll／warp plate／設置Waystoneへの一般対応は対象外。試験後は元world・設定・JARを復元する。

固定fixture `waystones` と `tools/eval/Invoke-McmcpV2WaystonesSmoke.ps1` を準備した。runnerはInspect／Cancel／TickLimit／Budget／UnknownRefを扱い、転送を伴う往復試験は別途、client状態とserver fixture receiptを照合する。runnerを作成・構文確認したことは、実機合格を意味しない。
