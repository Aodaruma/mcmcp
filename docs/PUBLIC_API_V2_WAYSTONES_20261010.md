# Waystones操作：試用候補の契約

2026-10-10。候補R15で隔離Dockerの一覧・費用拒否・取消・Esc停止・End往復を確認し、検証環境を復元済み。通常profileはR7で、この操作の導入は差し替え担当への引継ぎ待ち。[実機結果と未確認範囲](experiments/20261010_v2_waystones_docker.md)。

## 公開入力

`agent_interact`に、検証対象Waystones 26.2.0.9のワープストーン用操作を追加する。MOD版とJAR SHA-256が一致する場合にのみproviderを使う。固定のnative操作だけを扱い、任意のpacketや未確認のwaystone UUIDを受け取らない。

```json
{"target":"item","item":"waystones:warp_stone","operation":"inspect_destinations","max_ticks":600}
```

通常のitem使用で選択画面を開き、今回serverから受信した候補を読む。結果の`destinations`は`destination_ref,name,dimension,xp_levels,xp_points,selectable,requirements`を含み、画面を閉じて終了する。候補名はデータとして扱う。未ロードの周囲のblockやMODの全登録地点databaseを読むものではない。

```json
{"target":"item","item":"waystones:warp_stone","operation":"select_destination","destination_ref":"abcdefghijklmnopqrstuvwx","max_xp_levels":3,"max_xp_points":0,"max_ticks":600}
```

`destination_ref`は実際の一覧結果を使う。ワールドsession・出発block座標・手に持つitemとcomponentへ結び付け、2400client tickで期限切れにする。保持は最大256件。再開封時に候補・地点・費用・利用可否を照合し、費用上限を守って参照を一度だけ消費する。費用上限の既定値はどちらも0。現在は経験値費用に対応し、item支払い・未知の構造化要件は選択しない。MODのルール評価に含まれる変数代入や効果のスカラー戻り値は費用として扱わず、serverの許可判定と型付き費用を照合する。ワープストーンの耐久はnative転送で消費され得るため、経験値上限では耐久消費を制限しない。クールダウン中の候補は再度inspectして確認する。

## 選択と到着

費用上限は、再開封時に受信した費用に対する送信前の検査である。Waystonesのnative選択packetには上限を渡す欄がなく、serverが選択後に再計算した費用を原子的に制限する契約ではない。可変費用のserverではこの点を踏まえて使用する。

`selection_dispatched:true`は選択要求の送信を試みたことを示し、**転送完了を意味しない**。結果は`effect_confirmed:false`、`verification:pending_transfer...`と期待する候補名・dimensionを返す。転送の準備中にもserverが持ち物を確認するため、選択後はワープストーンの枠を維持する。

選択後は`agent_get_state`でdimension・位置を確認し、転送が確認できるまで次の入力を送らない。通信切断・server拒否・転送準備の失敗はあり得る。確認不能な選択を自動再送せず、停止して状態を確認する。通常の画面が閉じたことだけでも到着とは判定しない。取消やUI OFFは送信済み転送の巻き戻しを保証しない。

## 停止処理と検証範囲

保持useは1200tick以内、開封待ちは1280tick以内かつ全体予算内。入力lease、既存の予測ACK、画面所有、server cursor証拠を使う。item使用の初回ACKは、後のserver tickで開く画面がないことを保証しない。開封前の取消では使用解除の後に一度だけ通常の破壊中止packetを送信し、その順序付きACKまで遅れて届く画面の後始末権限を残す。ブロックを破壊・使用する処理や、未知のcursorを移すclickは行わない。

ローカル試験は参照の期限・一度限りの使用、session／出発地点／item変更、行き先・費用・利用可否の変更、上限超過、閉じた入力schema、遅延した0slot画面の所有と空cursor証拠を対象にする。

Java 1,704件、harness 14件、admin bridge 28件、計1,746件とbuild・harness隔離チェックが成功。PowerShellの共通smoke runner 7ケースおよび追加runnerの構文・LibraryOnly読込みも成功。MODのローカルJARの型定義と照合し、Shogiの経験値points残高はlong、levels残高はintとして扱う。

実機では一覧の開閉、保持use中取消、tick上限、経験値上限拒否、未発行／session変更前の参照拒否、End往復の到着・経験値収支、Esc停止、OFF後の再有効化と再開封を確認した。使用中のUI OFF／world変更の同時競合、独自cooldown設定、通信遅延・マルチプレイは未確認。未知の費用、他のWaystones版、scroll／warp plate／設置Waystoneへの一般対応は対象外。

固定fixture `waystones` と `tools/eval/Invoke-McmcpV2WaystonesSmoke.ps1` のInspect／Cancel／TickLimit／Budget／UnknownRefはR15で合格。往復試験は別途、client状態とserver fixture receiptを照合した。公開15ツールの全定義は実行中JARとcatalogで一致。製品SHA-256は`c913077ad3b72a187493a0872b232dd7158541e4fb4b5e01c00114df50642e68`。
