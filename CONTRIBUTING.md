# 貢献ガイド / Contributing

不具合報告、文書の修正、小さな機能改善を歓迎します。作業前に [AGENTS.md](AGENTS.md) の安全契約を読んでください。このガイドはその契約を緩めません。  
Bug reports, documentation fixes, and small improvements are welcome. Read the safety contract in [AGENTS.md](AGENTS.md) first; this guide does not relax it.

## 1. Issueで担当を決める / Claim an issue

1. [Issues](https://github.com/Aodaruma/mcmcp/issues)で同じ報告や着手中の作業を探します。なければ、期待する動作・実際の動作・再現手順を書いてIssueを作ります。  
   Check for an existing report or active work. Otherwise, open an issue with expected behavior, actual behavior, and reproduction steps.
2. 着手前に「対応します」とコメントし、担当を調整します。担当変更権限がなければmaintainerに割り当てを依頼してください。  
   Comment before starting and coordinate ownership. Ask a maintainer to assign the issue if you cannot assign yourself.
3. **1 Issueにつき1作業branch**を使います。別の人・AIと同じ作業フォルダーを同時編集せず、cloneまたはGit worktreeを分けてください。共有`main`での直接編集は禁止です。  
   Use **one working branch per issue**. Give each contributor or AI a separate clone or Git worktree. Never edit a shared `main` checkout directly.

大きな変更は先にIssueで範囲を相談し、独立して検証できるIssueに分割します。ラベルの意味は[保守手順](docs/MAINTENANCE.md#ラベルとprojects--labels-and-projects)を参照してください。  
Discuss larger changes first and split them into independently verifiable issues. See the [maintenance guide](docs/MAINTENANCE.md#ラベルとprojects--labels-and-projects) for labels.

## 2. Forkしてbranchを作る / Fork and create a branch

GitとJava 25を用意し、GitHubでこのrepoを自分のアカウントへForkします。以下の`YOUR_HANDLE`、`123`、説明部分は自分のアカウント・Issue番号・作業内容へ置き換えてください。  
Install Git and Java 25, then fork this repository on GitHub. Replace `YOUR_HANDLE`, `123`, and the description with your account, issue number, and task.

```sh
git clone https://github.com/YOUR_HANDLE/mcmcp.git
cd mcmcp
git remote add upstream https://github.com/Aodaruma/mcmcp.git
git fetch upstream
git switch -c issue/123-short-description upstream/main
```

`origin`は自分のFork、`upstream`は本体です。次のIssueも最新の`upstream/main`から新しいbranchを作ります。  
`origin` is your fork; `upstream` is the main repository. Start each new issue branch from the latest `upstream/main`.

## 3. 変更して検証する / Make and verify the change

対象は **Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25** です。`JAVA_HOME`をJava 25へ設定し、コード変更では次を実行します。  
The target is **Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25**. Set `JAVA_HOME` to Java 25 and run these checks for code changes:

```powershell
.\gradlew.bat test harnessTest adminBridgeTest verifyHarnessIsolation build
```

Linux/macOSでは`bash gradlew`を使います。文書だけの変更はリンク・表記・表示を確認し、配布PDFや生成処理を変えた場合は[配布ツールの手順](tools/release/README.md)で生成と見た目を確認してください。PRではCIの`build`成功が必要です。  
Use `bash gradlew` on Linux/macOS. For documentation-only changes, check links, wording, and rendering. If you change the distribution PDF or its generator, follow the [release tooling guide](tools/release/README.md) and inspect the output. Every PR must pass the CI `build` check.

- 不具合修正には、同じ不具合を検出できる回帰試験を付けます。公開Tool/DSLを変える場合はcatalog・runtime・schema test・固定hashを同期します。  
  Add a regression test that detects the bug. Keep the catalog, runtime, schema tests, and fixed hashes consistent when changing public Tools or the DSL.
- 実機試験は[AGENTS.md](AGENTS.md)と[評価protocol](docs/experiments/MCMCP_fresh_MCP-only_評価protocol.md)に従います。通常環境や他人のワールドを勝手に変更せず、結果・未検証事項・復旧を記録します。  
  Follow AGENTS.md and the evaluation protocol for game tests. Do not modify normal installations or someone else's world without authorization. Record results, unverified behavior, and restoration.
- トークン、認証設定、個人情報、非公開のサーバー情報をcommit・Issue・PR・ログ・画像へ載せないでください。公開前に伏せ字へ置き換えます。  
  Redact tokens, authentication settings, personal data, and private server information from commits, issues, PRs, logs, and images.

## 4. PRを出す / Open a pull request

変更ファイルだけをstageし、commitして自分のForkへpushします。`path/to/changed-file`は実際の変更ファイルへ置き換えてください。  
Stage only your changed files, commit, and push to your fork. Replace `path/to/changed-file` with an actual changed file.

```sh
git add -- path/to/changed-file
git commit -m "Fix the behavior described in issue 123"
git push -u origin issue/123-short-description
```

[本体repo](https://github.com/Aodaruma/mcmcp)の`main`宛てにPRを作り、本文に次を記載します。  
Open a PR targeting `main` in the upstream repository and include:

- `Closes #123`（対応するIssue番号 / the issue being resolved）
- 問題と変更後の動作 / The problem and resulting behavior.
- 実行した検証・結果・未検証事項 / Checks run, results, and remaining verification.
- 安全契約・公開仕様・配布物への影響 / Impact on safety, public contracts, or distribution files.

`main`が進んだら、作業branchで`git fetch upstream`と`git merge upstream/main`を実行し、競合を解決して再検証・pushします。他の人のbranchを書き換えないでください。  
If `main` advances, run `git fetch upstream` and `git merge upstream/main` on your working branch, resolve conflicts, rerun checks, and push. Do not rewrite another contributor's branch.

maintainerが差分と対象SHAをレビューし、CI `build`成功・最新`main`との整合・会話の解決を確認して**squash merge**します。現在の必須approval数は0ですが、レビュー記録は必須です。詳細は[保守手順](docs/MAINTENANCE.md)を参照してください。  
A maintainer reviews the diff and exact SHA, checks the successful CI `build`, an up-to-date branch, and resolved conversations, then **squash merges**. The current required approval count is zero, but a recorded review is mandatory. See the maintenance guide for details.

## ライセンス / License

貢献コードはrepoと同じ[MPL-2.0](LICENSE)で扱います。別途CLAの提出は求めません。第三者素材を追加する場合はライセンスと出典を明記し、[NOTICE.md](NOTICE.md)等の必要な表示を更新してください。  
Contributions use the repository's [MPL-2.0 license](LICENSE); no separate CLA is required. Document the license and source of third-party material and update required notices such as [NOTICE.md](NOTICE.md).
