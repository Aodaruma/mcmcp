# 建築計画と決定論的実行の境界

- 状態: 検討案（未実装）
- 作成日: 2026-08-21
- 対象: Phase 6完了後の建築自動化

## 目的

Phase 6までのCraftAgentは、LLMが複数の型付きroutineを選ぶouter loopと、Minecraft内で通常操作・server同期・安全停止を担うinner loopを分離しています。この境界は短い作業では機能しましたが、アイアンゴーレムトラップ（以下、TT）の評価で、建築セル、作業位置、工程、資材、機構、Mob条件までをLLMが逐次組み立てる方式は、token量と失敗確率の両面で拡張しにくいことが分かりました。

本書は次を整理します。

- 現在できていることと、TT評価で判明した不足
- LLMへ任せる判断と、決定論的なtoolへ移す処理
- 材料と検証済みplanがあれば、1回の開始指示で施工を進める仕組み
- 汎用workflow DSLを導入せずに済む、有限な建築記述の案
- 実装順と受入条件

## 結論

次の一手は、LLMをより長く動かすことではありません。LLMが一度だけ宣言的な「何を建てるか」を出し、それをpure compilerが有限のセル、依存関係、作業地点、資材、検証集合へ展開し、決定論的runnerが既存の安全なroutineを用いて実行する層を追加するのが妥当です。

この記述は自由なworkflow DSLにしません。任意の`if`、`loop`、tool呼び出し、コード実行、任意predicateを持たず、静的に上限を計算できる建築専用IR（中間表現）に限定します。本書ではこれを**建築パッケージ**と呼びます。

当面、アイアンゴーレムTT自体の完成を目標にしません。まず、Mob・睡眠・水流・溶岩を含まない一般建築で、建築パッケージから複数作業地点を経由して完成まで進めることを証明します。

## 現状

### 実装済みの境界

Phase 6時点の公開surfaceは9 tools・13 routine kindsです。主な建築能力は次のとおりです。

- `compare_block_plan`: 指定セルをcurrent / last-known / unknownに分けて比較
- `apply_block_plan`: 1回1phase、最大64セル、移動なし、完全なbefore/after BlockState
- `navigate_to`: 短距離の平坦な通常移動
- `craft_items` / `transfer_items`: allowlist済み画面での資材準備
- `survey_area`: 通常の視点と移動で有限サンプルを観測
- `completion_intent=continue_goal`: 最大16回・15分のbounded chain

Minecraft内のrunnerは、各block操作の直前条件、prediction ACK、server由来state、inventory同期、入力解放を確認します。未観測状態や推定を成功にせず、失敗時にlockして停止する点は維持すべき強みです。

一方、現行設計は次をLLM側へ残しています。

- 大きなblueprintの作成
- phaseへの分割
- 各phaseから届く作業位置の選択
- 視点、reach、FOVを考慮したセル順序
- phase間の移動
- 資材補充と工程依存の管理
- 失敗後にどのセルから再開するかの判断

### アイアンゴーレムTT評価の実測

開発専用のflat labで、10×10床の中央2×2をchuteとし、60セルをagent施工対象にしました。村人、ゾンビ、ベッド、水、溶岩、回収系は、現行routineにない能力を混ぜないためfixtureが準備しました。

結果は次のとおりです。

| 項目 | 結果 |
|---|---|
| 単セル施工 | 3セルはprediction ACK・server state・最終current確認付きで成功 |
| 複数セル施工 | `STEP_NOT_PREPARABLE / aim_feasible=false`で停止 |
| 作業位置への移動 | `ROUTE_BECAME_UNSAFE / probe_not_currently_visible`で停止 |
| false success | なし。失敗後はinput解放・finalization・lockを確認 |
| TT発生条件 | 視線開口を手動補正後、ゴーレム1体のspawnを確認 |
| 搬送・処理・回収 | 水流が静的な水面となり未達。鉄回収0 |

ゴーレムは`(254.5, 206.0, 252.5)`へspawnし、10×10の意図したspawn床内でした。問題はspawn座標ではなく、床の任意位置から中央へ運べる水流になっていなかったことです。また、村人とゾンビの初期視線開口が狭く、panic条件もfixture側の手動補正を要しました。

この評価から、次を区別できます。

**機能した点**

- 完了を推測せず、確認済みセルだけを進捗にした
- 視点・経路が安全条件を満たさないとき、副作用前に停止した
- failure、cleanup、lockが構造化され、暴走やblind retryがなかった

**不足した点**

- LLMがセル単位の幾何、視点、移動を組み立てる負担が大きい
- `apply_block_plan`が現在位置固定で、phaseと作業地点が別々の問題になっている
- staticなfixture testが、水流、Mob衝突、kill、drop回収という時間発展を検証していない
- 一般建築の問題と、Mob・睡眠・fluid機構の問題が同じ評価に混在した
- fixtureによる支援完成と、agent自身の完成を明確に分ける必要がある

## 問題を分ける

「建築する」を少なくとも次の6層へ分けます。

1. **要求解釈**: 何を、どの規模・用途・外観で建てるか
2. **構造設計**: 完成時の相対座標、BlockState、不変条件
3. **施工設計**: 支持関係、閉鎖前検査、工程、資材、仮設の有無
4. **作業計画**: 作業地点、移動経路、視点、hotbar、1回のphase
5. **実行**: 通常input、prediction、server同期、局所回復
6. **受入確認**: 完成状態、機構の時間発展、output、unknownの有無

現状は1〜4を主にLLM、5〜6の一部をMODが担当します。大規模建築では2〜4が反復的かつ機械的であるため、ここをLLMから外す必要があります。

## 推奨する責務分担

| 判断・処理 | LLM | 決定論的tool / MOD |
|---|---:|---:|
| 曖昧な依頼の解釈、要件確認 | 主担当 | schema候補を提示 |
| レビュー済みtemplateの選択 | 主担当 | compatibilityを検証 |
| anchor、向き、寸法、paletteの決定 | 主担当 | bounds・registry・材料を検証 |
| 数百セルの列挙と座標変換 | 行わない | compilerが展開 |
| BlockStateのmirror / rotation | 行わない | Minecraft registryで変換 |
| 支持関係と施工順 | 方針だけ | dependency graphで決定 |
| 作業地点、reach、FOV、セル順 | 行わない | access plannerが決定 |
| phase分割とhotbar補充 | 行わない | runnerが決定 |
| tick操作、packet、ACK、retry | 行わない | 既存runtimeが担当 |
| typed failure後の別方針 | 主担当 | 診断と再開候補を返す |
| 完成・稼働の断定 | 意味を評価 | positive evidenceを収集 |

LLMは「意図と選択」に使い、座標計算と実行管理には使いません。局所失敗が同じpostconditionへ収束できる範囲はrunnerが扱い、設計、bounds、材料、危険許可の変更はLLMまたはユーザーへ戻します。

## 提案アーキテクチャ

```text
User request
    |
    v
LLM intent planner
  - template selection
  - parameters / style / policy
    |
    v
Build Package (finite declarative IR)
    |
    v
Pure compiler + validator
  - exact cells / states
  - dependency graph
  - material manifest
  - bounded work zones
    |
    v
Execution Manifest (content-addressed plan_ref)
    |
    v
Deterministic build runner
  - observe -> choose work pose -> navigate
  - refill -> apply phase -> reconcile -> checkpoint
    |
    v
Existing Minecraft routines / ports
    |
    v
Server-confirmed world state
```

### 建築パッケージ

建築パッケージは「完成形と施工上の制約」を記述します。操作手順や任意分岐は記述しません。

必要な要素は次です。

- schema version、package ID、Minecraft/registry compatibility
- anchor、rotation、mirror、work bounds
- paletteと必要item
- 有限なgeometry primitive
- component間の依存関係
- 完成時のexact BlockStateとclearance
- 許可する破壊、仮設、fluid、multi-block、BlockEntityのpolicy
- 必須能力と、未対応時のuser handoff
- 最終verificationと機構別acceptance probe

初版のgeometry primitiveは、静的に展開数を計算できるものだけにします。

- `cell`
- `line`
- `plane`
- `cuboid_shell`
- `repeat_grid`（固定回数・固定上限）

`if`、データ依存loop、任意式、任意tool名、script、chat/commandは許可しません。展開後セル数、region、資材、所要phaseの上限を実行前に確定できなければ拒否します。

以下は説明用の案で、wire schemaの確定版ではありません。

```yaml
schema: craftagent.build/v1
id: dry_platform_with_chute
parameters:
  width: 10
  depth: 10
anchor:
  dimension: minecraft:overworld
  x: 251
  y: 205
  z: 251
transform:
  rotation: 0
  mirror: none
bounds:
  min: {x: 248, y: 199, z: 248}
  max: {x: 263, y: 209, z: 263}
palette:
  floor: {item: minecraft:smooth_stone, state: {}}
components:
  - id: spawn_floor
    primitive: plane
    from: [0, 0, 0]
    size: [10, 10]
    material: floor
    except: [[4, 4], [5, 4], [4, 5], [5, 5]]
stages:
  - id: foundation
    components: [spawn_floor]
    verification: exact_current
requirements:
  unsupported_capabilities: [fluid_place, entity_transport]
```

### Execution Manifest

compilerは建築パッケージを、LLMへ再列挙させない実行用manifestへ変換します。

- package hashとcanonical `plan_ref`
- 全targetのabsolute座標とexact before/after state
- support / clearance / close-before-coverのdependency graph
- stageとcheckpoint
- item別の必要数と補充単位
- 候補work poseと、そこから担当するセル集合
- 最大travel、最大duration、最大mutation数
- final verification集合
- 未対応能力とhandoff地点

manifestはcontent-addressedにし、同じpackage、anchor、transform、policyから同じhashを作ります。再開時は保存済み「完了フラグ」だけを信用せず、worldのcurrent exact stateと照合してalready-satisfiedをskipします。

### Deterministic build runner

runnerの状態遷移は固定します。

```text
VALIDATE
  -> PREFLIGHT_MATERIALS
  -> PREFLIGHT_SITE
  -> SELECT_STAGE
  -> OBSERVE_WORK_ZONE
  -> CHOOSE_WORK_POSE
  -> NAVIGATE
  -> REFILL_HOTBAR
  -> APPLY_BOUNDED_PHASE
  -> RECONCILE_STAGE
  -> CHECKPOINT
  -> ...
  -> FINAL_VERIFY
  -> FINALIZE
```

別素材への変更、anchor変更、bounds拡張、破壊許可追加、Mob対処は自動で行いません。固定manifest内で別の可視face、同等のwork pose、already-satisfied skipを試すことはできます。world divergence、資材不足、到達不能、未対応能力では安全に停止し、stage、cell、現在state、必要なhandoffを返します。

## one-shotの再定義

建築におけるone-shotは、ユーザーの1依頼でも、LLMがセルごとに数十回会話する意味でも、1packetで完成する意味でもありません。

推奨する契約は次です。

1. LLMまたはユーザーが建築パッケージとparameterを1回確定する
2. compilerが全セル、資材、作業地点、上限を副作用なしで検査する
3. 必要資材がdeclared staging inventoryに揃った後、`plan_ref`を1回開始する
4. runnerがcheckpoint間をLLMなしで進める
5. 完成をpositive evidenceで確認するか、再計画材料を伴って停止する

将来の公開形は、既存13 kindへ急いで追加せず、development-only runnerで実証した後に検討します。候補は`compile_build_package`というread-only操作と、`execute_build_package(plan_ref, anchor, bounds, idempotency_key)`という1つの有限routineです。

## tokenとcontextを抑える方法

- レビュー済みtemplateは`template_ref + parameter`だけをLLMが出す
- 新規形状も`plane`や`cuboid_shell`で表し、全セルを文章へ展開しない
- 展開済みmanifestはhashで参照し、MCP応答へ毎回再掲しない
- 通常進捗はstage、completed/expected、現在pose、資材残数だけ返す
- cell詳細は失敗セルとその近傍だけをcursor付きで取得する
- LLM pollingを毎tick行わず、terminalまたはtyped stop時だけ再計画する
- 実行traceはlocal artifactへ残し、LLM contextへは要約を渡す
- 稼働試験は定義済みprobeをrunnerが実行し、生ログの解釈をLLMへ任せない

これにより、建築セル数とLLM token数をほぼ切り離せます。tokenは要件選択と例外対応へ使い、反復作業には使いません。

## 安全境界

建築パッケージを導入しても、既存の安全規則は緩めません。

- local arming、emergency stop、manual input、world session fence
- finite bounds、deadline、travel、mutation、resource上限
- current-only preconditionとserver-confirmed postcondition
- raw input、任意packet、任意command、任意コードを非公開
- unknown、last-known、推定を成功扱いしない
- blind retryとtransaction rollbackを行わない
- container、fluid、multi-block、Entity操作はtyped capabilityがない限り拒否
- hidden cellを経路・支持・完成のBoolean oracleにしない

建築パッケージは「大きな権限」ではなく、既存の小さな操作を静的に検証して順序付けるものです。compilerが作ったmanifestも、各操作直前のlive checkを省略する理由にはなりません。

## 初版scope

最初からTTを対象にすると、建築planner、Mob、睡眠、水流、kill、drop回収を同時に検証することになります。初版は次へ限定します。

- flatで既知の作業床
- 1 region、徒歩で届く複数work pose
- jump、足場生成、落下移動なし
- canonicalな単セルBlockItem
- fluid、bed/door等のmulti-block、Entity、combatなし
- safe break / placement support allowlistを維持
- 全資材を開始前にstaging containerへ準備
- 1 packageの展開上限と総時間を固定
- completionは全target current exact、unknown 0

このscopeで、LLMが生成するのはtemplateとparameterだけ、施工中のセル選択は0回、という状態を目標にします。

## 実装順

### 1. Pure compiler

- closed schemaとcanonical hash
- geometry primitiveの有限展開
- Minecraft registryによるfull BlockState正規化とtransform
- duplicate、bounds外、overlap、unsupported blockの拒否
- material manifestとdependency graph
- Minecraftを起動しないunit test

この段階ではworldを変更しません。

### 2. Access planner

- flatな安全床から候補work poseを生成
- normal reach、LOS、FOV、support faceで担当セルを割り当て
- 全セルを覆えないplanは開始前に`UNREACHABLE_BUILD_CELLS`で拒否
- pose間routeを既存navigation制約で事前検査

今回のように施工を始めて3セル目で視点不能になる問題を、最初のmutation前に検出します。

### 3. Development-only runner

- 既存`navigate_to`、`transfer_items`、`apply_block_plan`を決定論的に組み合わせる
- LLMを呼ばずstage loopを進める
- cancel、death、manual input、chunk unload、資材差替えで安全停止
- checkpoint後の再開とalready-satisfied skip

公開MCP surfaceへ追加する前に、fixture内部で有効性を確認します。

### 4. Generic live gates

次の順で対象を広げます。

1. 10×10の乾いた床と中央穴
2. 複数方向の壁、階段、slab、hopperを含む小構造
3. 複数work poseを必要とする2層の小屋
4. 中断・再開、外部divergence、資材不足
5. fluid専用adapterと動的流体試験
6. bed等multi-block
7. 準備済みEntity handoff

TTは5〜7が個別に合格した後の統合試験にします。

### 5. 公開契約

development gate通過後に、建築パッケージを新しい有限routineとして公開するか判断します。その際はADR 0002を更新し、「workflow DSLは非公開だが、有限な建築IRは許可する」という境界を明文化します。

## 受入条件

最低限、次をすべて満たすまで「材料があればone-shot建築」と呼びません。

- LLMは開始後にセル列挙、視点選択、phase分割をしない
- compilerが全セル、資材、work pose coverageをmutation前に確定する
- 複数work poseを通常移動で巡回できる
- 全targetがserver-confirmed current exact、unknown 0になる
- 中断後の再開で重複設置・余分な消費がない
- 未対応能力を暗黙にfixtureへ肩代わりさせず、resultへ明示する
- failure時に全input、screen、slot、camera、Voiceを解放する
- hidden stateやserver内部情報を成功根拠にしない
- 実行セル数が増えても、LLM contextがほぼ一定である
- 稼働機構はstatic BlockStateだけでなく、定義済みの時間発展probeに合格する

## アイアンゴーレムTTの扱い

TT作業は一旦停止します。現在のワールドは完成例ではなく、次の不足を再現した評価artifactです。

- agentが3セルだけ施工し、安全に停止した状態
- fixture支援で床を完成させた状態
- 視線補正後にゴーレムがspawnした状態
- 水流が搬送せず、kill・drop・回収が未確認の状態

比較用saveは`run/iron-farm-evaluation-snapshot-20260821-0043`へ保存しています。今後TTを再開するときは、このworldを完成品として修正し続けるのではなく、上記generic gatesを通過した建築runnerと、独立したfluid・Mob・collection fixtureを組み合わせて新しい破棄可能worldで評価します。

## 判断

LLMだけで巨大な計画を反復実行する方式は、tokenを増やしても安定性が比例して上がりません。一方、施設ごとの専用`build_iron_golem_farm`を増やすと汎用性を失います。

中間案として、LLMは有限な建築パッケージまたはレビュー済みtemplateのparameterを作り、pure compilerと固定runnerが施工へ落とす構成が最も妥当です。これなら、LLMの得意な要件解釈と設計選択を残し、不得意な座標列挙、作業順、視点、retry、同期をtoolへ移せます。
