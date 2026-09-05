# 額縁表示品の変更 / Item-frame item operations

倉庫整理後の表示品を実内容へ合わせるため、Vanillaの額縁と輝く額縁に対する閉じた2操作を追加する。大チェストを除外する既存container_labelには依存せず、可視entityのopaque参照を使う。

## 操作境界 / Operation boundaries

- `remove_visible_frame_item`はexpected_item付きの表示品を空手で1回だけ攻撃し、同一frame本体が残り、回転不変で空表示になったことをserver item field ACKで確認する。drop生成・回収の成功は主張しない。
- 旧itemの回収は、次の再観測と既存`collect_visible_item`で別途行う。
- `insert_visible_frame_item`は観測した空frameへ既存hotbarの1個を通常useで挿す。同IDの異なる成分候補は拒否し、表示ACKとserver選択slot payloadの1個消費、選択外inventory不変を確認する。
- 両操作とも単独top-level node、400ticks・20,000ms、1interaction。未確認結果を再送せず、キャンセル・再計画・pause時も有限cleanupを通る。

Vanillaの通常攻撃は、frameが空なら本体を壊す。通常useも空でなければ回転する。操作直前にこの条件を検証するが、client-only protocolにはatomic compare-and-swapがないため、別playerとの競合を完全に防ぐとは主張しない。旧品のdrop率・固定flag等の非同期hidden設定も読み出さない。

## 観測と同期 / Observation and synchronization

`frame_display={item,rotation,aim_point}`は正面中心のfog・LOS検査後だけ取得する。item=nullは確認済みの空、field欠落は未確認。配送ACKでtype/ref/位置/AABB/表示item/回転/正面aim点を最大128件保持し、同じ内容の最新観測と配送100tick期限を照合する。静的surfaceの再観測でentity timestampや期限を延長しない。

EntityData packetのitem/rotationはfield別の受信revisionとして保持し、local predictionや別fieldのpacketを表示変更ACKにしない。session・level・ID・UUIDを束縛し、RemoveEntities・level終了・session変更で無効化する。insert用hotbar証拠もinbound payloadの9slotだけを保持する。

These standalone operations each send one ordinary interaction. Removal confirms an empty living frame, not a collected drop. Insertion additionally requires a fresh server hotbar payload showing exactly one consumed item. Both use acknowledged front-face observations and preserve rotation; unknown outcomes are never retried automatically. Concurrent players may still race the ordinary server action.

## 検証 / Validation

DSL入力・capability・単独Action制約、配送認可・期限・静的再観測、正面fog/LOS、packet field別ACKとsession/identityの無効化、1回dispatch・UNKNOWN・cleanup・成分混在・選択外slot保持を回帰テストで確認した。`test` 1,178件・`harnessTest` 13件・`adminBridgeTest` 21件、計1,212件が成功し、`verifyHarnessIsolation`と`build`も通過した。実ゲームでの表示除去・回収・挿入はJAR引き渡し後に別の操作タスクで行う。

All 1,212 automated tests, the production artifact isolation check, and the build passed. In-game removal, collection, and insertion are pending verification by the separate operator task.

### 実ゲームの配送後拒否 / Rejection after observation delivery

`1473d5b`のJARでは、正規Toolで取得した正面の額縁に対するremoveが3回ともdispatch前の`TARGET_UNKNOWN`となった。いずれも額縁への操作は送信されていない。記録例は配送tick2,589 / revision15,123、state tick2,604 / revision15,170で、観測の100tick期限内だった。

単独のage条件や配送ACKの有無だけではなく、full scan後のblock更新によりvisual revision barrierが進むとframe証拠を拒否する経路がある。実機artifactにはbarrier値がないため、この拒否の直接原因と断定せず、同じ条件を回帰テストで再現して、既存の配送済みframeの実再観測と固定分類の診断を追加する。

The first in-game attempts were rejected before dispatch despite being within the observation TTL. A block update after a full scan can invalidate the visual revision barrier; this path is being covered by a regression test and a fresh, strictly matched observation. The recorded game artifact does not include the barrier value, so the exact live cause is not yet established.

追加修正では、そのActionの対象refだけを現在のfog/LOSで再観測し、配送済みのtype/ref/位置/AABB/item/rotation/aim点が完全一致する場合だけ内部planningのrevisionを更新する。元の配送tick・60秒期限・公開frameは変更せず、container_labelも再認可しない。拒否時は未配送・期限・非可視・表示変更・revision等の固定理由を返す。

同じtick/revisionの回帰、元tick+101での再拒否、欠測・表示変更・壁時計期限・未配送の拒否・対象primitive限定を追加した。追加cleanupと内部格納上限を含む統合検証でunit 1,189件・harness 13件・admin bridge 21件、計1,223件、build/isolationが成功した。
