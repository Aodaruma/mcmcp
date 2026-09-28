# 公開API v2の隔離smoke test

`Invoke-McmcpV2Smoke.ps1`は実装者が実行する決定的な機能試験です。fresh LLMによる自律評価ではありません。mock試験の成功も実ゲーム合格とは扱いません。

## 準備と復旧

1. 使用を許可された隔離Docker、または独立したローカル検証profileで実施します。12:00～23:00 JSTはローカルPCのみ、23:00～12:00 JSTはaod-mimoidも利用できます。稼働中の他担当・通常profileへ割り込みません。
2. 停止中の検証instanceについて、save全体、MOD、instance設定、optionsと変更予定のfixture設定を退避し、相対path／SHA-256／file数を記録します。source commit、candidate JARとharnessのhash、実際の導入先hashを照合します。
3. fixture-adminへ`public-api-v2`と`public-api-v2-water-stop`のmanifestとsetupを導入します。`status → validate → apply`でbase fixtureを適用します。`prepare-ui.mcfunction`はrestricted loaderへ渡さず、隔離ゲームのcommand UIでT0前に一度だけ実行します。cowを重複召喚せず、再試験はsave復旧から始めます。
4. T0前のfixture確認とUIのMCP ON後、`get_mcp_status.world_session_id`を取得します。runnerはこのsessionと範囲を毎回照合し、MCPから開発用commandを実行しません。
5. 試験終了時は全jobと入力の停止を確認し、ゲームを保存終了、containerまたは検証用JVMを停止します。save全体・MOD・設定・追加fixtureを元へ戻し、元のfile数と全hash一致を記録します。水源は周囲へ流れるため、変更cellだけの復旧では不十分です。

ローカルで新規worldを作る場合も、fixture適用前に対象chunkをロードし、適用後の足場・座標・体力・持ち物を確認します。試験前に保存終了してsave全体のbaselineを取り、失敗後の再試験はそのbaselineを復元します。通常profileの認証情報や設定を検証資料へコピーしません。

## 実行

PowerShell 7.4以上と同じcommitの`tools/mcp`／catalogを使います。tokenはファイルpathで指定し、値をログへ出しません。各回に新しいArtifactDirectoryを指定します。

```powershell
pwsh -File tools/eval/Invoke-McmcpV2Smoke.ps1 `
  -TokenPath '/data/prism/instances/MCMCP-Validation/minecraft/config/mcmcp/mcp-token' `
  -ExpectedWorldSession '<get_mcp_statusのsession ID>' `
  -ArtifactDirectory '/data/eval-artifacts/v2-smoke/item-entity' -Phase ItemEntity
```

同じ形で`Core → Cancel → Hazard`を実行します。ItemEntityは開始位置から見えるcowを使います。Coreでは観測、inventory交換・投棄、収納take/store、gateの通常使用とraw右クリック、2cellの設置・破壊、10cellの連続採掘、script内の移動・inventory、入力反復を確認します。Cancelは実行が始まった移動入力を取り消し、その後の位置が静止することを確認します。

Cancelでプレイヤーは開始位置から動くため、Hazardの前に`agent_move({x:207,y:201,z:200})`を一度実行し、成功と指定cellへの到着を確認します。Cancel後の位置がそのままHazardの開始条件を満たすとは限りません。

Hazardはcell `(207,201,200)`内でかがみ入力を開始し、`hazard-ready.json`をatomicに作成します。独立したfixture controllerが、そのファイルの出現とMCPの同じrunning actionを確認してから、別のfixture-admin endpointで`public-api-v2-water-stop`をvalidate／applyします。runnerへadmin tokenを渡しません。30秒以内の`safety_interrupted`、同一action IDのterminal、制御の解放を確認します。入力保持中の空振りtimeoutは合格ではありません。

Hazardのterminal取得後は速やかにゲームを保存終了します。入力を解放しても水流は止まらず、この高所fixtureでは試験後にプレイヤーが押し流されて落下し得ます。2026-09-28の実機試験では停止判定後、終了までの間に落下死亡を確認しました。停止処理の確認と、その後の生存保証は区別します。水だけ消すのではなくsave全体を復元してください。

`result.json`に開始引数・action ID・terminal・readback・失敗を残します。開始応答を失ったmutationは再送せず、既知のaction IDだけを取消に使います。未確認の終了・復旧は未完了として記録してください。

runner単体の失敗経路検査:

```powershell
pwsh -File tools/eval/Test-McmcpV2Smoke.ps1
```

この検査は誤session、応答消失、job失敗、照会失敗を模擬し、勝手な再送や誤った成功判定を検出します。実ゲームの成功証拠には含めません。
