# v2追加機能：材料データ・MOD破壊・水中／乗り物・整理・スクリプト

2026-10-09。Issue #84／Draft PR #85。今回の追加実装の使い方と範囲をまとめる。**実装・Java等1,713件の自動テストは完了し、実機操作はMCP有効化待ち**。[候補と検証記録](experiments/20261009_v2_priorities_docker.md)を参照。以前の生産系JARの実機合格を、今回の変更の合格として扱わない。通常の「くらふとぶ！」への導入は保留中。

## クラフトと所持品

`agent_craft`の呼び方は[生産系ガイド](PUBLIC_API_V2_PRODUCTION_20261009.md)と同じ。公開レシピの`v2_craft_supported:true`を確認して`recipe_ref`と`fingerprint`を渡す。

- 名前、ダメージ、エンチャントなどが付いた材料も、通常のitem ID条件を満たせば材料に使える。レシピブックの自動配置から、サーバー確認付きの通常PICKUPによる配置へ変更した。
- 材料として消費するアイテムのデータが完成品へ移ることは保証しない。完成品へデータを写す特殊レシピ、独自component条件を要求するMODレシピ、連鎖クラフトは対応外。
- 材料配置・完成品・戻り容器は数量とcomponentsで照合する。途中取消では送信済み操作の確認を待ち、確認できたcursorを戻してから終了する。不明なクリックを再送しない。

`agent_inventory`に次を追加した。

```json
{"operation":"sort"}
```

既定はmain inventoryの9～35枠。まず同じitem・componentsのstackをまとめ、その後item ID順に並べる。同じIDの異なるデータは混ぜない。空き枠がなくても通常PICKUP交換を使える。

```json
{"operation":"consolidate","slots":[0,1,2,9,10,11]}
```

`consolidate`はstackの統合だけ行う。`slots`の順序が詰める順序。明示指定は0～35、重複不可。いずれも`max_ticks`の既定は2,400。

数量移送`move`の移動元・先は0～40へ拡張。36=靴、37=脚、38=胴、39=頭、40=オフハンド。装備slotへの適合、実際の容量、取り外し制限を確認する。自動的に「最強の装備」を選ぶ機能は含まない。

## MODブロック破壊

`agent_break_block`の`include_blocks`／`exclude_blocks`は登録済みMOD IDを受け付ける。固定の`minecraft:`制限を外し、現物の登録、硬度、道具、局所安全、通常破壊後のサーバー更新を確認する。破壊不能blockは入力前に拒否する。

MOD固有の機械の分解手順、保護MODの権限取得、レンチ操作などは推測しない。通常攻撃で壊せない対象は確認不能として止まる。drop内容の完全な追跡は今回も含まない。

## 水中・乗り物

`agent_move`は確認できた水中経路を通常入力で進む。水面への浮上、上下移動、出入口を局所観測の経路へ接続する。水流を含む水と水草のある空間を扱い、気泡柱は対応外。空気が減った場合は近くの確認できる水面へ退避する。退避後は元の目的地への成功とはせず、`air_supply_retreat`を返す。近傍に安全な水面が確認できない場合は停止する。水中呼吸・コンジット・creative/spectatorでは酸素退避は不要。

乗車中の対応は通常のボート、トロッコ、操縦可能な飼い慣らした馬。乗り物と乗員の大きさ、局所の水面・支持・レール接続を使って経路を作る。`sneak`・`clear_path`との併用は拒否する。座標のYは乗り物側の足元／レール基準。MOD独自車両、エリトラ、豚などのitem操縦は対応外。

```json
{"target":"entity","operation":"mount","entity_ref":"観測で得た参照"}
```

空手で一度操作し、サーバーの乗員packetで乗車を確認する。降車は次の通り。

```json
{"target":"vehicle","operation":"dismount"}
```

静止中で、通常の降車先が近傍の安全な足場であることを確認してsneak入力する。取消は入力を解放するが、慣性・動力レールを強制停止しない。到着成功には位置に加え速度の低下を確認し、`vehicle_may_continue_moving`で残る移動を示す。目的位置で停止できるレール／地形が必要。

## 条件とスクリプト

`stop_when`は従来のposition/block/item/screenに加え、`all`／`any`／`not`、player、観測済みentityを使える。深さ8、総数64、all/anyの子は1～16。未観測を否定しても未知のままであり、真にはならない。

```json
{"type":"any","conditions":[
  {"type":"player","property":"air","comparison":"at_most","value":100},
  {"type":"item","item":"minecraft:stone","count":64}
]}
```

playerの数値は`health`／`food`／`air`、entityは`distance`。比較は`equals`／`at_least`／`at_most`。真偽値は`on_fire`／`in_water`／`passenger`／`on_ground`、entityには`baby`もある。利用できない情報は未知とする。

scriptは`observe`で`known`と`value`を取得し、プロパティと配列要素を読み取れる。行動呼出しも`success`とjob結果を返す。

```javascript
let p=observe(kind="player");
if(p.known && p.value.air>200) {
  let r=move(x=10,y=64,z=10,on_failure="continue");
  if(!r.success) { look(x=10,y=65,z=10); }
}
```

`observe`のkindは`player`／`item`／`block`／`entity`／`screen`／`condition`。追加引数は対象に応じてitem、x/y/z、entity_ref、condition。workerは直接worldを読まず、ゲームthreadで現在の許可された観測を読む。

失敗は既定でscript全体を止める。`on_failure:"continue"`でも、地形変更を伴わないmoveの経路未確認・再計画上限等だけを継続可能とする。取消、危険、通信不明、未確定の破壊・設置・在庫移送をこのoptionで無視しない。小関数は引き続きreturn文・再帰・外側変数のcaptureを持たない。

## 外部sourceとJSON/CSV

Minecraft側の設定は`script_resource_root`（既定：game directory内の`mcmcp/scripts`）と`script_resource_max_mib`（既定32、1～256）。rootには利用者が管理する絶対pathも指定できる。初めにこのディレクトリを作り、読み込むファイルを配置する。

```json
{
  "source_file":"build.mcscript",
  "data_files":{"blueprint":{"path":"art/part-000.json","format":"json"}},
  "max_work":1000000,"max_iterations":10000,"max_calls":4096,
  "max_duration_ticks":72000
}
```

`source`と`source_file`はどちらか一方。登録データは最大32ファイル。sourceの上限32,768文字と、外部データ合計の容量は別。登録時にUTF-8を読み、JSON/CSVとして解析し、不変データとSHA-256をjobへ保持する。実行中のファイル変更では内容を差し替えない。

```javascript
let rows=read_json(name="blueprint");
for(let i=0;i<length(value=rows);i++) {
  let b=rows[i];
  place(x=b.x,y=b.y,z=b.z,block=b.block,advance=true);
}
```

CSVはヘッダーをキーとする行配列で、セルは文字列。`read_csv(name="blueprint")`と`number(value=b.x)`を使う。引用符・改行・カンマを含むセルを扱う。JSONは深さ64、全ファイル合計800万valueまで。容量はHTTPの短いリクエスト上限に依存しない。

scriptから使うのは登録名だけであり、データ中のpathや命令文字列を開いたり評価したりしない。pathの親移動・絶対path・ADS・リンク／junctionは拒否し、書込み、URL、shell、Java、任意import/evalは公開しない。rootは信頼するローカル利用者が管理する。別のOSプロセスが同時にディレクトリを差し替える状況まで隔離するOS sandboxではない。

## 128×128×9の設計図と再開

147,456セルを一度のjobへ詰めず、PC側で全体を計画して分割できる。JSON座標配列、CSVの`x,y,z,block`列、JSON palette形式を受け付ける。

```json
{"size":[2,1,2],"origin":[100,64,100],
 "palette":["minecraft:white_concrete","minecraft:black_concrete"],
 "blocks":[0,1,1,0]}
```

sizeは[X,Y,Z]。blocksはXが最速、次にZ、最後にYの平坦なpalette index配列。座標形式では任意のblockに`item`・`properties`を付けられる。空気セルは何もせず、既存建物の破壊許可に変換しない。

```powershell
python tools/building/compile_v2_blueprint.py art.json out/art-plan --origin 0 0 0
pwsh -File tools/building/Invoke-McmcpV2Blueprint.ps1 `
  -ManifestPath out/art-plan/manifest.json -CheckpointDirectory out/art-progress `
  -TokenPath /path/to/mcp-token -MaxSteps 256 -MaxSeconds 900
```

変換器の既定は最大1,048,576セル・256MiB。必要なら`--max-cells`（最大16,777,216）・`--max-mib`（最大1,024）で拡張できる。空気除外後に材料数・境界を集計し、下層から蛇行順で最大4,096設置・2MiBずつ分割する。147,456通常セルは36ファイル。分割サイズは`--chunk-size`で小さくできる。

runnerは全ファイルのhashを確認し、各設置のサーバー確認済み結果をcheckpointへ保存する。同じコマンドで続行、`-StatusOnly`でオフライン確認。応答不明の設置は自動再送せず、既存の[単一plan runner](../tools/building/Invoke-McmcpV2Construction.ps1)で該当chunkを確認・解決してから再開する。world sessionが変わった場合はworldを確認し、`-AcceptWorldSessionChange`を明示する。checkpointはplanとは別ディレクトリへ置く。

これは設計データの施工順と材料数を作る機能であり、材料の自動調達、全体の支持構造解析、MOD block設置、特殊設置の全組合せは保証しない。実行時には既存の設置・接近・安全確認を各セルで行う。147,456セルの計画生成と模擬script反復は自動試験対象。全量のゲーム内施工は未検証。

## 今回の優先対象外

取引・金床・エンチャント、複数工程の生産計画、全MOD収納への一般対応、scriptの実行途中の変数／制御位置そのものの永続化は未実装。既存の製錬・単工程醸造と対応済み収納は[生産系ガイド](PUBLIC_API_V2_PRODUCTION_20261009.md)・[公開仕様](PUBLIC_API_V2.md)を参照。
