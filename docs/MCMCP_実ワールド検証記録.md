# MCMCP 実ワールド検証記録

- 更新日: 2026-08-27
- 対象: Prism Launcherの単一検証profile、Minecraft 26.2 / NeoForge 26.2.0.59
- 状態: 木こりの最小gateは合格、畑テストは進行中

## 完成目標と判定原則

完成目標は、LLMがMinecraftの画面操作を代行する`computer-use`を使わず、MCMCPが公開するMCP Toolだけで一連の作業を完遂できる状態である。

したがって、次はテスト準備または機能ギャップであり、MCPによる成功には数えない。

- chest GUIをマウスで開き、itemをhotbarへ移す
- fence gateをマウスで開閉する
- Screen上のMCP操作を手動でONへ戻す
- Minecraftの移動・視点・attack・useを外部のraw key / mouse操作で補う

最終的な合格判定では、観測、item取得、container操作、gate操作、移動、作業、成果確認をMCP呼出しだけで連続実行し、各操作をMCMCPの監査traceとworld / inventoryの事後条件で確認する。

## 木こりテスト

### 対象

石の柵で囲われた安全な領域にある木を伐採し、原木を回収する。将来の加点目標は、苗木の植え直し、巨木を含む全原木の回収、各原木1 stackの収集である。

### 確認できた結果

| 項目 | 結果 | 根拠・範囲 |
|---|---|---|
| 既知地点への初回移動 | 成功 | 最小木こりgateで、地上から届く既知のoak幹まで移動できた |
| 既知oak幹の伐採 | 成功 | Action `32a87494-4768-445a-a142-3b688566bbbb`が`SUCCEEDED`。3 blocks、73 ticks、camera 59.63° |
| fixtureの安全条件 | 成功 | `phase5.tree.gate=PASS`。柵、支持面、player位置を保持し、axe damage 3を確認 |
| 使用道具の自律取得 | 未検証 | iron axeはテスト前に用意されており、取得工程は木こり成功に含まれない |
| drop回収 | 未検証 | 原木itemの全回収は保証していない |
| 葉の破壊を伴う接近 | 未実装・未検証 | 隠れた幹の探索と葉を除去する経路は最小gateの対象外 |
| 巨木、高所、異種木 | 未検証 | 地上から届く3段oak幹だけが合格範囲 |
| 苗木の植え直し | 未検証 | 植林は最小gateの対象外 |
| 1 stack収集 | 未検証 | 伐採、drop回収、植林、成長待機の反復は未実施 |

### 改善点

1. chest等から適切なaxeをMCP経由で取得し、既存itemだけを使う。
2. leaf blockを可視・既知targetとして安全に破壊し、幹とdropへ到達できる経路を再計画する。
3. drop entityの観測、到達可能性、inventory増加を使い、場内の原木回収を検証する。
4. full-blockの高低差、足場、落下リスクを扱い、巨木と1 block高い木へ段階的に対応する。
5. 元の土と樹種を記録し、対応するsaplingの回収・植え直しを事後条件付きで実行する。

## 畑テスト

### 対象

chest内のhoeとwheat seedsを取得し、fence gateを通って畑へ入り、耕す、植える、成熟を待つ、収穫する、植え直す、を反復してwheatを1 stack集める。

### 2026-08-27時点の結果

| 工程 | 結果 | 詳細 |
|---|---|---|
| chest内itemの確認 | 手動補助 | `computer-use`でchest GUIを開いた |
| hoeとseedsの取得 | 手動補助 | netherite hoe 1個とwheat seeds 64個をhotbarへ移した。MCP成功には数えない |
| 畑入口付近までの移動 | 一部成功 | MCPの移動で入口付近まで到達した |
| 狭路の通過 | 失敗 | `PATH_BLOCKED`で停止した |
| gateへ視点を向ける | 条件付き成功 | camera budget 180°では`BUDGET_EXCEEDED`。360°での再試行は65.37°、8 ticksで成功した |
| 経路再計画後の移動 | 失敗 | `navigate_to_known`の`primitive_replanned_route`が`BUDGET_EXCEEDED`になった |
| fence gateの開閉・通過 | 手動補助 | `computer-use`で開閉・通過した。MCP単独成功には数えない |
| MCP操作の再有効化 | 手動補助 | Screen上でONへ戻した。MCP単独の連続実行ではない |
| `till_known_block` | 失敗 | dirt `(-15,55,-14)`に対し、camera 24.37°、5 ticks、interaction 0の時点で`BUDGET_EXCEEDED (primitive_budget)`。worldは未変更 |
| 播種、成長待機、収穫 | 未到達 | 耕作primitiveの予算不整合を先に修正する |
| wheat 1 stack | 未達 | 反復試験を開始できていない |

### 改善点

優先順は次のとおり。

1. `interact_known_block`相当の型付きprimitiveでfence gateの現在の`open`状態を観測し、通常use後の反転を検証する。
2. gateの開閉でKnown Traversability Mapのedgeを更新し、player AABBが通る幅の狭路を過度に`PATH_BLOCKED`へしない。
3. `face_known_position`とnavigation replanのcamera / motion budget消費をtraceから分離して調べ、同じprimitive内の再計画でbudgetを意図せず使い切らない。camera上限は実行前に十分な値を宣言する。
4. chestのinventoryを許可された観測として取得し、slot指定のpickup / transferを型付き操作と事後inventory検証で実装する。
5. 上記の入口工程がMCP単独で通った後、`till_known_block`、`plant_known_wheat`、`crop_mature`、`harvest_known_wheat`を1区画ずつ実ワールド検証する。
6. 最後に、播種可能数、成熟状態、wheat / seedsのinventory差分を見ながら、wheat 64個まで有限反復する。

`till_known_block`の失敗は、admissionが観測面の照準点でcamera costを見積もる一方、semantic preparationが複数の到達可能面から別の照準点を選べるため、実消費がprimitive単位の見積もりを上回ったことが原因である。修正後は、実行側の照準点がadmissionへ公開されるまで、1回のblock mutationに安全側の360°上限を予約して再検証する。

## 次回の受入手順

### 木こり

1. MCMCP操作をONにする。これは試験開始時の利用者による明示許可とし、以後の作業操作には含めない。
2. MCPだけでchest等からaxeを取得する。
3. MCPだけで木へ移動し、伐採、drop回収、苗木の植え直しを行う。
4. inventoryとworldの事後条件を取得し、computer-useによる補助がなかったことを監査traceと合わせて記録する。

### 畑

1. MCPだけでchestを開き、hoe 1個と必要数のseedsを取得する。
2. MCPだけでgateを開け、通過し、必要なら閉じる。
3. MCPだけで耕作、播種、成熟待機、収穫、再播種を行う。
4. inventoryのwheatが64個以上であることを確認する。
5. 各ActionのID、terminal state、failure code、ticks、distance、camera、interaction / place / break数を本記録へ追記する。

途中に手動補助が1回でも入った場合、その試行は部分機能の診断結果として残すが、end-to-end合格にはしない。
