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

## 検証 / Validation

期限後の境界変化、同一ownerとserver空cursor証拠、FAULT中のretire拒否・遅延確認後の1回だけのretire、pending cleanup中のcancel処理と新規Action拒否を回帰テストに追加した。額縁操作を含む統合検証で`test` 1,178件・`harnessTest` 13件・`adminBridgeTest` 21件の計1,212件、`verifyHarnessIsolation`、`build`が成功した。

実ゲームは通常終了済み。再起動後、個人所持品・送り元・送り先をinspectし、未確認の12個を照合してから作業を再開する。実ゲームでの回復確認は引き渡し後に追記する。
