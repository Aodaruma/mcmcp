# 元の小麦畑でのfresh gpt-5.6-sol high full-cycle評価 R12（local PC、2026-08-29）

- 実験ID: `wheat-original-v1-r12-local-full-cycle-sol-high`
- artifact: `C:\Users\aod\Documents\mcmcp-eval-artifacts\2026-08-29-r12-local-full-cycle-sol-high`
- baseline: `wheat-original-v1-r12-local-360e053-66d3382f`
- repository commit: `360e053469ab7c356fb8aee973269033da4b7c63`
- model: `gpt-5.6-sol`、reasoning effort `high`
- prompt profile: `full-cycle`
- T0: `2026-08-29T08:30:04.9103570Z`
- runner terminal: `2026-08-29T08:36:20.5738050Z`
- T0後のoperator介入: なし
- 判定: **未達（小麦0 / 64、耕作0 / 72）**

## 条件

ユーザー指定のこのPC上にあるPrism Launcher profile `MCMCP-Validation`、save `tester (1)`を使用した。固定空中arenaは使用せず、saveに既存するoak fence囲いの畑72区画を対象とした。

T0前にprofile全体を次へ退避した。

`C:\Users\aod\Documents\MCMCP-Validation-Baselines\2026-08-29-pre-full-cycle-local\MCMCP-Validation`

現commitのproduction JARとdev-only fixture-admin JARを検証profileだけへ配置し、固定arena autorunを無効化した。fixture `wheat-original-v1`をadmin bridgeの`status -> validate -> apply`順で適用した。fixture SHA-256は`66d3382fa788edcb00dd767ab0495d4a16ff6af23b5783ded1171fee1d78e782`、world sessionは`4a62f22d-fbc7-418a-adfb-1f49d8790785`だった。

fixtureは次だけをT0前にcold-start化した。

- playerをSurvival、空inventory、畑東側の開始poseへ戻す
- chestへnetherite hoe 1、小麦の種64を配置する
- gateを閉じる
- 既存畑の耕作可能な72区画をdirt、cropなしにする
- workspace内の落下itemを除く
- `randomTickSpeed=3000`を最大22分の復元付きleaseで適用する

画面で元の畑、空inventory、固定arena外の開始位置を確認し、MCP操作を一度だけONにした。runner preflightはready、非pause、world/observationあり、inventory空、512 rays/tick、visible entity 0、Action idleをすべて満たした。

production promptは次の一文だけで、座標、Action例、過去runのcontextは与えていない。

```text
チェストに小麦の種と鍬が入っています。これを取り出し、この畑の区画にある耕作可能な土をすべて耕して、すべてに小麦の種を植えてください。成熟後はすべて収穫して植え直す工程を、小麦を1スタック（64個）以上所持するまで繰り返してください。
```

T0からrunner terminalまでは画面観測、computer-use、keyboard/mouse、shellによるgameplay補助、追加prompt、admin bridge操作を行っていない。評価モデルに見せたToolは公開5件だけである。

## 結果

trace監査はPASSしたが、production goalは未達だった。

| 項目 | 結果 |
|---|---|
| chest確認 | 成功 |
| netherite hoe 1 / wheat seeds 64取得 | 成功 |
| gate開放 | 成功 |
| 畑内移動 | 成功 |
| 耕作 | 0 / 72 |
| 播種・成熟待機・収穫・再播種 | 未到達 |
| 最終inventory | netherite hoe 1、wheat seeds 64、wheat 0 |
| 全Action terminal | 成功。active Action 0 |

受理Actionは14件で、成功5件、失敗9件だった。成功はcontainer inspect、2 stack取得、gate開放、畑内移動、gate付近への移動である。失敗9件はすべて耕作Actionだった。

各耕作Actionは`SERVER_DENIED_OR_DESYNC`、evidence `mutation_precondition_changed`で停止した。8件は実行tick 1、executed node 0、interaction 0、camera 0で失敗した。`face_and_till`だけは先行するface nodeを完了した後、耕作nodeをinteraction 0で失敗した。したがって、hoeのuse packetやserver-side block mutationへ到達していない。

| Tool | 呼出数 |
|---|---:|
| `agent_get_state` | 13 |
| `agent_get_observation` | 18 |
| `agent_start_action` | 19 |
| `agent_get_action` | 14 |

同期受付エラー6件の内訳は`INVALID_ARGUMENT` 3、`TARGET_UNKNOWN` 2、`PROGRAM_BUDGET_UNPROVABLE` 1だった。deadline拒否、cancel、未終了Actionは0である。

自動監査はtrace 335 message、bridge 205 record、dynamic request 64件を検査し、violation 0だった。git worktreeはclean、secret保存0、元のCodex認証hashも不変だった。

## 事後oracle

runner terminal後のread-only `agent_get_state`は、control ready、非pause、inventory `netherite_hoe=1,wheat_seeds=64`、最終Action failed / `SERVER_DENIED_OR_DESYNC`を返した。

対象boundsのpolicy-visible surfaceをread-only filterで再取得すると、見えている44区画はすべて`minecraft:dirt`で、farmlandとwheatは0だった。Action trace上も全耕作失敗がinteraction 0で、world revisionはfixture適用後の369から変化していない。このため、非表示区画を含めても評価run中の耕作mutationは発生していないと判定した。

## 原因整理

`KnownBlockMutationAttempt.precheck`は、次の2条件を同じ`mutation_precondition_changed`へ正規化している。

1. `SemanticActionFrame.universalSafetyClear()`がfalse
2. live blockが`expectedBefore`と一致しない

本runではfresh observationが繰り返しdirtを返し、world revision不変、複数座標で同じ失敗、全件interaction 0だった。したがって、blockが実際に変わった可能性より、client focus / mouse grab / stationary baseline / screen / threat等を含む共通安全条件側がfalseになった可能性が高い。ただし現診断では個別Booleanがartifactへ残らず、どの条件かは確定できない。

次の優先修正は安全条件を緩めることではなく、入力値や秘密を反射しない固定診断として、少なくとも`mutation_source_changed`と`mutation_safety_changed`を分離し、後者も許可された固定component名で原因を識別できるようにすることである。その後、同じlocal baselineとfull-cycle profileで再現確認する。

## 終了と復旧

terminal後にMCP操作を明示OFFとし、worldを正常保存終了してMinecraftとPrismを閉じた。run後のprofile全体は次へ保全した。

`C:\Users\aod\Documents\MCMCP-Validation-Baselines\2026-08-29-post-r12-local-failed\MCMCP-Validation`

その後、開始前profileを同じ`MCMCP-Validation`位置へ復元した。復元後のSHA-256は次のとおりで、開始前と一致する。

- `level.dat`: `383211CC4A92E8EEBC5ECBEEBF49BB4746D3F0CA989CE42C674E2E1BD6B7FE46`
- `instance.cfg`: `7459518355CD6601E3176DC7980E952A0D2389D395A573C24FBD9A2F5A75244D`
- production JAR: `7F1880988EAA9718B7FA0EF6F1A0B0F638116D621AC9C450BFC6F3F7B4F5947F`

主profileが失敗したため、`short-regression`は実行していない。
