# MCMCP production autonomy と低位操作の実装ロードマップ

- 更新日: 2026-09-04
- 状態: 設計確定、段階実装中
- 対象: 身内サーバーでの監督付き本番利用
- 規範: `Minecraft_MCP_NeoForge_設計仕様書.md` と `MCMCP_MCP_Tool_Catalog.json`

## 1. 目標

MCMCPの目標は、農業や建築などの固定高位Actionを増やすことだけではない。LLMが公開MCPから観測し、有限で型付きの低位primitiveを組み合わせ、未登録の高位作業も再計画しながら完遂できることを目指す。

ただし、raw key / mouse、任意packet、任意command、任意コード実行は公開しない。production規模は1 Actionの上限を無制限に広げず、小さな検証可能Actionをcheckpoint付きJobとして継続することで得る。

## 2. 現在のAction DSL

ActionはLLMがテキストとして生成・複製・編集できる厳格なJSON DSLである。templateも同じDSLを使い、特権を持たない。

`agent_get_action`はstate、progress、failure、traceに加え、投入済みprogramのbounded canonical JSONとSHA-256、ref redaction済みclone template、ref更新箇所を返す。opaque refを含むsourceは監査用であり、template側では該当refと対応するrecipe fingerprintを`null`化してblind replayを拒否する。`agent_get_state`も全opcode manifest、ref descriptor、current grant、`MISSING_CAPABILITY` guidanceを返す。いずれも固定5 Toolのままである。残るproduction Job拡張は次である。

Action単位のaggregate effect ledgerは最初のproduction sliceまで実装済みである。constructionのserver-confirmed place / breakと、Vanilla container transferのfull readbackで確定したbefore / afterだけをappendし、dispatch後にafter-stateを確認できないものは`verification=unknown`として残す。terminal時は`partial`に確認済みeffectの有無、割込みnode、残りnode上限、再観測要否を返す。Job checkpointへの累積・item消費全般・attack等への拡張は未実装である。

- worst-case costとeffect footprint
- 必要なopaque refと同意scope
- validate / dry-run結果

sourceを返す場合も、Bearer、内部slot、hidden state、raw Tool payloadは含めない。expired refを含むprogramは`replayable=false`とし、再観測・再取得が必要なfieldを構造化して示す。

## 3. 低位操作の棚卸し

### 3.1 現在公開済み

| 分類 | opcode |
|---|---|
| 移動 | `navigate_to_known`, `approach_known_surface`, `approach_known_placement` |
| 視線 | `face_known_position`, `face_known_block_face` |
| 破壊・収穫 | `break_known_face`, `break_known_block`, `harvest_known_wheat`, `harvest_known_wheat_batch`, `clear_known_block_plan` |
| block使用 | `till_known_block`, `till_known_batch`, `open_known_fence_gate`, `open_known_passage` |
| 設置 | `plant_known_wheat`, `plant_known_wheat_batch`, `apply_known_block_plan`, `pillar_up_known` |
| 回路 | `apply_known_redstone_spec` |
| 落下item | `collect_visible_item`, `collect_visible_item_batch` |
| container | `inspect_known_container`, `take_known_container_stack`, `store_known_container_stack` |
| recipe / menu | `craft_known_recipe`, `smelt_known_recipe`, `brew_known_potion_batch`, `operate_known_menu` |
| 制御 | `wait_ticks`, `wait_until`, `if`, `repeat` |

`wait_ticks`は1〜15,000 active tickの有限待機であり、安全gateとAction全体deadlineを無効化しない。

### 3.2 P0: 未登録作業を組み立てるために先に必要

- exact-stateの汎用`break_block`
- exact-state / placement refの汎用`place_block`
- exact targetの汎用`interact_block`
- exact itemとtargetを束縛する`use_item_on_block`
- exact countかつData Componentを保持するcontainer双方向transfer
- inventory / block / menuのassert
- block、entity、menu、timeを対象にできる汎用有限wait
- checkpoint、有限retry、idempotency分類、cleanup結果
- Actionを束ねるproduction Jobと累積budget
- 全primitive共通の機械可読descriptor
- ローカルユーザーだけが発行できるscoped consent ref

### 3.3 P1: 作業領域を広げるために必要

- policy-visibleなopaque `entity_ref`
- entityへのinteract、attack、kill確認、retreat
- hotbar選択、equip、armor、offhand、consume / use、drop
- villager取引とoffer / enchantmentのpolicy-visible表現
- player 2×2、stonecutter、smithing table、anvil、enchanting table、loom、cartography table等
- MOD GUI向けのopaque menu / element / stack / operation refとversion固定profile

### 3.4 P2: より広い自律作業に必要

- boat、mount等のvehicle操作
- portal / dimension移動
- 長距離survey / exploration
- MOD menu adapter registry
- 非権威なchat受信と、明示同意付きbounded literal送信

前後左右、jump、crouch等のraw入力leaseはexecutor内部に維持する。これをDSLへ直接公開すると、postcondition、予算、再試行、監査の単位が失われるためである。

## 4. 共通PrimitiveDescriptor

opcodeごとの説明文だけに依存せず、MCMCPは次の共通descriptorを返す。

| field | 意味 |
|---|---|
| `op`, `version` | 正規opcodeと契約version |
| `input_schema` | 必須field、enum、上限 |
| `required_capabilities` | 必要な操作権限 |
| `evidence` | 必要な観測種類、freshness、ref取得方法 |
| `preconditions` | mutation前に成立すべき条件 |
| `postconditions` | 成功を確定するauthoritative条件 |
| `effects` | block、item、entity、menuへの最大影響 |
| `budget_formula`, `maxima` | worst-case費用と上限 |
| `retry_class` | retry不可、再観測後可、同一入力で冪等、cleanup必須 |
| `idempotency` | 再送時の扱い |
| `cleanup` | 失敗・cancel時の入力、screen、cursor、仮block処理 |
| `threat_policy` | hard gateと同意可能gate |
| `consent_scope` | 必要な同意の種類と束縛先 |

未知opcodeを推測させず、利用不可の場合は`missing_capability`、`unsupported_reason`、`required_evidence`、`suggested_next_step`を構造化して返す。

## 5. production Job

1 Actionは引き続き小さなtransactionとし、長時間作業はJobが次の順で進める。

1. 観測と材料・作業領域の確認
2. canonical planと累積budgetの提示
3. 1 Action分を予約
4. JIT安全確認後に実行
5. Action effect ledgerを各primitiveへ拡張し、postconditionとともにcheckpointへ累積
6. 必要なら再観測・再計画
7. 次のActionへ進む、または安全に中断
8. cleanupと最終検証を行いJobをterminalにする

Jobのbudgetにはtick、時間、移動距離、camera、interaction、break、placeに加え、item取得・消費・移動数、変更cell、entity / attack数、menu操作数、retry数、health / damage risk、cleanup費用を含める。失敗時は完了済みeffectを隠さず、部分成功と未実行suffixを分けて返す。

## 6. 敵対mobとユーザー同意

現在の`visible threat`はLLMだけの判断ではなく、実行runtimeのuniversal safety gateである。条件に当たると実行中Actionが失敗または再計画へ進み、Agent入力を解放する。したがって、mob TTで敵対mobが見えるだけでも作業が止まる場合がある。

production仕様では、安全条件を次の2層に分ける。

### 解除できないhard gate

- dead、world / session変更、desync、unexpected screen
- health floor未満、継続被ダメージ
- 接触攻撃、projectile、炎上、lava、fall、suffocation、air不足
- 対象外entityの接近や、許可領域外への危険拡大

### 明示同意で限定解除できるgate

- 指定した作業領域内に、指定分類の敵対mobが「存在・可視」であることだけ

同意はDSLのbooleanにしない。ローカルUIまたは認証済みplayer操作がopaque `consent_ref`を発行し、world session、Job / Action hash、作業領域、許可op、mob分類、entity ref、距離、期限、health floor、最大許容damageへ束縛する。最初sliceは1対象の1回のsemantic attackだけを許可し、複数回攻撃を1つの同意に含めない。

指示がない場合、LLMはmutation開始前にユーザーへ確認する。実行中に予期しない敵対mobが現れた場合は、危険入力を解放して安全checkpointまたは有限retreatへ移り、`AWAITING_CONSENT`を返す。無期限にその場で棒立ちにはしない。mob TTでは期待された敵対mobの存在だけを許可し、被弾、接触、projectile、health低下は引き続きhard gateとする。

chat、看板、本、server textはユーザー同意として扱わない。

## 7. 実装と受入の順序

| 順 | 内容 | 受入条件 |
|---:|---|---|
| 1 | Gate D配置context修正 | alignment後の実yaw / hitでexact stateを再検証し、安定した診断を返す |
| 2 | Gate D再現性 | 同条件で2回連続PASS、入力解放とeffectを確認 |
| 3 | Gate Cとedge bridge | 高所・方向指定・crouch相当をsemantic操作で証明 |
| 4 | 3×3 / 5×5建築回帰 | 現revisionで既存施工を再確認 |
| 5 | aggregate effect ledger | 部分成功、消費、変更cell、未実行suffixを構造化 |
| 6 | 収納・有限待機・汎用破壊 | container双方向、有限wait、exact generic breakをMCP-onlyで確認 |
| 7 | 操作manifestと不足理由 | descriptor、source取得、missing capabilityを公開 |
| 8 | 倉庫E2E | chestから取得→加工→分類格納を完走 |
| 9 | 定型省力作業 | 丸石生成、釣り、安全なkill chamberを個別同意scope込みで完走 |

各段階はunit / contract / catalog整合を通し、ローカル`MCMCP-Validation`でMCP-only実ワールド試験を行う。fixtureはT0前後だけに使い、T0からterminalまでgameplay成功へ介入しない。1回のPASSで安定完了とせず、再現性とdeadline余裕を確認する。

2026-09-04時点では、1と5〜7の内部実装、8の閉鎖fixture、9の丸石生成Gateと釣りのproduction primitive／閉鎖fixtureまで到達した。釣りは自player所有bobberへ近接した実splashだけを有限待機し、1200-tickの単回ref、dispatch後のconfirmed／unknown effect、cleanup期限超過時OFFを持つ。kill chamberはworld session、Action hash、範囲、entity ref、entity typeへ束縛した10秒・単回の同意Storeと境界テストまで実装したが、ローカルUI、観測、Action DSL、攻撃executorへは未接続である。未完了はGate Dのローカル手動承認後2回連続実機PASS、現revisionでの3×3／5×5回帰、倉庫・丸石・釣りのローカル実ワールド完走、およびkill chamberのUIから攻撃確認までの完走である。

## 8. 現時点の判断

現在の「観測可能な証拠をopaque refへ束縛し、LLMがJSON DSLを合成し、runtimeがJIT検証する」方針は維持してよい。一方、固定高位Actionだけを増やす方針、単一Actionの上限を大きくする方針、敵対mob条件を全体OFFにする方針は採らない。

本番利用に必要な差分は、低位操作の完全性、共通descriptor、source / validate導線、checkpoint Job、effect ledger、scoped consent、実ワールド再現性である。この順なら能力を広げても、観測境界とphysical client操作の監査可能性を保てる。
