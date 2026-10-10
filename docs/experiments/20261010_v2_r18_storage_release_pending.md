# 通常R18の収納release_pending再発（2026-10-10）

## 保存された状態

地図アート担当から14:16 JSTに保存された報告。通常ゲームの所有は同担当にあり、開発担当からゲーム入力・画面操作・再起動・移送再送は行っていない。証拠は `_PLAYING/map-art-MFwtpM21wWc-3x3/duper-operation/r18-batch2-storage-release-pending-20261010.json`。

- 5分batch2は同じscriptでOFFとfresh OFFを確認済み。End中央 `(100.430232,49,0.360975)`、体力20・空腹20、session `a35496b3-85fd-4628-bedb-a3534db3c27b`。
- 直前のバッグinspectで完成品3,630個（白251個を含む）を確認。これは失敗移送前の数量で、現在値として再利用しない。
- `storage_slot:6` へ黒コンクリートパウダー64をstoreしたaction `07f2366d-2ff0-4e86-8801-2baeb743320e` が `known_menu_update_deadline_exceeded`。`phase:releasing`、`release_fault:true`、`release_confirmed:false`、`release_pending:true`。
- beforeはplayer640／bag286、confirmed_count:0、effects UNKNOWN、unconfirmed:true。10秒時の途中結果にはunconfirmed:falseでeffects未掲載だったが、29秒以降にUNKNOWNが保存されている。初期のfalseを無変更の証明に使わない。
- cancelを1回受理し、62.34秒でもrunning、cancel_requested:true、completed_operations/max_operations=240/240。所持black640を観測。これだけでは移送ゼロ・空cursorを確定できない。
- state/statusは読取り可能。control.mode=agent。known_menu省略はREADY以外で省略する既存仕様でも起き、画面の有無やcursorの空きの証拠ではない。

この時点では復旧未確認。今回の直接原因（cursor不一致、古い同期証拠、menu不一致など）は公開結果から確定できない。

## R18の終了条件

`V2OperationJobExecution.publishAfterRelease` はdriver.closeと入力解放の両方を確認してからterminalを公開する。取消・操作数上限・経過時間は新しい移送を止める条件であり、未確認cursorを強制解放する期限ではない。120秒経過だけで終了する保証はない。

`MinecraftKnownMenuPort.maintainRelease`／`releaseStorageCursor` の条件:

1. 同じ所有menu・storage・sessionに対して新しいserver証拠があり、空cursorとその更新revisionが最後の送信より新しければcursor解放を証明できる。
2. cursorが空でない場合は予測とitem/components/数量が一致し、元slotが同種または空で容量も足り、現在のmenuも一致する場合だけ、元slotへのcleanup clickを1回送れる。新しいserver証拠で空を確認するまで終了しない。送った可能性のあるcleanup clickを再送しない。
3. 所有画面を閉じた後の通常画面・inventoryMenuへの遷移と、必要なcursor解放証拠を確認する。pending transferの解放証拠がないまま画面だけ閉じてもrelease_pendingが残る場合がある。
4. player／level／connectionの実体が変わるworld境界では旧menuの所有を終了できる。これは移送数量の成功確認ではなく、UNKNOWNを保持する。

release_faultは解放確認に問題があった記録であり、新しい証拠が後から到着しても一切進めないという意味ではない。ただし、`KnownMenuProfileSupport.current(...,true)` には同一session/menu、server/clientの全slot・cursor・state ID一致等が必要で、証拠が新しくならない場合は待機が続く。今回そのどの条件が欠けたかはまだ特定できない。

R18と現作業ブランチで、収納driverおよびこのmenu解放処理に差分がないことを確認した。R20の収納先選択修正や安全受付の理由追加は、稼働中のこのcleanupを直すものではない。

## 現在の公開APIでできること

同じactionの有限wait付きget_action、直接get_stateのcontrol/world/action/inventory、get_mcp_statusを1組保存する。cancelは既に受理済みで、繰り返してもcursor証明を補えない。新しいstorage inspectや移送、raw入力、Waystonesを開始して既存所有を迂回しない。

R18に強制解放・任意画面閉鎖・MCP OFF・サーバー切断を行う公開APIはない。新しいserver証拠で既存cleanupが完了しない場合、**公開APIだけで確実に復帰させる手順は確認できていない**。

隔離R18で実機確認済みの復帰例は、所有者側でGUIのMCPをOFFにして通常のworld終了を行い、world境界で旧所有を解消するもの。EscやOFF単独では終了待ちが残り得る。JVM再起動やJAR交換そのものはこの解放条件ではない。今回この操作を開発担当で行ったり、通常profileの操作権を取得したりはしていない。所有者が通常終了・再接続で復帰した場合も、freshなバッグ・手元の実数、周囲のアイテム、装置OFFを確認し、元store64は再送せずUNKNOWNのまま区切る。

## 追加診断の不足と次の隔離試験

現在のstorage_progressにはphase、awaiting_open_evidence、wait_reason、failure、releaseの3フラグしか出ず、以下を区別できない。今後の診断候補として、固定enum・boolean・有界な数値で追加し、slot全体や任意例外本文を公開しない。

- 所有menuがまだ同じか、通常inventoryMenuへ戻ったか、同期contextを取得できるか。
- cursorが未観測／空／予測一致／不一致か、証拠が最後の送信より新しいか。
- cleanup clickを送信済みか、そのACK待ちか、元slotの不一致／容量不足か。
- 最後の移送送信revision、cursor証明revision、解放判定のtick、固定のrelease待機理由。

実機は新baseline・leaseを作り、許可時間帯23:00～12:00 JSTにaod-mimoidの隔離Dockerで、満杯のplayer在庫＋bag黒286＋store64、並行収納更新、ACK遅延、予想外cursor、menu閉鎖を個別に試す。証拠を取得し、数量UNKNOWNと解放完了を分けて評価する。今回の根本修正・追加診断・この再発ケースの隔離再現は未実装／未実施。

別件として、End扉は感圧板を避け、`(95,49,-2)` からwest面の扉 `(97,49,-2)` をopen=trueとして `(99,49,-2)` へ通過できたとの報告を受領。これは手順を変えた成功例で、先の扉端REPLANからの自律退避を実装・検証したことは意味しない。
