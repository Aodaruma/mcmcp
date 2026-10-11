# 設計図からの施工：agent_build

開発中の17番目の公開ツール。通常profileへ導入した床/script R4には含まれない。実機確認と配布状況は[進捗](PUBLIC_API_V2_STATUS_20260928.md)へ記録する。

`agent_build`は、登録ディレクトリのJSON/CSVを読み、設置・破壊を区間にまとめて実行する。`agent_run_script`と同じ入力所有、取消、有限時間、server確認を使う。呼出し側が1床ずつmove/look/inputを組み立てる必要はない。

## 確認と実行

設計図を設定の`script_resource_root`（既定はゲームdirectory下の`mcmcp/scripts`）へ保存する。ファイル書込み、任意path、リンク、network、コード評価は許可しない。

```json
{"operation":"preview","blueprint_file":"art.json","dimension":"minecraft:overworld",
 "origin":{"x":310,"y":105,"z":310},"start_index":0,"max_blocks":4096}
```

previewはworld操作をせず、`blueprint`に設計図の`plan_sha256`、対象範囲、件数、材料数、今回の区間を返す。材料数は設計図全体の要求数で、所持品や現場の既設blockを差し引いた数ではない。材料一覧は最大128種類、打切りは`materials_truncated`で示す。未知のworldを観測済みとして返さない。

実行は同じfile・dimension・originと、previewのhash、`agent_get_mcp_status`で確認した現world sessionを指定する。

```json
{"operation":"execute","blueprint_file":"art.json","dimension":"minecraft:overworld",
 "origin":{"x":310,"y":105,"z":310},"plan_sha256":"<previewの64桁hash>",
 "world_session_id":"<現在のsession UUID>","start_index":0,"max_blocks":4096,
 "max_duration_ticks":1728000}
```

既定では直ちに`action_id`を返す（kindは共通実行器の`script`）。進捗・結果は`agent_get_action`、取消は`agent_cancel_action`で取得する。`wait_timeout_ms`を明示して最大25秒待つこともできる。読取済みデータはそのjob内で不変であり、途中で元ファイルを変更しても反映しない。次回はhashが一致しなければ拒否する。hashにはファイル内容・format・dimension・合成origin・解釈versionを含み、区間境界には依存しない。

## 入力形式

JSONの座標配列、`blocks`を持つobject、またはpalette形式に対応する。CSVは同じ座標行を使う。座標行は指定順を維持し、例えば明示的な破壊→設置も表現できる。

```json
{"blocks":[
  {"x":1,"y":0,"z":0,"block":"minecraft:black_concrete"},
  {"x":2,"y":0,"z":0,"block":"minecraft:gray_concrete"},
  {"operation":"break","x":3,"y":1,"z":0,"block":"minecraft:stone"},
  {"x":3,"y":1,"z":0,"block":"minecraft:torch"}
]}
```

- `operation`は`place`（既定）または`break`。placeの`item`は省略時blockと同じ。`properties`で向きなどの状態を指定できる。CSVのpropertiesはJSON object文字列として記述する。
- placeのair/cave_air/void_airは無視する。空気セルから暗黙に破壊しない。breakは破壊してよいblock IDを明示し、item/propertiesの変更は指定しない。別blockや未観測状態は成功した破壊として数えない。
- JSONの`origin:[x,y,z]`は呼出し側originへ加算する。ファイル内`dimension`は呼出し側と一致が必要。
- palette形式は`size:[x,y,z]`、`palette:["minecraft:stone", ...]`、`blocks:[0,1,...]`。配列のXが最速、次にZ、最後にY。実行順は下層から各Z行をX往復にする。palette entryはblock/item/properties/operationのobjectも指定できる。
- 同じ材料・状態で隣り合うX座標は最大8個の子命令へまとめる。空隙、材料変更、操作種別変更はまたがない。

## 大きな設計図と再開位置

設計図の幅・奥行き・高さを小さな固定サイズへ制限しない。Minecraft座標範囲内で、1ファイル最大1,048,576入力セル、既定32 MiB（設定で最大256 MiB）と解析複雑度上限を適用する。より大きな設計図はファイル分割できる。128×128×9＝147,456セルは1ファイルに収まる。

1回の`max_blocks`は既定4096・最大65536。`start_index`はair除外・実行順確定後の0始まりセル位置。既定4096なら147,456セルは36回の有限jobになる。原点や内容を変えず、前のjobを確認して次の区間へ進む。

親resultの`placed_blocks`・`broken_blocks`は確定済み変更の累計。`result.blueprint`は以下を持つ。

| 項目 | 意味 |
| --- | --- |
| `start_index` / `end_index` | 今回選んだ区間。endは含まない |
| `next_index` | 完全確認済みの子区間の次。実行中にも更新する |
| `completed_entries` | 今回の完全確認済みセル数 |
| `batch_complete` | 今回の区間を完了したか |
| `blueprint_complete` | 設計図の末尾まで完了したか |

一部のみ確定した子命令では設置/破壊累計を保持し、`next_index`はその子区間の先頭に留める。`last_command`と現場を確認してから、同じplan hashと次のindexを明示して再開する。未確認操作を自動再送しない。結果は利用者側で保存できるが、一般scriptの実行スタックを永続化する機能ではない。

`advance`はこの施工ツールでは既定true。各子の`step_max_ticks`は既定3200、`step_max_distance`は既定64・最大4096。距離は子命令ごと、`max_duration_ticks`は全job共通で既定24000・最大1728000（24時間）。中断後の再開は新しい有限jobになる。

## 対応範囲

既存place/breakで操作できる形状・材料を対象とし、支持床、通常入力、reach、損傷、server照合を維持する。床延長は通常の完全立方体の安全条件に従う。高層部への足場や支柱の自動生成、任意建築・回路の依存順序の解決、任意収納からの自動補給までは行わない。必要な到達経路と施工順序は設計図・既存scriptで準備する。

大規模データの自動検証は全量の座標・分割整合性を確認するもので、147,456セルのゲーム内施工完了を示すものではない。
