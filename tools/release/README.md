# 配布とPDFの自動生成

Python 3.11以上、Java 25、Gradle Wrapperを使います。Typoraやデスクトップ操作は不要です。

## 初回準備

```sh
python -m venv .venv-release
# Windows: .venv-release\Scripts\activate
# Linux/macOS: source .venv-release/bin/activate
python -m pip install -r tools/release/requirements.txt
python -m playwright install chromium
```

LinuxのCIでは`python -m playwright install --with-deps chromium`を使います。Chromiumと初回の日本語フォント取得にはネットワークが必要です。PDF生成時は外部HTTP通信を遮断し、ローカル画像とフォントだけを読み込みます。

## 手元で作る

```sh
# PDFのみ（gradle.propertiesの開発版番号を使用）
python tools/release/build_release.py --docs-only

# JARと配布ZIP
./gradlew build
python tools/release/build_release.py
```

Windowsでは`./gradlew`を`.\gradlew.bat`に置き換えてください。出力先は`build/release/`です。ZIPには本体JAR、README.md/PDF、画像、Action DSLガイド、LICENSE、NOTICE、フォントライセンス、SOURCE.txt、SHA256SUMS.txtを含めます。ゲーム設定・トークン・ワールド・harness/admin JARは含めません。

PDFの本文は`docs/MCMCP_配布用README.md`、画像は`docs/assets/readme/`、印刷スタイルは`tools/release/guide.css`が正本です。TyporaのCSSを流用せず、似た余白・見出し・表・Noteを独自CSSで整えています。通常の引用とGitHub形式`> [!NOTE]`等を表示でき、日本語の太字、明示改ページ、ページ番号を扱います。目次のページ番号はPDFの実際の見出し位置から再計算します。

本文・JAR名のバージョンは出力時に置換します。元のMarkdownやgradle.propertiesをタグごとに自動commitする方式ではありません。Gitのタグと成果物のバージョンが一致し、元のタグのソースを再現できます。

Noto Sans JPはGoogle Fontsの固定commitから取得し、SHA-256を照合して`build/release-fonts/`にcacheします。フォントのライセンスはOFL-NotoSansJP.txtです。Python依存とChromiumの版もrequirements.txtで固定しています。

## タグから公開する

例えば次のタグをpushすると、CIが`mod_version=0.1.0-rc3`でbuildします。

```sh
git tag v0.1.0-rc3
git push origin v0.1.0-rc3
```

全テスト・harness分離検証・PDF/ZIP生成が成功した後、**draftにせず公開**します。接尾辞付きはPre-release（Latestにはしない）、`v0.1.0`等は正式Releaseです。公開するのはタグpush時だけで、main/PR/手動実行は検証とActions artifact保存に留まります。既存Releaseを無条件に上書きする処理はありません。

GitHub Actionsの公開jobだけに`contents: write`を付け、build jobはread権限で実行します。タグ名は環境変数として渡し、形式検証後に引用付きで使います。タグを作成・pushする操作そのものが公開のきっかけになります。

手元でタグ形式の成果物だけ検証する場合：

```sh
./gradlew build '-Pmod_version=0.1.0-rc3'
python tools/release/build_release.py --tag v0.1.0-rc3
```

このローカルコマンドはタグの作成やRelease公開を行いません。タグがまだ存在しない場合、SOURCE.txtのタグURLは公開後に有効になります。

## 検証

```sh
python -m unittest discover -s tools/release -p 'test_*.py' -v
```

パッケージ生成はJARのMOD ID・version・MPL-2.0 metadata・開発用class混入を検査し、不一致なら停止します。画像欠落、空ページ、未処理の太字、主要説明の欠落、不安定な目次ページ数もエラーにします。生成ZIPはCRCとJAR内容の一致を検証します。レイアウト変更時はPDF全ページを画像化して目視確認してください。

実装の参照：[Playwright PDF API](https://playwright.dev/python/docs/api/class-page#page-pdf)、[GitHub CLI release create](https://cli.github.com/manual/gh_release_create)、[MPL 2.0](https://www.mozilla.org/en-US/MPL/2.0/)。
