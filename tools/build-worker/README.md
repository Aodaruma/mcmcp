# 大型施工台帳の読書き

`BuildProgressJson.ps1` は、施工workerが変更しない `floor_receipts` / `torch_receipts` 配列を `JsonElement` として保持し、進捗・所有権・pendingなどの小さなメタデータだけをPowerShellへ展開します。通常ゲームやMCPへ接続する機能はありません。UTF-8の施工台帳が対象です。

- `Read-BuildProgressJson -Path <absolute path>`：共有読取り・削除許可でスナップショットを取得し、ファイルを閉じてからJSONを解析します。確定記録配列の件数は `.GetArrayLength()` で取得します。
- `Get-BuildProgressLastFloor -Progress $progress`：最後の確定床1件を通常のPowerShellオブジェクトとして返します。空配列は拒否します。
- `Write-BuildProgressJsonTemp -Progress $progress -Path <absolute temporary path>`：全記録を維持したUTF-8 JSONを一時ファイルへ書きます。元台帳への原子的な置換、排他lock、所有権、停止条件は呼出側で維持します。

配列はworkerから追記・変更しません。記帳を担当する既存Python処理が正式な台帳へ追記した後、workerが読み直します。台帳形式・各記録・Action ID・設計hashは維持します。メタデータの日時とPSCustomObjectは従来の `ConvertTo-Json` に処理させるため、直接.NET serializerで置換しません。

導入済みworkerをこのスクリプトだけで差し替えないでください。受け側の配列件数・末尾取得の変更が必要です。候補は[検証記録](../../docs/experiments/20261014_v2_torch_worker_speed.md)を参照し、通常施工の所有者がworker終了・実行中Actionなしの区切りで適用します。

```powershell
python -m unittest discover -s tools/build-worker -p 'test_*.py' -v
```

JSONの全内容保持、エラー時に正式台帳を変更しないこと、旧workerとのDryRunの一致をオフラインで検証しています。導入後の通常施工でも384床の試行と後続4,608床＋33松明が成功しました。松明後の区間境界は保存記録の推定中央値で約30.2秒→約7.0秒へ短縮していますが、施工全体の改善率とは区別してください。測定条件は上記の検証記録を参照してください。
