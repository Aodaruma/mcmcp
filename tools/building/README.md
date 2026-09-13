# 平面建築の保存・補充・再開

PowerShell 7.4以上で使う施工runnerです。最大128×128の床を蛇行順に1セルずつ施工し、指定したセルの上へ松明を置きます。MCMCPの固定5 Toolと既存の検証済みtransportを使います。MinecraftのMCP操作を手動でONにし、Survivalで開始してください。

`example-small-floor.json`はDockerの試験用座標の例です。実際の建築では、利用者が決めた場所・材料・行列へ変更した別ファイルを作ってください。

```powershell
pwsh -File tools/building/Invoke-McmcpBuilding.ps1 `
  -BlueprintPath my-floor.json -CheckpointPath progress/my-floor.json `
  -TokenPath /path/to/mcp-token -MaxCells 64 -Refill

# 同じ引数で、保存した位置から再開する
pwsh -File tools/building/Invoke-McmcpBuilding.ps1 `
  -BlueprintPath my-floor.json -CheckpointPath progress/my-floor.json `
  -TokenPath /path/to/mcp-token -MaxCells 64 -Refill

# ゲームへ接続せず進捗だけを見る
pwsh -File tools/building/Invoke-McmcpBuilding.ps1 `
  -BlueprintPath my-floor.json -CheckpointPath progress/my-floor.json -StatusOnly
```

`-Endpoint`は既定`http://127.0.0.1:8765/mcp`です。credentialの内容を引数や設計図に入れないでください。ゲームまたはrunnerの再起動後も、同じ設計図とcheckpointを使います。設計図のハッシュが変わると再開を拒否します。

## 設計図と準備

- `anchor`は行0・列0の**床ブロック座標**です。`rows`の文字を`palette`の材料へ対応させます。全行の幅を揃え、縦横それぞれ1〜128、paletteは最大32種類です。
- 回転0では列が東（+X）、行が南（+Z）へ進みます。`rotation`の90/180/270でこの2軸を回転します。奇数行は逆向きに歩き、各セルへの移動を水平1マスへ限定します。
- 開始支持は、anchorから「列方向の反対へ1マス」の床です。`start_support_state`と一致する通常摩擦のfull cubeを用意し、その上の中心へ立ちます。設置範囲は空き、頭上2セルに障害物がない状態が必要です。
- 床は`extend_known_floor`に対応する監査済みVanilla full cubeが対象です。建築一般で使える階段・slab・pane等は、この平面runnerの床対象とは異なります。材料は通常の無改変itemを用意します。
- 各材料の完成状態を示す見本ブロックを、出発場所から見える位置に用意します。`source`はその座標、`state`は完全なblock stateです。見えた材料の不変な`placement_state_ref`だけを現在のworld session内で保持するので、長い床の先端でも利用できます。座標の観測記録・経路を保存済みのまま使うことはありません。serverの512 state保持枠からrefが失効した場合は見本の再観測が必要です。
- `supply`は出発場所から通常reachで開けるchest/barrelです。`-Refill`指定時は確認済みの床を戻り、通常操作で1 whole stackを受け取り、施工位置へ戻ります。補充経路の途中で実行予算が尽きても、次回は現在位置から継続します。指定を省くと`needs_material`で停止し、手動補充後にも再開できます。
- `torches`は行列内の`u`（列）・`v`（行）です。床が完成してから、対象の隣の施工済み床へ移動して通常の床置きtorchを設置します。`torch_source`にも見本の座標を指定できます。

## 保存内容と停止時の扱い

checkpointは設計図ハッシュ、world session、確認済みprefix、各セルの確認方法とAction ID、材料収支、未確認Actionのintentを保持します。同じファイルへの同時実行を排他lockで防ぎ、一時ファイルをflushした後に同一directory内で原子的に置き換えます。破損・異なる設計図・不正なpending情報は拒否します。

Action開始**前**に一意のnonceを保存し、開始応答を受けたらAction IDも保存します。開始応答が失われても、同じsessionの最新Actionのnonceが一致すれば、terminal後の履歴も照合できます。他のActionに履歴が置き換わった場合や応答の意味が不明な場合は推測で再送しません。明確な受付前拒否は、通信結果不明とは区別して停止します。

failed/cancelledのActionでもserver確認済みの設置は一度だけ計上します。その後は停止しており、再開時に位置と支持を再観測します。未知の設置結果は、同じ対象・完全stateを実際に再観測できる場合だけ`observed`として進めます。材料消費のACKが得られたことにはしません。`floor_server_confirmed`/`floor_observed`と松明の対応数は別表示です。

初期在庫＋確認済み補充−確認済み消費を現在の在庫と照合します。手動出し入れ・拾得などの差は`external_inventory_delta`へ残し、原因や消費ACKを推測しません。未精算があれば、全セルが揃っても`complete`ではなく`built_with_unknown_balance`になります。

`world.session_id`は現在の読込みsessionの識別子で、saveの恒久IDではありません。Minecraft再読込みで変わると自動再開を拒否します。**元のsaveを開いたことを確認した場合だけ**`-AcceptWorldSessionChange`で明示的に結び直せます。この場合は材料refも再取得します。pendingが残る場合のsession付け替えは拒否します。receiptを失い、履歴も失効した操作を勝手に施工し直すことはありません。

1回の上限は既定64セル・512 Action・900秒です（`-MaxCells`、`-MaxActions`、`-MaxSeconds`）。どの停止もMinecraft自身の有限Actionと入力解放を維持します。移動失敗後でも、純粋な移動予算超過に限り、材料効果なし・health低下なし・現在の支持と床施工の中心範囲を再確認できれば到着姿勢として採用します。落下・damage・不明な足場を無視して続行しません。

## 検証範囲

`Test-McmcpBuilding.ps1`は128×128・4回転の経路、16,505件の確認receiptを含む保存・読込み、二重計上防止、破損、lockを確認します。`Test-McmcpBuildingRecovery.ps1`は開始応答喪失、nonce照合、受付前拒否、world変更、材料収支を検証します。`tools/check-source.ps1`にも含めています。

実機の記録は[進捗保存・再開の試験](../../docs/experiments/20260914_construction_progress.md)を参照してください。128×128全施工やQRの読み取りは、ここでの小平面の合格範囲に含めません。
