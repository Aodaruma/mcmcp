# MCMCP Fixture Admin Bridge

`mcmcp_fixture_admin` は、private singleplayer の integrated server にだけ、外部fixture scriptを適用するdevelopment-only MODです。production MCMCPのMCP endpoint、token、Tool catalogとは独立しています。

MODを一度buildした後は、fixtureの追加・座標変更にJavaの再compileは不要です。

## Build artifact

```powershell
./gradlew.bat adminBridgeTest adminBridgeJar verifyHarnessIsolation
```

生成物は `build/libs/*-fixture-admin.jar` です。通常profileや配布物には入れません。

## 起動gate

専用test profileでだけ、次の両方を設定します。

1. JVM property `-Dmcmcp.fixtureAdmin=true`
2. `config/mcmcp-fixture-admin/enabled-profile.marker` の内容を正確に `MCMCP_FIXTURE_ADMIN_V1` とする

endpointは `127.0.0.1:18766` にだけbindします。portはtest profileに限り `-Dmcmcp.fixtureAdmin.port=<port>` で変更できます。別Bearer tokenは `config/mcmcp-fixture-admin/admin-token` にowner-onlyで生成されます。

crash後のrandomTickSpeed復旧はこの2つのendpoint起動gateから独立しています。MODがprofileに残っていれば、endpointを無効化した次回起動でも、private integrated serverとworld identityが一致する場合にだけ復旧を試みます。

## Fixture directory

各fixtureは次の外部ディレクトリに置きます。

```text
config/mcmcp-fixture-admin/fixtures/<fixture-id>/
  fixture.json
  setup.mcfunction
```

`fixture.json` のschema version 1は次の形です。座標は対象saveを確認した上で外部データとして指定し、MODへ埋め込みません。

```json
{
  "schema_version": 1,
  "id": "example-fixture",
  "dimension": "minecraft:overworld",
  "mutation_bounds": {
    "min": {"x": 0, "y": 0, "z": 0},
    "max": {"x": 0, "y": 0, "z": 0}
  },
  "player_bounds": {
    "min": {"x": 0, "y": 0, "z": 0},
    "max": {"x": 0, "y": 0, "z": 0}
  },
  "max_changed_blocks": 1,
  "containers": [],
  "random_tick_speed": {
    "target": 3000,
    "maximum_seconds": 1200
  }
}
```

`random_tick_speed` は省略可能です。指定時は値を変更する前にcanonical world-path hash、save内の永続UUID、元値を `random-tick-lease.json` へatomic記録し、最大30分以内またはintegrated server停止時に復元します。JVM/container crash後は、同じworldの次回load時にjournalから元値を復元し、save成功を確認してからjournalを削除します。pathまたはworld UUIDが一致しない場合はapplyをfail-closedします。

## 閉じたcommand grammar

`setup.mcfunction` では空行と `#` commentのほか、次だけを使用できます。

- `setblock <absolute xyz> <minecraft:block[state]> [replace|keep]`
- `fill <absolute xyz> <absolute xyz> <minecraft:block[state]> [replace|keep]`
- `item replace block <absolute xyz> container.N with <minecraft:item[components]> [1..64]`
- `clear @s`
- `gamemode survival @s`
- `tp @s <absolute xyz> [yaw pitch]`
- `kill @e[type=minecraft:item,x=...,y=...,z=...,dx=...,dy=...,dz=...]`

block mutationはneighbor update用に `mutation_bounds` の各面から1 block内側、item purgeはbounds全体、teleportは `player_bounds` 内に制限されます。したがってmanifestのmutation boundsは実際に変更する座標より1 block広く宣言します。設置blockはair、dirt、farmland、wheat、chest、oak fence/gate、stone、smooth stone、sandstoneだけです。water、lava、TNT等の伝播・爆発性blockは拒否します。container操作先は `containers` への事前宣言が必要で、投入itemも畑・木こりfixture用の閉じた集合（iron/netherite hoe、wheat seeds等）です。gamemodeはcold-start playerをproduction相当へ戻す `survival @s` だけを許可します。`execute`、`function`、`schedule`、他gamemode、任意selector、NBT、command separator、script内gameruleは拒否します。

## JSON API

全requestに `Authorization: Bearer <admin-token>` が必要です。`Origin` headerは拒否します。

- `GET /v1/status`
- `POST /v1/fixtures/validate` body: `{"fixture_id":"..."}`
- `POST /v1/fixtures/apply` body: `{"fixture_id":"...","fixture_sha256":"...","world_session_id":"..."}`

安全な順序は `status -> validate -> apply` です。applyはvalidate時のhashと現在のworld-session IDが一致した場合だけ実行されます。応答の `commands_dispatched` はserver threadへ配送した件数であり、課題の完成oracleではありません。T0前にworld同期とfixture postconditionを別途確認します。

T0以後、評価LLMへこのendpoint、token、shellを公開しません。評価LLMが使用できるのはproduction MCMCP toolsだけです。

## 現時点の制約

- Minecraft command dispatcherは、idempotentなno-op（既に同じblock）と実行時失敗（対象container不在等）を一律に完成oracleとして扱えません。現在のapply結果は「配送済み」であり、部分適用をrollbackしません。`wheat-original-v1` は既知の元畑構造に限定し、T0前に別のpostcondition確認を行います。
- 1 block insetとblock allowlistは直接mutationを制限しますが、`air` で未知の砂・砂利支持を除去した場合のgravity連鎖まで一般に境界保証しません。既知block構造のfixtureだけに限定し、汎用fixtureへ広げる前にprecondition guardと影響範囲の事後確認を追加します。
