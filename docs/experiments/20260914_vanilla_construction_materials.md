# Vanilla建材拡張の機能確認

Issue #36。対象はMinecraft 26.2 / NeoForge 26.2.0.59、Java 25。
利用者の依頼に基づき、通常のVanilla建材familyと床置きtorchを建築対象へ拡大した。
MOD追加ブロックは対象外。全Vanillaブロックを無条件で配置可能にする変更ではない。

## 変更とソース検証

通常のfull-cube建材、乾いた階段・非double slab・paneを厳密な実装classで判定する。
観測と設置は共通policyを使い、完全state、通常item、支持面、予測state、server同期を維持する。
階段shapeとpane接続のplan内依存検証も材料familyに合わせた。
落下・液体・危険物・収納・可変挙動の未検証classを継承やnamespaceだけで許可しない。

`tools/check-source.ps1` は成功。Java全体、harness、admin bridge、release isolation、build、
Python通信14件、既存capability gateの模擬試験を含む。
追加した接続依存のparameterized試験では、oak/birch/stone-brick stairsと
glass/black-stained-glass/iron-barsの未完了隣接entryによる閉包を確認した。

独立したCodex CLIによる静的レビューを実施した（subagentは使用していない）。
初回のwaterlogged pane懸念は、diff外の既存共通拒否条件を完全なclassとテストで
再確認して取り下げられた。提示した差分・関連classの範囲で重大な追加指摘なし。
これは実機試験やMinecraft全blockの網羅検証の代用ではない。

## リモートの軽い実機試験

指定されたSSH接続先の使い捨て検証profileを利用。元のsave、instance設定、旧JARを退避した。
Windows共有上のPOSIX権限制約に対応し、管理用認証設定はLinuxの専用volumeへ分離した。
本体の認証・公開Tool・安全条件は変更していない。

製品JAR SHA-256: `8404b4d8e3d06167734c9a060aa83c537e63508ebf1bc9ae72d26a67e99fd3f3`

管理fixture `construction-materials` はT0前にのみ適用した。
fixture SHA-256: `3823e247118ab7f59daefc7fdec13c5710f7f8a378b880da3d2de19253af7b97`。
14 commands、宣言変更数973、上限1000。
MinecraftのUIでMCPをONにし、以後terminalまでは公開5 Toolだけを既存通信層で転送した。
これは決定的なcapability gateであり、freshモデルによる自律受入試験ではない。

| 材料 | 設置後の位置 | 在庫 before → after | 結果 |
| --- | --- | --- | --- |
| snow_block | (-6,56,3) | 64 → 63 | PASS |
| black_wool | (-5,56,3) | 64 → 63 | PASS |
| red_concrete | (-4,56,3) | 64 → 63 | PASS |
| torch（床置き） | (-6,57,3) | 16 → 15 | PASS |

4材料ともチェストからのtake、可視配置元の取得、支持面への照準、通常設置、
設置後の再観測と在庫消費を確認した。全Actionがterminal、controlはREADY、cancel不要。
公開stateは入力ownerを直接列挙しないため、ownerの直接観測を行ったとは記録しない。
terminal後にゲームを保存してタイトルへ戻した。退避saveは後続フェーズの検証と復旧用に保持する。

## 未確認範囲

128×128の完成、海上の端移動、補充と再開、QR読取り、地図上のtorch画素影響、
全Vanilla特殊ブロックの個別実機検証はこの試験の合格範囲に含めない。
端設置はIssue #37、全体進捗の保存・再開はIssue #38で継続する。
