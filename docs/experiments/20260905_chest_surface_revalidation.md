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
