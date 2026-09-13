# Windows の実プロファイルへ製品 JAR を導入する

Windows PowerShell 5.1 / PowerShell 7 用。利用者が指定したプロファイルだけを対象にし、Minecraft を通常終了してから、導入完了まで再起動しないでください。追加バックアップは作りません。

MSIX 版アプリから起動したシェルでは、同じ `AppData\Roaming` パスがアプリ専用の `LocalCache\Roaming` へ転送される場合があります。子プロセスの package identity が空でも転送は残ります。そのシェルでのコピーとハッシュ一致だけでは、Minecraft が使うファイルへの導入を証明できません。通常の Windows PowerShell / PowerShell を別途起動して実行してください。

```powershell
$params = @{
    SourceJar = 'C:\src\mcmcp\build\libs\mcmcp-neoforge-26.2-0.1.0-SNAPSHOT.jar'
    ModsDirectory = 'C:\games\PrismLauncher\instances\PROFILE\minecraft\mods'
    ExpectedSourceSha256 = '検証済み成果物のSHA-256（64桁）'
    ResultPath = 'C:\src\mcmcp\preflight.json'
}
& .\tools\install\Install-McmcpProductJar.ps1 @params
# preflight.json の source / destination / native_destination / hash を確認する。
$params.ResultPath = 'C:\src\mcmcp\installation.json'
& .\tools\install\Install-McmcpProductJar.ps1 @params -Install
```

全パスはドライブ名を含むローカル絶対パスに限定します。結果は mods 外の新しい JSON ファイルへ出力し、既存ファイルは上書きしません。JAR 内の製品クラスで対象を識別し、hidden を含め製品が 1 本の場合だけ、検証した一時ファイルを既存 JAR と原子的に置換します。転送先やリンクを使うパスは拒否します。

導入前後のハッシュと、ファイルハンドルが指す物理パスを検証します。Gradle daemon 以外の Java が動いていれば導入を拒否しますが、最終検査後の並行起動までは防げません。コマンドライン引数にはゲームの認証情報が含まれ得るため、診断結果へ出力しません。

導入成功後に Minecraft を起動し、実際の `tools/list` の仕様と利用者の手動入力を確認します。JAR の配置成功は実機動作の合格を意味しません。

回帰試験（実ゲームは起動せず、一時 ZIP とプロセス検査のスタブを使用）:

```powershell
& .\tools\install\Test-McmcpProductJarInstall.ps1
```
