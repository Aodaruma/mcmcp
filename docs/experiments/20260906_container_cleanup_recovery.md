# コンテナ終了処理の回復 / Container cleanup recovery

まとめ移送の実ゲーム検証中、チェストを閉じる処理が失敗し、Actionがrunningのまま残り、キャンセルもclient dispatch timeoutとなった。発生状況は[まとめ移送の検証記録](20260906_container_batch_transfer.md)に記載している。

## 確認できた問題 / Confirmed defects

- cleanupの40tick期限後は無条件でreturnし、遅れて届いたACKや通常の画面終了があっても解放判定へ戻れなかった。
- cleanup待機による早期returnがcancel用の制御queueも止めていた。
- FAILED画面の情報を破棄するとき、同一ownerに対応するserver側の空cursor証拠を失う場合があった。
- 同じ例外が毎tick記録され、最初の原因を追いにくかった。

最初に終了処理が失敗した直接原因は、保存済みログからは断定できない。例外causeの保持を追加して、再発時の診断を可能にした。

## 修正 / Changes

期限後も所有権と操作ロックを保持する。新しいpacket revision・因果ACK・画面・menu・cursor・操作境界の変化がある場合だけ、既存の厳格な解放条件を再確認する。同じ境界でcloseを送り続けず、期限経過だけで成功にもしない。FAILED情報の破棄前に同一ownerのserver空cursor証拠を保持する。

cleanup待機中もcancel用queueを処理し、新規Actionはcaptureとcommitの両方で拒否する。初回faultと元例外を記録し、同じfaultの毎tick出力を抑止する。未確認の移送は再送しない。

After timeout, ownership remains locked until the original release conditions are proven. New server evidence or a changed screen/control boundary can trigger another check; unchanged failures do not repeatedly send close operations. Cancellation remains responsive while new actions remain blocked. The initial trigger is still undetermined, but exceptions now retain their causes for diagnosis.

### FAILED後のcursor証拠 / Cursor evidence after failure

追加レビューで、所有画面がFAILEDになった後のfull-content/cursor packetがcleanup用の空cursor証拠へ反映されない経路も確認した。遅延した空cursor ACKで回復できないだけでなく、古い空cursor証拠が新しい非空値を反映しないおそれがあった。

FAILED状態は維持したまま、同じsession・元のOpenScreen・menu・live screenに一致する新しいcursor証拠だけを更新する。別のOpenScreen（同じmenu IDの再使用を含む）は過去の証拠を無効化し、別画面へ所有権を引き継がない。port側も現在の証拠がある間は過去のtrueを蓄積せず、現在値へ更新する。

The cleanup ledger now accepts fresh cursor evidence for the original owned menu even after failure, without restoring action ownership or accepting a replacement screen. New nonempty evidence invalidates a previous empty-cursor proof.

追加回帰は関連98件を通過し、その後の統合テストでもunit 1,189件・harness 13件・admin bridge 21件、計1,223件、build/isolationが成功した。

## 検証 / Validation

期限後の境界変化、同一ownerとserver空cursor証拠、FAULT中のretire拒否・遅延確認後の1回だけのretire、pending cleanup中のcancel処理と新規Action拒否を回帰テストに追加した。額縁操作を含む統合検証で`test` 1,178件・`harnessTest` 13件・`adminBridgeTest` 21件の計1,212件、`verifyHarnessIsolation`、`build`が成功した。

修正JAR（SHA-256 `8A54047B7D93B2D34D9235DB7C8CD2D6EBA314D809A000118749AAFA8E3F6F4C`）へ置換して再起動後、独立inspectで送り元W02-C05-L05は空、送り先W02-C04-L01は深層岩2,037個、手持ちは12個を確認した。未確認のstoreが適用されていないことを解消してから新規store12を行い、送り先2,049個・手持ち0個・READY復帰を確認した（Action `04f9f652-32c0-4a1e-91e6-0bd5fa9a8e1b`）。これは再起動後の通常経路の確認であり、遅延ACKによるFAULT回復そのものの実証とは区別する。

After restart, independent inspections resolved the unknown transfer: all 12 items remained in the player inventory. A new store completed with the expected destination count and returned to READY. This confirms the normal restarted path, not an in-game reproduction of recovery from a delayed ACK.
