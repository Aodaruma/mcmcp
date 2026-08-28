# fresh gpt-5.6-sol high MCP-only再評価 R2（2026-08-28）

- 実験ID: `02-wheet-2026-08-28-fresh-sol-high-r2`
- artifact: `sol-high-20260828T093751Z`
- artifact保存先: `C:\Users\aod\Documents\Codex\2026-08-26\mine\outputs\mcmcp-eval\sol-high-20260828T093751Z`
- baseline: `tester1-combined-wheat-54201b3-r2`
- repository commit: `54201b3780ecbeb0dacadbadfcda3aac2f3ba643`
- model: `gpt-5.6-sol`、reasoning effort `high`
- T0: 2026-08-28 09:37:57.0367448Z
- 終了: 2026-08-28 09:54:57.0543350Z
- 評価時間: 1,020.018秒（17分の評価期限まで継続）
- T0後のoperator介入: なし
- 総合判定: **不合格**。チェスト内容の確認、ゲート開放、畑内への移動には成功したが、道具・種を取得できず、小麦栽培へ進めなかった

## 目的とprompt

新規LLM contextへproduction promptだけを渡し、画面、座標、過去の操作contextを与えずに、公開MCP Toolだけで依頼を完遂できるか確認した。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

promptにはprefix、suffix、Action DSLの補足、座標、経路を追加していない。effective configのMCP server登録数が0件だったため、評価protocolで許可されたdirect MCP bridgeを使用した。

## 実行環境と開始条件

- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25
- Prism Launcherの既存検証profile `MCMCP-Validation`
- save `tester (1)`
- Survival、開始座標はチェスト前、inventoryは空
- チェストはiron hoe 1個とwheat seeds 64個を保持
- 閉じたoak fence gateが、危険物のない9区画の畑への唯一の入口
- 9区画はすべてairを上に持つdirtから開始
- combined wheat fixtureは`randomTickSpeed=300`、全周観測512 rays/tickを一時適用し、15分の非更新wall-clock leaseを持つ
- fixtureは成長速度と観測速度だけを変更し、移動、耕作、播種、収穫、drop移動、inventory編集を代行しない

runner preflightは、公開5 Toolのlive surface hash一致、control `ready`、game unpaused、worldと観測frameの存在、空inventory、512 rays/tick、`visible_entity=0`、Action idle/terminalを確認した。preflightによるgameplay callは0件である。

## T0後の非干渉条件

T0前にMinecraftを起動してworldへ入り、MCP操作をONにした。T0からrun終了までは次を行っていない。

- `computer-use`による画面観測または操作
- PowerShellからのgameplay操作
- Minecraftへのkeyboard / mouse入力
- operatorから評価LLMへの追加入力
- operatorによる途中のMCP state、observation、log、artifact確認

T0後の144件のdynamic callは、fresh評価LLMが生成したものだけである。

## 監査結果

| 項目 | 結果 |
|---|---:|
| trace message | 725 |
| bridge record | 445 |
| dynamic request | 144 |
| schema上有効なdynamic call | 144 |
| Tool成功 | 17 |
| domain Tool error | 127 |
| rate limit / HTTP 429 | 0 |
| transport / protocol failure | 0 |
| trace audit | **不合格** |

Tool別の内訳は次のとおりである。

| Tool | 合計 | 成功 | domain error |
|---|---:|---:|---:|
| `agent_get_state` | 6 | 6 | 0 |
| `agent_get_observation` | 6 | 3 | 3 |
| `agent_start_action` | 127 | 4 | 123 |
| `agent_get_action` | 5 | 4 | 1 |

audit violationは次の2件だけだった。

1. `turn/completed`がない
2. turn lifecycle IDと`turn/start` responseが一致しない

manifestの`runner_failure`は旧runnerの一般的な文字列`app-server response timeout`だが、T0と終了時刻は設定された1,020秒の評価期限と一致する。bridgeは最後のdomain errorまで正常に1対1転送しており、429やtransport failureは記録されていない。このため、上記2件は評価期限でturnを打ち切ったことに伴う未完了lifecycleであり、MCP transport障害を示すものではない。

## 時系列

| UTC | 事象 | 結果 |
|---|---|---|
| 09:37:57 | exact production promptを1回送信 | T0 |
| 09:38:39 | `inspect_supply_chest` | `go_chest`は完了したが、2 node目のinspectで`BUDGET_EXCEEDED / primitive_budget` |
| 09:38:54 | `inspect_chest` | 成功。`minecraft:iron_hoe:1`、`minecraft:wheat_seeds:64`を確認 |
| 09:39:14–09:43:07 | `take_farming_supplies`を32回試行 | すべて`stack_policy: not in catalog enum` |
| 09:43:20 | `open_field_gate` | 移動とゲート操作の2/2 nodes成功 |
| 09:43:27 | `enter_field` | 移動1/1 node成功、畑内へ到達 |
| 09:43:45–09:54:56 | `take_farming_supplies`を91回追加試行 | enum不一致90件、必須値欠落1件 |
| 09:54:57 | 17分の評価期限 | `turn/completed`前にrun終了 |

受理された4 Actionの結果は次のとおりである。

| Action ID | name | 結果 | 主な証拠 |
|---|---|---|---|
| `641301fe-1508-4d70-8f23-4249ae84fac0` | `inspect_supply_chest` | failed | 1/2 nodes、`primitive_budget` |
| `d0c5cecb-3a44-4c78-9b32-11e8c3e309a4` | `inspect_chest` | succeeded | chestにiron hoe 1、wheat seeds 64 |
| `e43ae3d4-f3c0-4992-8167-817f6c85c806` | `open_field_gate` | succeeded | 2/2 nodes、interaction 1 |
| `aebe011d-0bca-4432-9ac9-cfea2e66701f` | `enter_field` | succeeded | 1/1 node、移動2.063 blocks |

## 最終state

artifact内の最終`agent_get_state`と、run終了後のread-only MCP確認は次の状態で一致した。run終了後の確認ではgameplay操作を行っていない。

| 項目 | 最終値 |
|---|---|
| control | `ready`、game unpaused |
| player座標 | `(199.44250925106962, 200.0, 200.3046276312284)` |
| health / hunger | 20 / 20 |
| inventory | 空 |
| wheat / seeds / hoe | 0 / 0 / 0 |
| 最終Action | `aebe011d-0bca-4432-9ac9-cfea2e66701f`、`succeeded / COMPLETED` |
| gate | 開放済み、通過可能 |
| player位置 | 畑内 |
| 畑 | dirt 9、farmland 0、wheat crop 0 |
| visible entity | 0 |
| world revision | 496 |

## 達成・未達

達成した項目:

- fresh LLMが画面や事前座標なしでチェストと畑をMCP観測から発見した
- schema-validなActionを4件開始し、3件を完了した
- チェスト内容を通常のcontainer inspectで確認した
- ゲートを通常操作で開き、畑内へ移動した
- 全dynamic requestをrate limit内で転送し、429を発生させなかった

未達の項目:

- チェストからseedsとhoeを取得する
- 9区画を耕す
- 播種、成熟待機、収穫、drop回収、再播種を行う
- wheatを64個以上集める
- turnを評価期限内に正常完了する

## 根因

R1で問題だったnode `id`と正規opcodeの発見性は改善し、有効Actionを組み立てられるようになった。R2の直接の停止要因は、`take_known_container_stack.stack_policy`の列挙値をTool説明とerrorから発見できなかったことである。

- 123件の取得Actionが同じfieldで同期的に拒否された
- 内訳は`not in catalog enum` 122件、`required` 1件
- LLMは非欠落値として109種類の文字列を推測した
- 正規値`default_components_only`と`item_id_any_components`は一度も生成されなかった
- errorはfield pathだけを返し、許可値を返さなかった

再帰schema内には正規enumが存在していたため、runtime能力の欠落ではない。しかし、production promptだけを受け取るLLMにとって、深いschemaから列挙値を確実に回収できる自己説明性が不足していた。モデルは取得を諦めずに推測を続けたため、17分の大半をschema rejectionに使用した。

observationの上限超過1件と期限切れframe 2件、`get_action.wait_timeout_ms`上限超過1件は自己修正可能な副次的errorであり、主因ではない。最初の複合Actionのprimitive budget超過も、inspectだけの再試行で回復できた。

## 次の修正

R3前に次を反映し、同じpromptで再評価する。

1. `agent_start_action`のdescriptionへ、`stack_policy`の正規値を文字どおり提示する。
2. 通常のseedsは`default_components_only`、damage・name・enchantmentを許容してhoeを取る場合は`item_id_any_components`を使う意味を明記する。
3. 公開する最小例を`take_known_container_stack`のschema-valid例にし、catalog testで常時検証する。
4. 小さな閉じたenumのvalidation errorには、入力値を反射せずcatalog由来の許可値だけを安全に表示する。
5. observationの`limit=1..256`、`FRAME_EXPIRED`時の最新frame再取得、`wait_timeout_ms=0..25000`をTool descriptionから発見可能にする。
6. 17分の終了を`evaluation deadline expired before turn/completed`として分類し、transport timeoutと区別する。
7. 同じ厳格なT0後非干渉条件で、取得からwheat 64個・再播種まで再実行する。
