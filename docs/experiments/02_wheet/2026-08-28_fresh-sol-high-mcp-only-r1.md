# fresh gpt-5.6-sol high MCP-only初回評価（2026-08-28）

- 実験ID: `02-wheet-2026-08-28-fresh-sol-high-r1`
- artifact: `sol-high-20260828T084708Z`
- baseline: `tester1-combined-wheat-25e0625-r1`
- model: `gpt-5.6-sol`、reasoning effort `high`
- T0: 2026-08-28 08:47:14Z
- 終了: 2026-08-28 08:52:27Z
- T0後のoperator介入: なし
- 総合判定: **不合格**。MCPによる状態・観測取得は成功したが、有効なActionを1件も開始できず、gameplayへ到達しなかった

## 目的

新規LLM contextへproduction promptだけを渡し、画面、座標、過去の操作contextを与えずに、公開MCP Toolだけで次の依頼を完遂できるか確認する。

```text
チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか
```

promptにはprefix、suffix、Action DSLの補足、座標、経路を追加していない。effective configにMCMCP登録がないことを事前確認したため、評価protocolで許可されたdirect MCP bridgeを使用した。

## 隔離条件

T0前にPrism Launcherの既存profile `MCMCP-Validation`から対象worldへ入り、MCP操作をONにした。T0からrun終了までは次を行っていない。

- `computer-use`による画面観測または操作
- PowerShellからのgameplay操作
- Minecraftへのkeyboard / mouse入力
- operatorからLLMへの追加入力
- MCP state、observation、logのoperatorによる途中確認

したがって、T0後の230件のdynamic callは評価LLMが生成したものだけである。

## 結果

| 種別 | 件数 | 結果 |
|---|---:|---|
| `agent_get_state` | 5 | すべて成功 |
| `agent_get_observation` | 4 | 成功 |
| observation domain error | 2 | 上限を超える要求1件と期限切れframe 1件 |
| `agent_start_action` | 218 | すべて`INVALID_ARGUMENT` |
| 最終bridge call | 1 | transport / protocol failureで未完了 |
| 合計 | 230 | 有効なgameplay Actionなし |

`agent_get_state`と`agent_get_observation`から、LLMがMCP経由でworldを観測できることは確認できた。一方、`agent_start_action`は必須node `id`の欠落や、公開文法に存在しないopcodeの試行を繰り返し、1件も受理されなかった。

そのため、次の事後条件は開始時から変化していない。

- Action IDは発行されていない
- world revisionは不変
- player poseは不変
- inventoryは空のまま
- gameplay mutationはない

chestを開く、itemを取り出す、gateを通る、耕作・播種・収穫する段階へ到達していない。したがって、今回のrunからchest、gate、navigation、farm機能の成否を判定することはできない。

## 原因分析

### Action DSLの発見性

評価時のdynamic Tool schemaにはAction DSL catalog全体が渡っていたため、Tool未登録が直接原因ではない。ただし、再帰的なschemaと`$defs` / `$ref`を読むだけでは、LLMが次を安定して発見できなかった。

- envelopeと全nodeに一意な`id`が必須であること
- 利用できる正規opcode名
- opcodeごとの必須fieldと値形式
- opcodeとcapabilityの対応
- 最小のschema-valid Action

LLMは`move_to`、`wait`、`look_direction`などの推測したaliasを試し続けた。有効なAction例を一度も生成できていないため、runtimeの照準、移動、interactionの不具合ではなく、その前段にある公開Tool interfaceの自己説明性不足である。

### 最終bridge failure

終了直前には50 requestが467 msに集中していた。これはMCMCPのrate limitである20 requests/s、burst 40を超えるため、最後のbridge failureはHTTP 429であった可能性が高い。

ただし、このartifactのbridge failure recordにはHTTP statusが保存されていない。したがって、429は時系列とrequest密度に基づく**強い推定**であり、確定事実としては扱わない。

### trace auditの誤判定

旧auditは542件のviolationを報告したが、内訳を分離する必要がある。

- 536件: app-server notificationに正規のtop-level `emittedAtMs`が含まれることをauditが許容していなかったための誤判定
- 6件: 最終bridge failureに伴う未完了lifecycle、turn完了欠落、bridge対応関係の不整合など、実際に残った失敗

後者6件の内訳は、`turn/completed`欠落、turn lifecycle ID不整合、最終item lifecycle未完了、transport / protocol failure、dynamic callとbridge recordの1:1:1対応不成立、孤立または重複したbridge recordである。

したがって、「542件すべてが評価runのprotocol違反」ではない。一方、残る6件は最終callとturnが正常完了していないことを示すため、今回のartifactを合格証拠にはできない。

### fixture開始状態

事後の解析で、workspace内に3件の落下itemが存在していたことが分かった。これらは事前purge後、combined wheat layoutのblock置換によって生成されたものであり、前回runから持ち越したitemではない。

今回はplayerが移動せずinventoryも不変だったため、この落下itemがAction失敗を引き起こしたわけではない。ただし、空inventory・所定chest内容・落下itemなしという厳格な開始条件を満たしていないため、仮にActionが進行していてもproduction合格runにはできなかった。

## 改善項目

1. `agent_start_action`の公開descriptionだけで、必須`id`、正規opcode、field、capability、座標形式を把握できる自己完結した文法説明を提供する。
2. schema-validな最小Action例を公開descriptionへ含め、例自体をcatalog testで検証する。
3. 推測aliasや固定actionを追加せず、閉じたAction DSLをLLMが正規形式で組み立てられるようにする。
4. bridgeで全requestをpacingし、20 requests/s、burst 40を超えないようにする。
5. transport failureをHTTP statusなどの固定診断codeへ分類し、429を一般的なbridge障害と混同しない。秘密、response body、例外messageはartifactへ保存しない。
6. trace auditが正規の`emittedAtMs`を受理するようにし、notificationの形式誤判定と実際のlifecycle / bridge失敗を分離する。
7. combined wheat layoutとcontainer設定の後にworkspaceの落下itemを最終purgeし、T0前に空inventory、所定chest内容、落下itemなしを確認する。
8. observation Toolのlimit上限とframe更新手順もdescriptionから発見できる状態を維持する。

## 再評価条件

1. 上記修正をbuild、unit test、GameTest、評価runner self-testで確認し、production JARとfixture JARを既存profileへ反映する。
2. 同じsaveを既知baselineへ復元し、fixtureを再準備する。
3. T0前にplayer inventoryが空、chestに所定のhoeとseedsがある、workspaceに落下itemがないことを確認する。
4. fresh `gpt-5.6-sol` high contextへ、同じproduction promptをbyte-for-byteで渡す。
5. T0後は今回と同じoperator非介入条件を守り、pacingされたMCP bridgeだけを使用する。
6. chest取得、gate通過、畑発見、耕作、播種、成熟待機、収穫、drop回収、再播種を実際に通過したAction traceで評価する。
7. 終了時にwheat 64個以上、収穫済み区画の再播種、control `ready`を確認する。
8. bridge失敗時は安全な分類とHTTP statusをartifactへ残し、trace auditの実違反だけを判定に使う。

## 再評価結果

<!-- 修正後のfresh MCP-only再評価結果をここへ追記する。 -->
