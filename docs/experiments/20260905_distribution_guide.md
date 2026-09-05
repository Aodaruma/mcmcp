# 配布ガイド r3の作成記録

2026-09-05。ユーザーの依頼により修正版JARをPrism Launcherの元プロフィールとMCMCP-Validationに反映し、旧版をバックアップした。

## ガイド画像とPDF

- PrismのMOD一覧、MinecraftのEscメニュー、MCP接続設定、ON待機、マルチプレイ許可画面をcomputer-useで実際に撮影した。
- 元の画面に枠・番号・日本語説明を別レイヤーとして追加した。認証情報や私用サーバーの接続先は配布画像へ含めていない。
- 状態表の7アイコンはAutomationIndicatorControllerの描画定義と同梱テクスチャに対応する。配布画像では読みやすい不透明の暗い背景に載せた。
- CurseForgeはインストールせず、公式ブログのOpen Folder画面を抜粋し、注釈と出典を加えた。画面内の別バージョン・別MODパックをそのまま選ばないよう本文で案内した。
- CurseForge出典：https://blog.curseforge.com/how-to-enable-mods-for-a-specific-world-in-minecraft-java/ （2026-09-05参照）。第三者画像をMCMCPのライセンスで再許諾しない。
- README.pdfはTyporaのGUIからGithubテーマで出力。メタデータのCreatorはTypora。6ページをPopplerで画像化し、全文・画像・状態表・改ページを目視確認した。出力後に元のダークテーマへ戻した。

## マルチプレイ撮影と復旧

公式Minecraft 26.2のローカルサーバーを `.codex-temp/readme-multiplayer-server` に作成した。loopbackの127.0.0.1:25575に限定し、online-modeを有効にして接続した。元のMODパックのサーバー必須MODは撮影時だけ退避し、撮影後は退避前の24ファイルをハッシュ照合して復旧した。許可画面ではキャンセルし、許可先を保存していない。

サーバーはstopで正常終了済み。フォルダー内の「削除方法.md」に後片付け方法を記載した。サーバー・ワールド・ログ・開発用harnessは配布ZIPに含めていない。復旧済みMODの一時退避フォルダーの削除は自動承認審査により拒否されたため、バックアップとして残した。

Prismのnative Roaming側とCodexパッケージのLocalCache側の両方について、元プロフィール・検証プロフィールのproduction JARがbuild成果物と一致することを確認した。

## 配布物と範囲

- `docs/MCMCP_配布用README.md`、同名PDF、`docs/assets/readme/` がガイドの正本。
- ZIPはproduction JAR、README.md、README.pdf、14個のPNG（画面・状態アイコン13個と採用ロゴ1個）のみを含む。画像リンク・CRC・同梱JARの一致を検査する。
- production JAR SHA-256: `C9B1B5F966BBBB512AB0711A3E81F6EA40DEED95D379F358D2214E753BF7FDDB`
- ライセンスは別文書で提案し、All Rights Reservedの設定は変更していない。
- ロゴは当初レビュー用の試作に限定していた。その後、ユーザーがPhotoshopで調整・合成した版を承認し、README・ガイド・ZIPへ採用した。MOD本体への組み込みは行っていない。
- 今回は文書・画像の変更であり、ゲームプレイのMCP-only受け入れ試験を実施したという扱いにはしない。修正コードのテスト結果は前の修正コミット481c7b9の記録を参照する。

## PDFレビューの反映

同日、PDFの15件の注釈と文書全体の見直し依頼を反映した。

- 画像内のタイトル・右側説明欄・左下補足文を全6画像から除去し、実画面とボタン枠・対応番号だけに統一。対応するボタンの役割は画像直下の本文へ移した。
- 表紙の対応環境は箇条書きに変更し、PDFページ付き目次と導入前の改ページを追加。章・節番号を1.1、1.2、2.1～2.3、3.1、4、5、5.1へ整理した。
- 初期状態を「デフォルトでOFF」と明記し、MCP接続設定から設定選択画面への遷移と自動設定操作を別々に説明した。
- CurseForgeの注意書きはNote!の引用枠とし、配布ガイドからchangelogを削除した。
- Typoraから再出力した最終版は8ページ。目次のページ対応、状態表が分割されないこと、文字・画像・番号の表示を全ページ確認した。Markdown・PDF・ZIPを同期し、production JARは変更していない。

## 採用ロゴとGitHub形式のNote

- ユーザーが調整したPhotoshop文書から、配置と色を維持した1600×1600の透過PNGをPhotoshopの「書き出し形式」で出力した。正本は `docs/assets/readme/mcmcp-logo.png`。元のレイヤー付きPSDは配布ZIPに含めない。
- リポジトリREADME、導入・接続ガイド、配布ガイドへ同じロゴを掲載し、リポジトリREADMEから画像付きガイドとPDFへリンクした。
- Typoraの「Github スタイルアラート」が有効であることを確認し、注意書きを `> [!NOTE]` に変更した。GithubテーマでGUIからPDFを再出力し、青いNote枠と表紙ロゴの表示を確認した。
- PDFは8ページを維持。変更した1・2ページを目視確認し、3〜8ページはPoppler出力のハッシュが前回の確認済み版と一致した。目次のページ番号も変更不要だった。
- PDF出力後、元のダークテーマへ戻す前にユーザーのEscでcomputer-useが停止した。以降は依頼に従いGUI操作をせず、成果物の同期・ZIP更新を行った。
