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

### 実ゲームの1往復 / In-game round trip

候補2（SHA-256 `58E561AFDB7883421D781D64C554512DB3FA2646BF6475FA1839610AD3A6441F`）ではpreflightを通過した。最初は2tick・camera4.5度・interaction0で`frame_front_not_visible`となったが、1ブロック横へ近づいてから次の一往復が成功した。最初の拒否がfog欠測か実LOSかは断定できない。

- remove `61605de7-5a98-43fc-9d8d-978a3ceb22f8`：33ticks・1interaction、深層岩→空をCONFIRMED。
- fresh観測で`frame_display.item:null`を確認。旧品は観測済み足場へのMCP navigation後、所持0→1で回収確認。
- insert `a21afcc2-b0bc-494d-acd8-c1a35c8f9e49`：14ticks・1interaction、空→深層岩、所持1→0をCONFIRMED。
- 額縁本体は残り、rotation 2を維持。MCPはREADYへ復帰した。

旧品回収に使おうとした`collect_visible_item`は、fresh観測後も2回`TARGET_UNKNOWN`で拒否された。専用回収操作の成功とは扱わず、今回はnavigationによる通常pickupと所持品差で確認した。結果の`reference_requirements`がcontainer_labelを案内する誤りも見つかり、frame_displayを案内し、cloneで意味上のitem条件を保持するよう修正した。

One remove/collect/insert round trip succeeded, preserving the frame body and rotation. Removal took 33 ticks and insertion 14 ticks, with one interaction each. Collection was verified through ordinary pickup after MCP navigation and an inventory increase; the dedicated collection action was rejected twice and is not counted as a successful collection test.

候補2ではその後、倉庫の表示変更17か所を完了したとの報告を受けた。作業の区切りで未回収drop・未格納cargoなし、READYを確認。一方、次の額縁で`SAFETY_PRECONDITION / Reason: frame_item_changed`が2回あり、額縁の品目と回転が観測上変わっていなくても、予約時の認可条件で拒否されるケースが残る。候補3へ更新して再確認する。

### 描画fog欠測の待機 / Waiting for a current fog sample

別の額縁でも照準中14tick・58.5度・interaction0で`frame_front_not_visible`を記録した（Action `4dc5b749-cf81-44ae-8347-138e7a79933b`）。既存adapterはfog欠測と本当の不可視を同じ失敗へ変換していたため、欠測だけを内部`observationPending`で区別する。

欠測中は可視item/rotationの読み取りへ入らず、総400tick・dispatch後ACK60tickを延長せず待つ。サンプル復帰後は従来の正面・半径・LOS・表示一致を再検証する。キャンセルやtimeout時にpendingの既定値をUNKNOWN afterへ使わず、dispatch後の再送もしない。既存の実機拒否が欠測由来だったかは、最終JARでの照準継続を確認して判定する。

準備/dispatch後の欠測からの復帰、元の400/60tick期限、remove/insertのキャンセル、UNKNOWN afterの省略、実LOS拒否、欠測中の可視read非実行を回帰検証した。参照説明の修正と合わせて、unit 1,194件・harness 13件・admin bridge 21件の計1,228件、build/isolationが成功した。

最終候補3（SHA-256 `C751AC8373C6B37838EA2D8C951F039261C0B5189B5C1C6585C15FFA766342B7`）の実ゲームで、W01-C06-L01のoak_sapling除去が29ticks・camera98.36度・1interactionでCONFIRMEDとなった（Action `1d35ee68-30a6-4cb8-a636-8579b785c609`）。空表示・rotation 0・本体残存、正しい`frame_display_entity_ref`と`/records/*/{entity_ref,frame_display}`の案内を確認し、落下した苗木1個も手持ちへ回収済み。照準の継続は確認できたが、以前の各拒否の直接原因まで確定したとは扱わない。
