# MCMCP

[日本語版](README-ja.md)

<img src="docs/assets/readme/mcmcp-logo.png" width="180" alt="MCMCP: a robot with a pickaxe">

[![Release](https://img.shields.io/github/v/release/Aodaruma/mcmcp?include_prereleases&sort=semver)](https://github.com/Aodaruma/mcmcp/releases)
[![Build status](https://github.com/Aodaruma/mcmcp/actions/workflows/release.yml/badge.svg?branch=main)](https://github.com/Aodaruma/mcmcp/actions/workflows/release.yml)
[![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-62B47A)](#getting-started)
[![NeoForge 26.2.0.59](https://img.shields.io/badge/NeoForge-26.2.0.59-orange)](#getting-started)
[![Java 25](https://img.shields.io/badge/Java-25-ED8B00)](#getting-started)
[![License](https://img.shields.io/github/license/Aodaruma/mcmcp)](LICENSE)

Ask an AI assistant such as Codex or Claude Code to help you in Minecraft. MCMCP is a client-side NeoForge mod that lets your assistant use your player to explore, move, build, craft, and manage items.

**[Download](https://github.com/Aodaruma/mcmcp/releases)** · **[Illustrated setup guide (Japanese)](docs/MCMCP_配布用README.md)** · **[Guide PDF (Japanese)](docs/MCMCP_配布用README.pdf)**

## Getting started

You need **Minecraft Java Edition 26.2, NeoForge 26.2.0.59, Java 25**, and an MCP-compatible AI client running on the same PC. Install MCMCP in your Minecraft client; no server-side installation is needed.

1. **Install the mod.** Download the distribution ZIP from [Releases](https://github.com/Aodaruma/mcmcp/releases), then add its JAR to your CurseForge or Prism Launcher profile.
2. **Connect your assistant.** Enter a world, press Esc, and open **MCP connection setup**. Configure your AI client, then restart it with the configuration you selected.
3. **Enable control.** Turn **MCP control** on, return to the game, and start by asking your assistant to check your status or inventory.

The illustrated setup guide and most detailed documentation are currently in Japanese. Development builds are available from [GitHub Actions](https://github.com/Aodaruma/mcmcp/actions/workflows/release.yml).

> [!NOTE]
> Press **Esc to stop an active action**. Control stays on between actions; switch it off to disable automation. Multiplayer control requires permission for each server.
>
> To keep Minecraft running while you use your AI client in another app, press **F3+P** with the default key bindings to disable pause on lost focus.

## What you can do

Public API v2 provides **17 tools** for describing tasks with coordinates, targets, and quantities. Actions share progress tracking, results, and cancellation.

| Task | Tools |
| --- | --- |
| Check state and surroundings | `agent_get_state`, `agent_get_mcp_status`, `agent_get_observation` |
| Move, look, mine, and build | `agent_look`, `agent_move`, `agent_break_block`, `agent_place_block`, `agent_build` |
| Interact, craft, and manage items | `agent_interact`, `agent_craft`, `agent_inventory` |
| Use inputs, scripts, and game chat | `agent_click`, `agent_input_sequence`, `agent_run_script`, `agent_chat` |
| Check progress or cancel work | `agent_get_action`, `agent_cancel_action` |

Use a bounded script to combine several actions, or preview a JSON/CSV blueprint with `agent_build` before building it in limited sections. Cancelling or failing an action does not undo changes already confirmed in the world.

See the [API guide](docs/MCMCP_Public_API_v2_クイックガイド.md), [blueprint guide](docs/PUBLIC_API_V2_BLUEPRINT_TOOL.md), and [tested features and remaining work](docs/experiments/20261115_release_rc1.md). These guides are in Japanese. API limits describe what a request can ask for; the validation records describe what has actually been tested.

**Upgrading from 0.1:** API v2 replaces `agent_start_action` and the public JSON Action DSL. Restart your MCP client to refresh its tool list, then update workflows to use the v2 tools and scripts.

## Help and feedback

For setup problems, see the [troubleshooting guide (Japanese)](docs/MCMCP_配布用README.md#5-困ったとき--トラブルシューティング). Report bugs and request features in [Issues](https://github.com/Aodaruma/mcmcp/issues).

## Contributing

Start with [CONTRIBUTING.md](CONTRIBUTING.md). Coordinate the scope on an issue, work in your own fork or checkout, and submit a pull request. [AGENTS.md](AGENTS.md) contains the shared rules for Codex and Claude Code; [MAINTENANCE.md](docs/MAINTENANCE.md) covers reviews, merging, and releases.

**[Project board](https://github.com/users/Aodaruma/projects/5)** · **[Ready issues](https://github.com/Aodaruma/mcmcp/issues?q=is%3Aissue+is%3Aopen+label%3Astatus%3Aready)** · **[Good first issues](https://github.com/Aodaruma/mcmcp/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)**

<details>
<summary>Building, technical documentation, and releases</summary>

Set `JAVA_HOME` to Java 25, then run:

~~~powershell
.\gradlew.bat test harnessTest adminBridgeTest verifyHarnessIsolation build
~~~

On Linux or macOS, use `bash gradlew`. Test-only mods are kept separate from the production JAR.

- [Connection details](docs/MCMCP_導入と接続ガイド.md)
- [Public API v2](docs/MCMCP_Public_API_v2_クイックガイド.md) · [Tool catalog](docs/MCMCP_MCP_Tool_Catalog.json)
- [Design and specification](docs/Minecraft_MCP_NeoForge_設計仕様書.md)
- [Test and experiment records](docs/experiments/)
- [Distribution and PDF generation](tools/release/README.md)

Technical documentation is primarily in Japanese. A version tag such as `v0.2.0-rc.1` triggers CI to test and build a matching JAR, PDF, and ZIP, then publish a non-draft release. Tags with a prerelease suffix produce a pre-release. PDF generation does not require Typora.

</details>

## License

Source code is licensed under [MPL 2.0](LICENSE). See [NOTICE.md](NOTICE.md) for third-party credits.

NOT AN OFFICIAL MINECRAFT MOD. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
