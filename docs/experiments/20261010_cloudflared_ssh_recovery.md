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

## 2026-10-11夜の更新後確認

ブラウザー認証完了の連絡後も、`ssh -o BatchMode=yes -o ConnectTimeout=12 aod-mimoid "hostname"` は約13秒・exit 1で `Connection timed out during banner exchange`、`Connection to UNKNOWN port 65535 timed out`。利用者が復旧を進める間、再試行を止めた。

利用者のcloudflared更新後、23:34 JSTに開発PCで `2026.10.0 (built 2026-10-05T08:39 UTC)` を確認。ConnectTimeout=20の単独SSHは約3秒・exit 0で実hostname `aod` を取得した。続くSSHの読取りでsshd／CloudflaredのRunningと隔離labの存在も確認した。この再確認では開発担当側のlock退避・サービス再起動・再認証を行っていない。

Codexの遠隔projectは一覧に出ていないため、SSH復旧とCodex遠隔接続を区別する。今回の成功だけで残存lockや特定バージョンの不具合が原因だったとは断定しない。認証期間延長の設定変更を確認した記録でもない。

## 2026-10-12 04:15の新規認証待ち

cloudflared 2026.10.0でSCP開始時にブラウザー認証待ちが発生した。既存の03:44開始lease SSHは存続し、新規接続だけが待機。アプリ用／組織用lockは更新時刻04:15、各81 bytesで、古い0byte lockの退避手順は適用しなかった。token本文と認証URLは本記録へ保存しない。

後続にGUI操作を含む保留SCPのPowerShellとそのSSH／認証の子processだけを終了し、既存leaseと常駐Cloudflaredは保持した。単独 `access login --quiet` の起動はローカルツールの自動承認レビューに `blocked by policy` で拒否された。詳細理由は返されず、別経路で同じ認証起動を強行していない。利用者へ再認証を依頼した。

Codex遠隔のチャット読取り・メッセージ送信は利用できたため、aod側「Cloudflare TunnelのSSHタイムアウト調査」へ元環境復元とDocker停止を依頼。対象・mount・lease所有者・backup66ファイルのhash一致は遠隔側でも確認できた。ただし遠隔側から元チャットの利用者の許可を参照できず、復元・停止は直接承認待ちになった。04:26時点では未実行であり、利用者へ遠隔チャットの確認への回答を依頼済み。

再発防止として、新しい検証runnerでは有効なbackup取得後のlease終了を、認証中の新規SSHに依存しないremote側の復元・停止処理へ接続する必要がある。現行leaseは期限でlockを解放するだけでDocker停止・world復元を自動実行しない。認証期間延長も未実施。

04:29:02の期限到達後、既存lease SSHはexit 1、`Lease deadline; restore explicitly before reuse` で終了した。期限は延長していない。**Docker停止・world復元は未完了**で、接続調査チャットへ期限終了を追記通知した。leaseの終了を環境復元の成功と混同しない。

04:38、利用者の「認証しました」の連絡後、単独SSHがexit 0でhostname `aod` を返した。新たな認証設定変更は行っていない。Docker process 0・lease lockなし、復元記録なしだったため、復元専用の所有lockを取得してDockerを起動し、前runの復元helperを実行。04:40:00に元66/world61ファイルのhash一致、04:40:53にDocker process 0、続いてWSL停止・lock解放を確認した。証拠zipは162ファイル、SHA-256 `2004d6a73bbd2869b9956dccddb70dc3bd67fe9cafbc7d6588df161e671cdb22`。遠隔側には重複操作防止のため復元依頼の取り下げを通知した。

次の隔離run用leaseには、期限到達または`finish-request`で既存の復元・停止helperを遠隔側の`finally`から実行する処理を追加した。新規SSH認証に依存して期限時の後始末が止まらないようにする。元の45分期限は延長せず、前runの完全復元後に別runとして開始する。
- 2026-10-12 05:25 JST: 認証後に再開した通し試験runも、遠隔leaseのfinish-request経由で元66/world61ファイル復元・Docker停止・lock解放まで完了した。期限時も同じ遠隔finallyを実行するため、SSH再認証が切れても後始末を現地で継続できる。
