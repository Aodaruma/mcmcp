# はしご区間移動の再受入（2026-11-15、#81 / PR #83）

状態: **未合格。長い下降と途中横床への乗移りに再現する失敗があるため、この記録だけでは merge / release を認可しない。** 短い下降、上昇、取消後の保持は合格した。製品コード変更はない。

## 対象と境界

- source: `09a63b68297f28d87f9533a848d3ea1abf1e6812`。前回 `d7c8b21` からの変更は検証文書のみ。
- 製品 JAR SHA-256: `3A43516C1C3906AA224A62AF708D12D4E4FDE43D2F87D19E9C7D99F0C3717595`。
- dev harness JAR SHA-256: `355DB948DC2686E49683D4E68A0768C56AB984874DDA28888F9FA65A20B07CEB`。
- 隔離 Docker clone / Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25。live の公開 Tool 5 本と source の catalog を完全照合した。
- 排他的な 45 分 lease、world・MOD・設定の 66 ファイル（world 61）を hash 付きで事前保全。通常 profile は変更しない。
- T0 前の固定コマンドだけで、`x=200..204, y=200..217, z=198..202` 内に床と 14 段の ladder を配置した。ladder は `(202,201..214,200)`、west 向き、backing は east の `(203,201..214,200)`。上側の入口床は `(201,212,200)`、途中の両側床は `(202,207,199)` と `(202,207,201)`。
- 各 T0 後は公開 MCP だけを使用し、画面・管理コマンド・手動移動による補助は行っていない。dev harness は初期配置のみで、試験中の地形更新はない。

## 合格した経路

底床から ladder 段 201 に入り、再観測ごとに段 206、211、213 へ進む 4 Action が成功した。上昇はそれぞれ 52、58、30 tick。各終端の `ladder_hold=true`、HP20、READY を確認した。観測にない底の座標は Action 開始前に `NO_KNOWN_PATH` で拒否された。この world は既存の床知識も持つため、未配送座標の拒否コードを常に `TARGET_UNKNOWN` とする主張はしない。

別の有限試験では、上昇中の公開 `agent_cancel_action` → `cancelled` → `ladder_hold=true` → 2 秒間の高さ維持 → 新 Action で上昇再開 → 205 から 204 への 1 段下降を確認した。**4 Action / 36 check / 23 request が合格**。短い下降は `succeeded` で、段上の保持も継続した。これらは長い下降の合格を意味しない。

## 未合格の再現

すべて `navigate_to_known`、`movement` のみ、許容差 0.4、予算 30 秒 / 600 tick / 32 block、interaction・破壊・設置予算 0。target は直前に配送された `navigation_target` を使った。`PROBE_ALLOWED` はこの実装における有効な ladder 経路であり、床支持がないことだけを理由に除外していない。

| ケース | 結果 |
| --- | --- |
| 高所床の足位置 `(201.5,213,200.5)` から段 `(202,208,200)` | tick 6 に `RECOVERING / goal_preempted_for_safety`、78 tick で `failed / SAFETY_RECOVERED`。Action `ed05af23-5a5f-4e1a-bf8d-93adc6ca8223`。 |
| 公開 MCP だけで上昇して停止した段 213 から、再観測後の段 208 | 開始位置 `(202.51249998807907,213.21449622092544,200.48380347579933)`、`ladder_hold=true`。tick 20 に安全回復へ入り、64 tick で `failed / SAFETY_RECOVERED`。Action `01ddd8f3-6d21-46f9-a1b0-4e0046362d96`。床からの entry と独立して再現した。 |
| 段 208 から途中の north 側床 `(202,208,199)` | 15 tick で `failed / BUDGET_EXCEEDED`、evidence は `replanned_route_remaining_occurrence`。移動実測 1.46 block。Action `cb9c78d0-7f57-4793-9ef6-98fbcdb56a73`。対岸 south 床への続行は行っていない。 |

両下降失敗は下床へ安全回復し、HP20 / READY、interaction・破壊・設置 0 を維持した。横床失敗後も HP20 / READY を確認した。公開 trace は内部の安全分類理由を公開しておらず、根本原因をこの試験から断定しない。

初期の試験スクリプトは拒否コードを `TARGET_UNKNOWN` に限定し、ladder record を `CONFIRMED` だけに限定していた。これは試験側の誤りとして訂正した。さらに最上段の上側床から有効な下段経路が配送されなかったため、支持と接触を確認できる入口床へ fixture を変更した。これらの準備・判定訂正を、製品の不具合修正や合格数へ計上していない。

## 保存と復旧

全 Action の終端を確認し、ゲームの通常 UI で保存してタイトルへ戻った後、world 不在・実行中 Action 不在を公開 state で確認した。container を停止し、事前の **66 ファイル / world 61 ファイルすべてを SHA-256 で復元照合、不一致 0**。lease を解放した。Docker Desktop は試験前から稼働していたため維持した。

公開応答は認証情報を含まないことを検査して記録した。長い下降と横床の失敗を保持し、成功した短い試験だけを全体合格として扱っていない。Esc / OFF、欠損段、水、低天井、他方位、死亡・disconnect は今回は注入していない。修正後は上記 2 系統の再現と対岸乗移りを再試験する必要がある。
