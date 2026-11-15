# 共通収納の追加受入（2026-11-15、#70 / PR #82）

状態: **同種 2 個、胸装備、offhand の代表経路が合格**。対象候補の製品コード変更はない。#70 全体の受入完了や、未試験構成への対応を意味しない。

## 対象と手順

- source `99d5adab5ce4a23af97d7591771f88c3b17c4f92`、製品 JAR SHA-256 `4E575347392ECB2C9A04DA9E189C7902610D98CEEA3FC24DFBBE1FD5954841E6`。
- dev harness JAR SHA-256 `355DB948DC2686E49683D4E68A0768C56AB984874DDA28888F9FA65A20B07CEB`。
- 隔離 Docker clone、Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25。Sophisticated Backpacks 3.25.90 / Core 1.4.99 は[既存記録](20260926_shared_storage_candidate.md)の SHA-256 と再照合した。
- 事前に world・MOD・設定の 66 ファイルを保全し、排他的な 45 分 lease を使用した。live の公開 Tool 5 本の完全な定義を source catalog と照合した。
- 最初の T0 前に固定の有限コマンドで空の通常バッグ 2 個と丸石 32 個を用意し、それぞれ通常の右クリックで初期化・開封した。T0 後の移送と再開封は公開 MCP だけで実行した。
- 2 段階目の T0 前は MCP OFF とし、初期化済みの同じ個体を胸と offhand へ固定コマンドで移した。個体の UUID / component と内容を保持し、移動先へのコピー直後に元 slot を空にした。数量移送の成功中に手動操作や管理コマンドを使っていない。

## 実測

所持品の同種バッグ 2 個が、別々の `storage_id` と fresh な単回 `operation_ref` を返した。毎回の操作後に再開封した `storage_inspection` が、操作に使った個体と consumed reference に結び付くことを確認した。

| 操作 | 所持丸石 | バッグ A | バッグ B |
| --- | ---: | ---: | ---: |
| 初回 inspect | 32 | 0 | 0 |
| A に 4 個格納 | 28 | 4 | 0 |
| B に 7 個格納 | 21 | 4 | 7 |
| A を再 inspect、2 個取出し | 23 | 2 | 7 |
| B から 3 個取出し、両方を再 inspect | 26 | 2 | 4 |

この段階は **9 Action / 102 check / 37 request が全件合格**。一方の変化で他方の識別や内容が入れ替わらないことを確認した。

装備へ移した後も同じ 2 個の `storage_id` を維持し、location は `chest` と `off_hand` に変わった。再 inspect の内容は A=2 / B=4。各装備先について 1 個格納 → 1 個取出しを実行し、各回の fresh readback と所持数の保存を確認した。この段階は **6 Action / 74 check / 27 request が全件合格**。

合計 **15 Action / 176 check / 64 request が合格**。各 Action は `succeeded`、stationary、HP20 / READY を維持し、閉じたバッグの内容は開封前に推測していない。今回は既存の同一 hash の候補を再使用し、ローカル Java 試験を新たに実行した結果とは区別する。

## 終了と残る範囲

全 Action の終端後、通常のゲーム UI で保存してタイトルへ戻り、公開 state の world 不在と実行中 Action 不在を確認した。container 停止後に **66 ファイル / world 61 ファイルを全件 SHA-256 で復旧照合、不一致 0**。lease を解放し、試験前から稼働していた Docker Desktop は維持した。通常 profile は変更していない。

拡張 stack、upgrade / 保護 slot、容量不足、取消、同期遅延・欠落、他 version、MOD なし起動、world に置いた収納、入れ子 / linked / 追加装備 API は今回の実機試験に含まない。単体・契約試験の確認と実機未試験を分け、#70 の未対応範囲は残す。
