# Remote validation container

`aod-mimoid`上で、Prism Launcher、Minecraft、複製save、認証状態を一つの削除可能な試験領域へ隔離する。元のPrism instanceやsave、ローカルの認証fileは変更・複製しない。

- image: pinned Prism Launcher 11.0.3 AppImage + Eclipse Temurin 25
- display: Xvfb + Openbox + noVNC
- rendering: Mesa llvmpipe（GPU-PVは別途smoke test合格後だけ使う）
- data root: remote hostの`F:\mcmcp-testlab\<run-id>`だけ
- published ports: remote hostの`127.0.0.1`だけ
- Minecraft内のMCP/admin endpoints: 引き続きcontainer loopbackだけ

ローカルDocker CLIからSSH transportを使う。remoteの非対話SSH sessionではWindows Credential Managerを利用できないため、remote側Docker CLIでpull/buildしない。

```powershell
$env:MCMCP_TESTLAB_ROOT = 'F:/mcmcp-testlab/20260829-wheat-v1'
docker --config infra/remote-validation/docker-cli -H ssh://aod-mimoid compose `
  -p mcmcp-wheat-20260829 -f infra/remote-validation/compose.yaml build
docker --config infra/remote-validation/docker-cli -H ssh://aod-mimoid compose `
  -p mcmcp-wheat-20260829 -f infra/remote-validation/compose.yaml up -d
```

初回Microsoft認証だけはPrism LauncherのGUIによるdevice-code承認が必要になる。Prismは`-d /data/prism`、instance起動は`-l <instance-id>`でCLI化できるが、account追加の公式CLI optionはない。

試験後は対象projectと、検証済みrun rootだけを削除する。image削除を含める場合は他projectが利用していないことを先に確認する。

```powershell
$env:MCMCP_TESTLAB_ROOT = 'F:/mcmcp-testlab/20260829-wheat-v1'
docker --config infra/remote-validation/docker-cli -H ssh://aod-mimoid compose `
  -p mcmcp-wheat-20260829 `
  -f infra/remote-validation/compose.yaml down --volumes --remove-orphans --rmi local
```
