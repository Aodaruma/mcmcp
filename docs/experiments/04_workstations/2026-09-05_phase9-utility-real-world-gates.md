# Phase 9 utility real-world gates（2026-09-05）

## 目的

知人サーバーで需要が見込まれる、リアルタイム判断をあまり要しない反復作業の最小実ワールドsliceを検証した。対象は、額縁ラベル倉庫、釣り、丸石生成、MCPフォーム許可付きkill-zoneである。

## 環境

- 実行先: `ssh aod-mimoid`
- container: `mcmcp-hard-building-20260902`
- Minecraft Java Edition 26.2 / NeoForge 26.2.0.59
- MCP protocol: `2026-07-28`
- 公開toolは固定5本のみ
- 製品jar: commit `80372cc` 相当
- 最終runner/harness HEAD: `3665c83`

全試験はfixture生成後、通常プレイヤー操作を所有するMCP Actionだけで実行した。コマンドやfixtureはT0前の初期化にのみ使い、合格判定対象の移送・釣り・破壊・攻撃には使っていない。

## 結果

| Gate | 結果 | 実ワールド証跡 | 確認内容 |
|---|---|---|---|
| 丸石生成 | PASS | `build/eval-artifacts/20260905-f4b7e0a-cobble-r8` | 1 Actionで11回破壊、8個回収、上限16回、位置・体力不変、入力解放 |
| 額縁ラベル倉庫 | PASS | `build/eval-artifacts/20260905-7f14c76-warehouse-r2` | 生鉄16個をlabel付きchestからtakeし、別label付きbarrelへ全量store |
| 釣り | PASS | `build/eval-artifacts/20260905-367a7b7-fishing-r3` | cast、splash音待ち、reel、釣果1個、竿damage +1、残存entityなし |
| kill-zone | PASS | `build/eval-artifacts/20260905-kill-zone-r4` | Minecraft許可画面なしのMCP form、攻撃1回、confirmed 1 / unknown 0、許可消費 |

4本すべてで、terminal後に`control.mode=ready`、全Action terminal、取消不要、内部input owner非公開を確認した。対応する4本のmock gateも最終HEADでPASSした。

## 試行と躓き

### 丸石生成

照準面の選択、再生成境界、結果schema、drop損失、gateの時系列前提を順に修正した。主な実機試行は次の通り。

1. 初期runnerはfixture開始時の照準を仮定し、`WORLD_CHANGED`。
2. block中心への照準では実際のrayが`UP`へ入るため、宣言面との不一致を修正。
3. 1個破壊後、effectの`cycle`が公開schemaになく`agent_get_action`がinvalid result。schemaへ1..64のbounded fieldとして追加。
4. 再生成tickと照準ray再計算tickのずれを、面変更と誤認して`SAFETY_INTERRUPTED`。初回は指定面を厳密確認し、反復中は次の攻撃直前に同一座標・同一state・到達可能性・tool・不動視点を再確認する形へ変更。
5. 8回破壊してもdropが7個しか拾えず`CONDITION_TIMEOUT`。絶対在庫目標8は維持し、破壊上限だけ16へ拡大。
6. Actionは9回破壊で成功したが、gateが「破壊直後effect」と「後tickのdrop拾得」を同時刻と誤認。破壊証明と最終在庫証明を分離。
7. 最終runは11回破壊で8個を回収してPASS。

これは連続左クリックを公開する実装ではない。固定座標・既知block state・既知tool・絶対在庫目標・破壊回数・deadlineを閉じた`operate_known_cobblestone_generator`が、内部の短いattack leaseを必要時だけ所有する。

### 額縁ラベル倉庫

最初の実機runで`/mcmcp_fixture phase5 label_transfer`だけがcommand treeへ未登録と判明した。mode実体・autorun・テストは存在していたため、手動command登録を追加してharnessを再配布した。次runでPASS。

現在の安全な対応範囲は「中身が1種類のitem frame / glow item frame」「single chestまたはbarrel」「額縁itemとのexact match」である。Action直前に額縁entity、表示item、取付方向、対象container、視認性を再検証する。double chest、空額縁、カテゴリを意味する抽象ラベルはfail closedであり、今後の拡張対象である。

### 釣り

製品動作は初回の実機runからcast、bite検知、reel、loot取得まで成功した。ただしgateが、reel直後の観測frameに一時的に残るbobber/item recordを即座に失敗扱いした。最大2秒のfresh frame settlementを追加し、次runでPASS。

現状は1サイクルを構成する低レベル意味操作（cast / sound-bound wait / single-use session refによるreel）である。任意回数を1 Action内で回す専用fishing jobは未実装で、LLM側がfresh stateと有限deadlineを保ちながらサイクルを再発行する必要がある。

### kill-zone

初回formは正常に`input_required`を返したが、gateが直前に完了した釣りActionのstate履歴を「新しいAction」と誤認した。form前後のAction ID同一性で判定するよう修正した。

またform待ち中は、Minecraft画面や入力lockを作らず`control.mode=ready`を保つ一方、公開state上は対象policyを`pending`として保持する。これは許可待ちを監査可能にする論理状態であり、Minecraft内の許可UIではない。失敗したform試験のpendingはMCP OFF→ONで安全に破棄し、fresh fixtureから再試験した。最終runはform承認、Action発行、攻撃1回、上限終了、consent消費までPASS。

## 実装上の境界と残件

- 長時間待機は有限な`wait_ticks` / `wait_until`とAction deadlineで構成できる。
- 丸石生成の押下保持は専用のbounded semantic operationとして実装済み。
- genericなraw left/right holdは誤対象操作の影響が大きいため公開していない。
- 自動釣り機など、既知stationへ右クリックを一定条件・一定時間だけ保持する専用jobは未実装。
- 釣りの複数サイクルを1 Actionとして所有し、失敗・取消・timeoutで必ずreel cleanupする上位jobは未実装。
- 今回の合格はonline oracleである。artifact内のoffline oracle manifestは、後続fixtureへ切り替えたため`pending_world_close`のままであり、今回の4ゲートPASSには算入していない。

## 関連commit

- `80372cc` repeated cobblestone generator reporting / regeneration境界修正
- `f4b7e0a` drop損失を含むbounded generator gate
- `7f14c76` label transfer fixture command登録
- `367a7b7` fishing entity cleanup settlement
- `ecc97d8` form前後の既存Action履歴保持
- `3665c83` MCP form待ちをlogical pending consentとして検証
