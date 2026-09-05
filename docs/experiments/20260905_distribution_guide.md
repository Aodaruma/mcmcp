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
- ZIPはproduction JAR、README.md、README.pdf、13個のPNGのみを含む。画像リンク・CRC・同梱JARの一致を検査する。
- production JAR SHA-256: `C9B1B5F966BBBB512AB0711A3E81F6EA40DEED95D379F358D2214E753BF7FDDB`
- ライセンスは別文書で提案し、All Rights Reservedの設定は変更していない。
- AI生成のロゴはレビュー用の試作に限定し、ソース・MOD・ガイド・ZIPへ組み込んでいない。
- 今回は文書・画像の変更であり、ゲームプレイのMCP-only受け入れ試験を実施したという扱いにはしない。修正コードのテスト結果は前の修正コミット481c7b9の記録を参照する。
