# Docker Desktopの一時ソケットによる起動失敗（2026-10-11）

R13の隔離検証を始める前、Docker Desktopが起動できなくなった。ゲームのworld・設定・JARは未変更で、直前に復元した66ファイルとのhash一致を確認した。SSHのCloudflare認証失効とは別の問題。

## 観測した原因と対応

- backendはInference managerの初期化で停止。`%LOCALAPPDATA%/Docker/run/dockerInference` を除去できず、Windows error 1920となった。0byteの再解析ポイントで、個別の所有者読取りも失敗した。
- Docker CLIの接続待ちも停止したため、各probeを有限timeoutにした。通常のDesktop終了が失敗した際は、このrunで起動したPID・実行ファイル・開始時刻に一致するプロセスだけを終了した。leaseは解放し、残存プロセスなしを確認した。
- 親の `Docker/run` が通常のディレクトリで、一時IPCのみであることを確認して、同じ親内の日時付き名へ退避した。元の内容は削除せず保管。Dockerは新しいrunを作成し、Inference managerのエラーは解消した。
- 続いて `%LOCALAPPDATA%/docker-secrets-engine/engine.sock` で同じ問題が出た。この親も0byteの一時ソケット1個のみで所有者を確認できたため、停止後の可逆的な退避・再生成を行った。その後engineが起動し、既存の隔離環境でR13のボート・馬・look・足場での作業を検証できた。

この対応では、個々の壊れた再解析ポイントをたどったり、ACLを緩めたりしない。Dockerの設定、コンテナ・image・volume、データVHDX、他のWSL distroには変更を加えない。Factory Reset・prune・Windows再起動も行わない。実際の退避先・日時・プロセス確認は非公開のrun receiptへ保存する。

[Docker公式repoの同種報告 #625](https://github.com/docker/desktop-feedback/issues/625)にも、個別socketへのアクセスが失敗し、一時ディレクトリの退避で起動できた事例がある。ただし再発例もあり、恒久的な修正とは扱わない。

## 同じ症状が再発した場合

1. エラーの対象が同じ一時IPCであることと、Desktop／engineの状態を確認する。CLIが停止する場合は個々の呼出しを有限にする。
2. 稼働中のcontainerや他の利用者がいないことを確認する。所有の不明なプロセスを一括終了しない。
3. 対象の絶対path、親の通常directory属性・所有者、直下の内容を確認する。永続データや種類不明のファイルがあれば、この手順を適用しない。
4. 停止した状態で、確認済みの一時directoryだけを同じ親内へ可逆退避する。退避先の重複を拒否し、元の内容を削除しない。再起動失敗で作られたrunにも同じ問題が出る場合は、改めて同じ確認を行う。
5. engineの起動、既存container／volumeの存在と状態を確認する。実機検証は新baselineと期限付きleaseを取得してから行い、終了後はworld・設定・JARをhash一致で復元する。

今回は実機検証後、08:28 JSTまでに元66ファイルをhash一致で復元し、container・Docker Desktop停止とlease解放を完了した。退避した一時IPCは保管している。詳細は[移動と結果返却の検証記録](20261011_v2_movement_results.md)を参照。

同日の後続[足場迂回の対照試験](20261011_v2_scaffold_detour.md)でも同種の起動エラーが再発した。今回起動したプロセスの停止と、一時IPCだけの可逆退避で再び起動できた。再発防止を確認したものではなく、Dockerの更新・設定変更はこの検証では行っていない。
