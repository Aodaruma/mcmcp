# Cloudflare認証後もSSHが初期応答待ちになる場合

2026-10-10 JST。aod-mimoid隔離Docker検証中の接続復旧記録。利用者から、再発時はこの記録を参照して開発担当側で復旧を進めるよう依頼を受けた。正常な既存接続、通常ゲーム、認証情報を保持する。

## 症状と確認結果

- ブラウザーで認証を完了しても、新しいSSHは`Connection timed out during banner exchange`。15秒から30秒に延ばしても変わらなかった。
- Cloudflare入口はHTTP 302を返し、既存のDocker維持用SSH接続は存続していた。
- aod側のtmpチャットで読取り診断し、実hostname `aod`、今回のbaseline 66件、sshd／CloudflaredのRunning、IPv4／IPv6の9022番待受とローカルSSH bannerを確認。22番待受ではない。確認したイベントログに09:00以降の関連エラー・警告はなかった。サービス変更は行っていない。
- このPCのcloudflaredは2025.11.1。アプリ用と組織用の認証待ちを示す0byte `.lock` が終了済み認証後に残っていた。有効なJWTファイルとは別物。
- 認証を試している新規プロセスを終了し、古い空lockだけを退避。その後、`cloudflared access login --quiet <対象AccessアプリURL>` が成功し、単独SSHもexit 0と所定の応答を返した。追加の手動認証依頼は不要だった。
- 復旧後の公開MCPは元sessionでREADY、game_paused=false、running_action_id=null。通常くらふとぶ！は操作せず、隔離Dockerの試験を再開した。

2025.11.1の実装はlockファイルの存在で待機し、空ファイルを作る方式で、所有PIDを記録していない。Windowsで認証プロセスが終了しても待機ファイルが残ると、別の認証も待たされる。今回、退避前後で接続失敗→成功を確認した。[対象版の公式ソース](https://github.com/cloudflare/cloudflared/blob/2025.11.1/token/token.go)。

## 次回の復旧手順

1. 単独のSSHを有限timeoutで確認する。複数のscp／SSHをまとめて試して認証要求を重ねない。認証要求が動いている間に短いSSH timeoutで終了させない。
2. Cloudflare入口の応答、cloudflared版、関連プロセスの開始時刻、対象アプリの認証cache／lockの種類と更新時刻を確認する。tokenの本文、認証URL、サービス引数の秘密情報をログへ出さない。必要ならaodのtmpでサービス状態・9022のローカルbannerを読取り確認する。
3. この作業で起動した認証プロセスだけを終了し、他の認証処理が動いていないことを確認する。既に接続中のSSH／cloudflared、Docker維持用lease、常駐Cloudflaredサービスを終了しない。
4. `%USERPROFILE%/.cloudflared`内で、この接続に一致する `<Accessアプリhostname>-<AUD>-token.lock` と `<チームdomain>-org-token.lock` だけを確認する。2025.11.1で0byte、認証処理が終了済み、対象が一致する場合に限り、同じディレクトリで日時付き `.stale-...` へrenameして退避する。実token、証明書、SSH鍵は変更しない。新しい版のPID付きlockや所有不明のlockを、この手順で一律削除しない。
5. `cloudflared access login --quiet <対象AccessアプリURL>` を1個だけ起動して終了を確認する。`--quiet`はJWTを端末へ表示しないために必要。ブラウザーのSSOで完了できず利用者の認証操作が実際に必要な場合だけ、新しい認証要求が有効な間に案内する。
6. 単独SSHの応答とexit 0を確認し、その後にMCPのsession・READY・jobなしを確認して試験を再開する。実機試験終了後のworld・設定・JAR復元とDocker停止は別途行う。

この手順は古い認証待機情報の解消であり、Access認証の無効化やサービス再起動を行うものではない。退避後も失敗する場合は再認証を繰り返さず、トンネルとSSH初期応答の診断へ戻る。

## 認証期間

復旧時に新しく保存されたアプリtokenの`iat`／`exp`だけを読み取り、期間は6時間（10/10 09:54:46～15:54:46 JST）だった。token本文は記録していない。

利用者は信頼するPCでの認証期間延長を希望している。まず対象SSHアプリのAllow policyまたはapplication sessionを72時間にする案を検討する。**この記録時点ではCloudflare側の設定変更は行っていない。** 他アプリを含むglobal sessionやMFA、対象ユーザーの範囲を一緒に変更しない。

Cloudflareのapplication sessionはpolicyの既定値で、個別policyのdurationが優先する。通常の選択肢は即時timeout～1か月。無期限を前提にせず、設定後に発行されたtokenの期限で反映を確認する。[公式のsession管理](https://developers.cloudflare.com/cloudflare-one/access-controls/access-settings/session-management/)。期間延長だけでは、今回の残存lock問題は解消しない。

## 2026-10-11の再発

06:34 JSTに6時間のアプリ認証が失効した。今回起動した新規SSH/認証だけを終了し、対象の空lock2件を退避して単独quiet loginを試したが、exit 1で再認証は完了しなかった。終了後のlock残存はなく、既存lease接続と常駐サービスを保持した。前回の「lock退避だけで復旧」とは区別する。

利用者の事前許可があるaodのtmpチャットから、hostname・sshd/Cloudflared Running・対象lab・baseline66件・containerとMCP状態を確認し、検証環境の操作を引き継いだ。接続先や認証要件を変更してSSHを迂回したわけではない。Windowsの認証画面を自動操作せず、通常ゲームも操作していない。

期限付きlease中に認証が失効する場合は、再認証だけに依存せず、許可済みの管理経路で元環境復元とDocker停止を確実に行う。今回の復元結果は[移動・結果返却の検証記録](20261011_v2_movement_results.md)へ記載する。認証期間72時間の設定変更は引き続き未実施。
