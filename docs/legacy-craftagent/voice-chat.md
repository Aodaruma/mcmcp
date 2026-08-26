# Simple Voice Chat連携

## 目的

自動化中の意図しない送話を防ぎ、終了後はユーザーが自動化前に選んでいたミュート状態へ戻します。既定キー`M`の疑似入力は使いません。キーは変更可能で、競合や二重toggleが起きるためです。

## 2.6.22で確認したAPI

実際の`voicechat-neoforge-2.6.22+26.2.jar`と同じ26.2ブランチの公式ソースから、次を確認しています。

- 公開`VoicechatClientApi.isMuted()`で状態を読める。
- 公開`ConfigAccessor`は読み取り専用で、公開APIには`setMuted`がない。
- 内部`ClientManager.getPlayerStateManager().setMuted(boolean)`は、設定保存と`MicrophoneMuteEvent`発火を行う。
- `KeyEvents.KEY_MUTE`も同じ`setMuted`を呼ぶ。

したがって、内部API利用を`voicechat-2.6.22` adapterへ隔離します。クラス・メソッド・MODバージョンの検査に失敗した場合は、ミュートせず続行せず、能動自動化を禁止します。

このfail-closedはSimple Voice Chatが導入済みの対象packに適用します。MOD自体が未導入で音声経路が存在しない環境では、local policyが許可すればVoice Chat条件なしで動作できます。導入済みだが接続・状態・adapter互換性が不明な場合は開始しません。

## 状態所有モデル

routine開始時:

1. Voice Chat接続とadapter互換性を確認する。
2. `previousMuted`を読む。
3. 既にミュートなら所有権を取らず、そのまま開始する。
4. 未ミュートならクライアントスレッドでミュートし、再読して成功を確認する。
5. 本MODが変更した場合だけ`ownsMute=true`にする。

routine終了時:

1. 先に全ゲーム入力を解放する。
2. `ownsMute=true`かつ、ユーザーの手動変更を検出していない場合だけ元へ戻す。
3. 復元結果を再読し、失敗は画面通知と監査ログへ残す。

完了後に通常disconnectするpolicyでも、disconnect処理より前に入力解放とミュート復元を行います。自動再接続時の再ミュート機構は作りません。

## ユーザー操作との競合

本MOD自身の変更中だけイベントを内部変更として印付けします。それ以外の`MicrophoneMuteEvent`はユーザーまたは他MODの変更とみなし、次の動作にします。

- 自動化中に未ミュートになった場合: 即時停止し、入力を解放する。ユーザーの状態を再変更しない。
- 自動化中にユーザーがミュートした場合: 自動化終了時に勝手に解除しない。
- Voice Chatが切断・再初期化された場合: routineを停止し、再検査までlockする。

## 設定ファイル

`voicechat-client.properties`の`muted`を外部プロセスから直接書き換えません。ゲーム内オブジェクトとイベントを経由しない更新は、実行中状態との不整合やユーザー設定の破損を起こし得るためです。
