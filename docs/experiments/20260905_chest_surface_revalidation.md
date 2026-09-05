# 配送済みチェスト表面の再観測とON維持の確認

2026-09-05。computer-useを使わず、公開MCPで再現確認し、Javaの回帰テストとbuildを実施した。倉庫の左手前で観測と向き変更だけを行い、アイテム移動・破壊・設置は行っていない。

## 再現と原因

- 対象はoverworldの `(158, 65, -300)`、`minecraft:chest`、east面。
- state tick 6999 / revision 55041に対し、直前の可視面はtick 6992 / revision 55012。`inspect_known_container`は `TARGET_UNKNOWN: Container target requires a current matching visible surface` で受付前に拒否された。
- plannerは直近のvisual失効・対象mutation・位置ledgerのevictionを鮮度下限にしている。通常の全周観測は完成済みframeを返すため、更新が続く環境では配送から受付・予約・JITまでに観測が失効し、同じ静的チェストを再確認する機会がなかった。
- `face_known_block_face`は静的な配送済み面を向きの根拠にできるため成功する。この差はラージチェストのラベル制限とは別。個々のworld更新を銅ゴーレムやホッパーへ帰属できる証拠は取得していない。

## 修正

- `McmcpRuntime.agentPlanningFrame`で、失効した配送済み表面を通常の観測policyで再raycastする。`OmnidirectionalObserver`の既存tracerを使い、現在のeye・fog・radius・遮蔽・load制限を維持する。
- `DeliveredPolicyEvidenceStore`は位置・面・block・公開state/item・shapeが同じ再観測だけを内部planning viewへ採用する。未配送面、動的情報、公開frame、配送TTLは変更しない。
- 鮮度下限を削除せず、予約前・実行直前にも再確認する。コンテナのmenu照合・所有権・server同期の処理は変更していない。

## ON維持

現行実装はAction終了後に `READY` へ戻る永続leaseであり、ここは変更不要だった。再armせずに以下を順に成功させ、各終了後のstateで `control.mode=ready`、全8 capabilities、`ready_expires_at=null` を確認した。

| Action | ID | 結果 |
| --- | --- | --- |
| 1 tick待機 | `989e2343-0b0d-4df8-905e-9733280cd4e5` | succeeded、ON維持 |
| 1 tick待機 | `1e33d06b-cb8f-41c3-9eea-c3173695e11b` | succeeded、ON維持 |
| 左手前チェストへ向き変更 | `c421c4aa-9495-4ecb-8b5c-731ba8d18a14` | succeeded、ON維持 |

配送期限後の向き変更は拒否されたため、新しい観測を取得して再実行した。拒否後もONを維持していた。上記は修正前の実行中MODでの確認であり、一般の全失敗経路を実ゲームで再現したものではない。unit testでは同じleaseで100回のAction完了・recovery完了を繰り返し、明示OFF後に再開しないことを追加確認した。

## 検証

- staleな配送済みチェストは拒否、同じ面の新しい可視rayならinspect計画とapproach用表面認可が成功、さらに次のrevision失効があれば再び拒否される回帰テスト。
- 再rayで対象消失・block変更・fog距離超過・未来の証拠を拒否。未配送面と動的情報を追加せず、配送期限も延命しないテスト。
- `test`: 1,069件、`harnessTest`: 13件、`adminBridgeTest`: 21件、すべて失敗0。`verifyHarnessIsolation` と `build` 成功。
- 修正版production JAR SHA-256: `15AC9DA2123B3F86E175B13DD166C3D8998C1E4599437483228FF445605A6764`。
- 修正版の実ゲームへの反映にはMinecraft終了後のJAR差し替え・再起動が必要。修正後の実チェスト確認は再起動後に実施する。

## メニューボタンと説明

- 操作ボタンが現在のラベル幅へ縮んだ後も接続設定が初期位置に残るため、2つの間に余白が生じていた。接続設定も文字幅に合わせ、操作ボタンの左へ隙間なく追従させた。画面右端のHUDオフセットは維持する。
- ON後は複数Actionを続けられる実装に合わせ、日本語・英語の古い「1 Action」ツールチップを修正した。
- 利用者の終了連絡後、元JARを .codex-temp/chest-fix-backups へ保存して通常・検証Prismプロフィールを更新。ネイティブとCodex LocalCache経由の双方で同じSHA-256を確認した（ファイルの仮想化により同じ実体を参照）。

## 再起動後の追試と受付タイミングの追加修正

- 15AC9D版で同じ座標を再観測してもTARGET_UNKNOWNが再現。tick1656/rev12085に対し配送面tick1651/rev12024、再配送tick2867/rev24103でも拒否された。Actionは予約されず、操作はREADYを維持した。
- 再観測の追加だけでは不十分だった。受付・予約はpost-tickのread queueで動いていたが、霧のsignalはrendererと同じentity tickだけを許可する。player tick進行後はfallbackの1 blockになり、約3 block離れた面を更新できない。
- 受付snapshotと予約を既存のpre-tick control queueへ移動。通常の観測収集直後、player tick進行前に再観測する。霧のtick一致・距離制限、配送確認前の入力禁止、実行JITは維持する。
- control queueのmapped completionにも取消・受付中断時のrollbackを引き継ぎ、途中放棄した予約が必ず破棄される回帰テストを追加した。
- test 1070件、harnessTest 13件、adminBridgeTest 21件、verifyHarnessIsolationとbuild成功。追加修正JAR SHA-256: 3D64F7BD22DB1D1432264D04E9D6E895C614D1E64B2EE8E5D24F5ED473B14653 。実ゲームでの反映・追試は次の再起動待ち。
- OFF/ONのメニューは実画面で隙間なく右揃えになることを確認して撮影済み。ボタン枠・番号だけの画像を更新した。

## 音を含む内部frameの整合性

- 3D64F7版では再観測が進み、公開MCPが INVALID_ARGUMENT / Sound age must be fixed at frame completion を返した。現在tick1428に対する配送面tick1426から内部frameの完了tickを進めた際、元のSoundClueのageを保持したことが原因だった。受付前に拒否され、Actionは生成されていない。
- 内部frameのみ、元のlastObservedTickを保持して音の経過時間を再計算し、600 ticksを超えた音は除去する。新しい音を追加せず、既存の音の観測時刻・revision・配送frameを書き換えない。
- 音が残る場合と期限切れの場合、公開frameが不変であることを回帰テストに追加。test1071件、harnessTest13件、adminBridgeTest21件、build/isolation成功。
- 最終JAR SHA-256: FEBE730A4436C58F558080DBDA763F213D61EC470A46C967D22BE967C9732F58 。通常終了を確認してPrismの通常・検証profileへ反映した。

## 最終版の実ゲーム追試結果

- FEBE730A版で公開MCPのみを使い、左手前の同じチェストを2回連続inspectした。再arm・転送・移動・破壊・設置は行っていない。
- 1回目 82bc705f-0b7e-4fc8-a3dd-484f95819931: succeeded、15 ticks、camera 51.5304度、interaction1。
- 2回目 3e7f04f5-0eee-4c5b-ae27-15f2ef826065: succeeded、7 ticks、camera0度、interaction1。
- 両方ともdistance0、blocks_broken0、blocks_placed0、effects0。読み取った内容はbirch_log17、dark_oak_log38、oak_log267、oak_sapling3で一致した。
- 各Action後にcontrol.mode=ready、ready_expires_at=null、全8 capabilitiesを確認。最終state tick1866/rev18842、位置(161.06573533742582,64,-298.7557547080176)、health20で、ゲームは接続したままON待機に戻した。
- approachの移動自体は利用者の検証範囲外のため実行せず、同じ表面認可の回帰テストで確認している。
