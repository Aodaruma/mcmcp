# 小麦1スタック実験（2026-08-28）

- 実験ID: `02-wheet-2026-08-28-wheat-1-stack`
- 環境: Prism Launcher `MCMCP-Validation`、Minecraft 26.2、NeoForge 26.2.0.59
- world save: `tester (1)`、内部LevelName `tester`
- 対象source: `fb90c30` (`feat(harness): add reversible crop acceleration`)
- 本体JAR SHA-256: `1ED7DBCB2DDF94D9B61604CBCF1D866C20DE733E5A355238834445ADDFD58B55`
- fixture JAR SHA-256: `AA4B6D4317EFF97190C7C888911EA533D5C47787D2A50E9F16E9901D6172B4B0`
- 総合判定: **部分合格**。畑内の栽培・収穫ループはMCP-onlyで成功したが、cold startの小麦タスク全体は未合格

## 目的

LLMがMinecraftの画面操作を行わず、MCMCPの観測とAction DSLだけを使って小麦を1 stack集められるか確認する。

最終製品の合格条件は次のすべてを満たすことである。

1. LLMへ与えるのは「小麦を64個集める」というgoalと通常のMCP Toolだけであり、座標、画面、過去会話、手書き経路を事前に与えない。
2. 試験開始後は`computer-use`、物理keyboard / mouse、chat commandをgameplayと準備のどちらにも使わない。
3. MCP観測だけでchest、hoe、seeds、gate、畑を特定する。
4. chestからのitem取得、gate通過、耕作、播種、自然成熟待機、収穫、drop回収、再播種をMCPだけで行う。
5. 終了時にwheatが64個以上、収穫済み区画がすべて再播種済み、controlが`ready`である。

開始前に利用者がMCP操作を1回ONにすることは、local authorizationとして許可する。開始後の再ONは許可しない。

## 今回の開始条件

今回文書化するのは、長い畑試験のうち、inventoryのwheatが32個になった時点から64個へ到達するまでの最終継続区間である。

| 条件 | 実際の状態 | 最終製品の条件との差 |
|---|---|---|
| player位置 | 既に石柵内の畑にいた | chest前からの自律移動ではない |
| inventory | hoeとseedsを既に所持し、wheat 32個から開始 | 空inventoryからの取得ではない |
| chest | 前段で画面を手動操作し、netherite hoe 1個とwheat seeds 64個をhotbarへ移した | MCP-onlyではない |
| fence gate | 前段で手動開閉・通過した | MCP-onlyではない |
| MCP ON | 前段では不具合後の再ONに手動操作が入った | 1回のauthorizationだけではない |
| 区画・経路 | 20区画、full-dirt gap、中間routeを会話と一時PowerShell helperに保持していた | cold-context discoveryではない |
| 成長速度 | fixture commandで`randomTickSpeed`を3から30へ一時変更した | 自然成熟だけの試験ではない |

PowerShell helperはMCP JSON-RPC requestの組み立てとpollにだけ使い、Minecraftのraw inputは生成していない。ただし座標と経路をhelper側に保持したため、「過去の操作コンテキストなし」の証明にはならない。

## MCP-onlyだった範囲

次のgameplay操作は、`agent_get_state`、`agent_get_observation`、`agent_start_action`、`agent_get_action`を通して実行した。

- policy-filtered observationの取得
- 既知cell間の移動
- blockへの解析的な照準
- 成熟wheatの通常attackによる破壊
- dropへ寄って待機する回収
- farmland支持面へのwheat seedsの通常use
- 複数nodeを持つprogrammed Action DSLの実行
- Action失敗後の再計画

gameplay中の移動、視点、attack、useを`computer-use`で補ってはいない。

## 実施方法

1. MCP観測から成熟作物と既知の支持面を取得した。
2. 収穫前に作物中央へ移動し、`harvest_known_wheat`後に20 ticks待機してdropを回収した。
3. camera予算に収まるよう、収穫は原則2株、播種は遠い支持面から2～4区画ずつActionへまとめた。
4. 作物の成熟待機だけを短縮するため、fixtureで`randomTickSpeed=30`にした。
5. 成熟後は直ちに`randomTickSpeed=3`へ戻し、収穫と再播種をMCPで行った。
6. inventory差分を確認しながら反復し、最後は63個から1株だけ収穫した。

fixtureはblock、crop、inventoryを直接変更していない。Prismの`latest.log`ではaccelerate / restoreを6組確認でき、最後は2026-08-28 06:11:29に`random_ticks.mode=normal current=3 saved=none`となっている。

## 結果

| 項目 | 結果 |
|---|---|
| wheat | 32→64 |
| 最終seeds | 168 |
| 最終control mode | `ready` |
| 最終Action | `3f6aded9-b54f-4881-9cea-711b1a8233eb`、`succeeded`、wheat 63→64 |
| 最終移動 | `9d3eebbb-e707-45bc-985a-66030bea128d`、4/4 nodes成功 |
| 最終座標 | `[-14.628863814505705, 55.9375, -13.478536497295035]` |
| drop回収 | 最終15株はすべて1株あたりwheat +1 |
| 再播種 | 途中の周期で成功。64個到達直後に収穫した15区画は未再播種 |
| growth fixture | 終了時に通常値3へ復旧 |
| cold-context end-to-end | 未合格 |

最後の連続収穫は次のとおりである。

| Action | wheat差分 |
|---|---:|
| `8314f5c5-2a14-4957-b238-e200c0d973fe` | 49→51 |
| `338358c7-083a-475f-b4b6-11d6e9e12058` | 51→53 |
| `3fd94406-2059-40b3-aa5d-8f55a6570f13` | 53→55 |
| `34b2e748-5ce8-48bc-a77d-50f49a8883ac` | 55→57 |
| `2476ba6c-eabd-41e6-9277-6ab9e8ba26db` | 57→59 |
| `850df040-925c-4101-bc68-5844cf3fb687` | 59→61 |
| `5fa5cb2e-a968-4611-8a3e-64e587d7428b` | 61→63 |
| `3f6aded9-b54f-4881-9cea-711b1a8233eb` | 63→64 |

## 失敗から分かったこと

### Action全体のcamera予算

Action `bc6e8a52-8aea-43e5-982d-68ec75f09c8a`は11/13 nodesまで実行した後、camera 337.81°で`BUDGET_EXCEEDED`になった。wheatは32→34へ増え、controlは`ready`を維持した。

これは失敗時にMCPが勝手にOFFにならない修正のlive確認にはなった。一方、world mutation後にAction全体の予算切れになるため、compiler / plannerが正確な照準角から実行可能node数を先に計算して分割する必要がある。

### 播種順と観測遮蔽

近い区画を先に播種すると、新しいcropまたはplayer自身が次の支持面を隠し、`jit_target_unknown`になる場合があった。遠い支持面から処理することで改善したが、固定順序ではなく各mutation後の再観測で解決すべきである。

### 高低差経路

full dirtの1 block高低差を含む箇所は、LLMが中間cellとY座標を明示した直交routeで補った。現在のplannerはこのedgeを観測から自動生成できないため、cold-context試験の阻害要因になる。

### 観測pagination

完了済みpagination leaseがTTLまで残り、短時間に再観測すると`SERVER_BUSY`になる場合があった。完了時にleaseを即時解放する必要がある。

### 農地上の移動

今回の平坦移動ではfarmlandの踏み荒らしを再現しなかった。ただしjumpまたは落下を伴う進入は分離試験しておらず、navigationのfarmland保護は未確認である。

## 証拠の限界

Minecraftの通常ログにはAction request / responseとtraceが保存されていない。Action IDと結果は実行時のMCP responseおよび`docs/MCMCP_実ワールド検証記録.md`へ転記した値であり、endpoint停止後にraw traceを再取得できない。

今回確認できた証拠は次の優先順位で扱う。

1. **save / game log:** 最終player NBTからsurvival、最終座標、netherite hoe 1、wheat 64、wheat seeds 168を確認した。game logからfixtureの復旧を確認した。
2. **MCP responseのGit追跡済み転記:** Action ID、32→64の途中差分、terminal state、camera消費は本ノートと実ワールド検証記録に残した。
3. **観察メモ:** raw responseが残っていない事柄の合格根拠には使わない。

最終値64と168はsaveから独立確認できたが、開始値32は最終saveには残らないためMCP responseの転記だけが根拠である。

次回以降は、tokenなどの秘密を除いたMCP request / response、開始・終了inventory、観測frame ID、Action traceを試験中にJSONLとして保存し、その実験ノートから参照できるようにする。

## 判定

- **合格:** 既に畑内・道具所持済みの状態から、MCP-only gameplayで栽培・収穫し、wheatを32個から64個へ増やせる。
- **未合格:** 操作コンテキストを持たないLLMが、通常のMCP Toolだけでchest前から開始し、全工程を自力発見して64個と全再播種まで完了する。

## 次回の厳格な受入条件

1. 新しいLLM contextへgoalと通常MCP Toolだけを渡し、座標、screen、helper、過去会話を渡さない。
2. fixture準備は試験開始前に完了し、開始後は`computer-use`、keyboard / mouse、chat commandを使わない。
3. `randomTickSpeed=3`、player inventoryにhoe、seeds、wheatなし、chest前の所定位置から開始する。
4. 既存のcontainer同期・`transfer_items`実装をAction DSLから再利用可能にし、MCPだけでhoeとseedsを取得する。
5. 既存の`open_known_fence_gate`を使い、gate状態とKnown Traversability Mapを更新して通過する。
6. MCP観測だけで畑を発見し、耕作、播種、自然成熟待機、収穫、drop回収を反復する。
7. wheat 64個以上、全収穫区画の再播種、control `ready`、`randomTickSpeed=3`を事後確認する。
8. 全MCP request / responseと開始・終了条件を実験artifactとして保存する。
