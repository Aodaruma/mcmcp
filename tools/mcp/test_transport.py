"""Credential-free HTTP integration tests. Never connects to a Minecraft instance."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
PWSH = os.environ.get('MCMCP_TEST_PWSH') or shutil.which('pwsh')
TOKEN = 'fixture_' + 'x' * 43
ACTION = '00000000-0000-4000-8000-000000000001'
META = {'io.modelcontextprotocol/serverInfo': {'name': 'mcmcp', 'version': '0.1.0'}}
START = {'schema_version': 1, 'program': {'dsl_version': 1, 'capabilities': [],
         'body': [{'id': 'wait', 'op': 'wait_ticks', 'ticks': 1}]},
         'budget': dict(max_duration_ms=1000, max_ticks=20, max_distance_blocks=0,
                        max_camera_degrees=0, max_interactions=0,
                        max_blocks_broken=0, max_blocks_placed=0)}


def tool_result(data=None, error=False):
    result = {'resultType': 'complete', '_meta': META, 'isError': error,
              'content': [{'type': 'text', 'text': json.dumps(data, ensure_ascii=False)}]}
    if not error:
        result['structuredContent'] = data
    return result


def status(state='succeeded'):
    media = 'application/vnd.mcmcp.action-dsl+json;version=1'
    return dict(schema_version=1, action_id=ACTION, state=state,
                progress=dict(phase='finished', current_node_id=None, executed_nodes=1,
                              total_node_upper_bound=1, distance_travelled=0, camera_degrees=0,
                              interactions=0, blocks_broken=0, blocks_placed=0, ticks=1),
                failure=None, trace=[], effects=[],
                effect_aggregate=dict.fromkeys(['total_effects', 'retained_effects',
                    'confirmed_effects', 'qualified_effects', 'unknown_effects',
                    'dispatched_attacks', 'confirmed_attacks', 'unknown_attacks'], 0),
                partial=dict(has_confirmed_effects=False, interrupted_node_id=None,
                             remaining_node_upper_bound=0, resume_requires_reobservation=False),
                source=dict(media_type=media, canonical_json='{}', sha256='sha256:'+'0'*64,
                            contains_opaque_refs=False, replayable=True),
                template=dict(media_type=media, canonical_json='{}',
                              ready_for_agent_start_action=True, blocked_by=None),
                reference_requirements=[])


@unittest.skipUnless(PWSH, 'PowerShell 7.4+ required (set MCMCP_TEST_PWSH)')
class TransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='mcmcp-transport-test-')
        self.addCleanup(self.temp.cleanup)
        self.token_path = Path(self.temp.name) / 'token'
        self.token_path.write_text(TOKEN, encoding='utf-8')
        self.requests = []
        self.modify = lambda request, envelope: envelope
        self.http_status = 200
        self.content_type = 'application/json; charset=utf-8'
        self.delay = 0
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers['Content-Length']))
                request = json.loads(raw.decode('utf-8'))
                owner.requests.append((request, dict(self.headers), raw))
                time.sleep(owner.delay)
                envelope = owner.modify(request, {'jsonrpc': '2.0', 'id': request['id'],
                                                  'result': owner.result(request)})
                body = (envelope if isinstance(envelope, str) else
                        json.dumps(envelope, ensure_ascii=False)).encode('utf-8')
                self.send_response(owner.http_status)
                self.send_header('Content-Type', owner.content_type)
                if owner.http_status == 302:
                    self.send_header('Location', owner.endpoint)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.endpoint = f'http://127.0.0.1:{self.server.server_port}/mcp'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close_server)

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)

    def result(self, request):
        if request['method'] == 'server/discover':
            return dict(resultType='complete', _meta=META,
                        supportedVersions=['2026-07-28'], ttlMs=0, cacheScope='private',
                        capabilities={'tools': {'listChanged': False}})
        if request['method'] == 'tools/list':
            return dict(resultType='complete', _meta=META, tools=[{'name': n} for n in
                ['agent_get_state', 'agent_get_observation', 'agent_start_action',
                 'agent_get_action', 'agent_cancel_action']])
        if request['params']['name'] == 'agent_start_action':
            return tool_result(dict(schema_version=1, action_id=ACTION, state='queued',
                                    accepted_at='2026-09-06T00:00:00Z'))
        return tool_result(status())

    def invoke(self, *options, arguments=None):
        command = [PWSH, '-NoLogo', '-NoProfile', '-NonInteractive', '-File',
                   str(ROOT / 'tools/mcp/Invoke-Mcmcp.ps1'),
                   '-TokenPath', str(self.token_path), '-Endpoint', self.endpoint]
        if arguments is not None:
            path = Path(self.temp.name) / 'arguments.json'
            path.write_text(json.dumps(arguments, ensure_ascii=False), encoding='utf-8')
            command += ['-ArgumentsPath', str(path)]
        process = subprocess.run(command + list(options), capture_output=True, timeout=20)
        self.assertEqual(process.stderr, b'')
        text = process.stdout.decode('utf-8')
        self.assertNotIn(TOKEN, text)
        reply = json.loads(text)
        self.assertEqual(process.returncode, 0 if reply['ok'] else 1)
        return reply

    def test_connection_check_only_discovers_and_lists(self):
        self.assertTrue(self.invoke('-Check')['ok'])
        self.assertEqual([r[0]['method'] for r in self.requests], ['server/discover', 'tools/list'])
        for index, (request, headers, _) in enumerate(self.requests, 1):
            headers = {k.lower(): v for k, v in headers.items()}
            self.assertEqual(request['id'], index)
            self.assertEqual(headers['mcp-protocol-version'], '2026-07-28')
            self.assertEqual(headers['authorization'], 'Bearer ' + TOKEN)
            self.assertEqual(request['params']['_meta']['io.modelcontextprotocol/protocolVersion'], '2026-07-28')

    def test_start_waits_only_on_successful_id_and_preserves_arguments(self):
        reply = self.invoke('-Tool', 'agent_start_action', '-WaitSeconds', '10', arguments=START)
        self.assertTrue(reply['ok'], reply)
        self.assertEqual(reply['result']['state'], 'succeeded')
        calls = [r[0]['params'] for r in self.requests if r[0]['method'] == 'tools/call']
        self.assertEqual(calls[0]['arguments'], START)
        self.assertEqual(calls[1]['arguments']['action_id'], ACTION)
        self.assertGreater(calls[1]['arguments']['wait_timeout_ms'], 0)
        self.assertLessEqual(calls[1]['arguments']['wait_timeout_ms'], 25000)
        self.assertEqual(len(calls), 2)

    def test_error_or_missing_id_never_polls_or_replays(self):
        for mode, diagnostic in [('rpc', 'jsonrpc_error'), ('rpc_secret', 'secret_blocked'), ('tool', 'tool_rejected'),
                                  ('id', 'invalid_success_schema'), ('string_error', 'invalid_tool_result')]:
            with self.subTest(mode=mode):
                self.requests.clear()
                def modify(request, envelope):
                    if request['method'] != 'tools/call':
                        return envelope
                    if mode in ('rpc', 'rpc_secret'):
                        return {'jsonrpc': '2.0', 'id': request['id'],
                                'error': {'code': -32602, 'message': TOKEN if mode == 'rpc_secret' else 'invalid params'}}
                    if mode == 'tool':
                        envelope['result'] = tool_result(dict(code='ACTION_NOT_READY',
                            message='開始できません', recoverable=True), error=True)
                    if mode == 'id':
                        del envelope['result']['structuredContent']['action_id']
                    if mode == 'string_error':
                        envelope['result']['isError'] = 'false'
                    return envelope
                self.modify = modify
                reply = self.invoke('-Tool', 'agent_start_action', '-WaitSeconds', '10', arguments=START)
                self.assertFalse(reply['ok'])
                self.assertEqual(reply['diagnostic_code'], diagnostic)
                self.assertEqual(len(self.requests), 2)
                if mode == 'tool':
                    self.assertEqual(reply['error']['message'], '開始できません')
                    self.assertTrue(reply['error']['recoverable'])

    def test_poll_id_must_match_the_started_action(self):
        def modify(request, envelope):
            if request.get('params', {}).get('name') == 'agent_get_action':
                envelope['result']['structuredContent']['action_id'] = ACTION[:-1] + '2'
            return envelope
        self.modify = modify
        reply = self.invoke('-Tool', 'agent_start_action', '-WaitSeconds', '10', arguments=START)
        self.assertEqual(reply['diagnostic_code'], 'action_id_mismatch')
        self.assertEqual(reply['action_id'], ACTION)
        self.assertEqual(len(self.requests), 3)

    def test_nested_domain_json_cannot_decode_an_escaped_token_into_output(self):
        def modify(request, envelope):
            if request['method'] == 'tools/call':
                result = tool_result(dict(code='TEST_ERROR', message=TOKEN, recoverable=False), error=True)
                result['content'][0]['text'] = result['content'][0]['text'].replace(
                    TOKEN, ''.join('\\u%04x' % ord(c) for c in TOKEN))
                envelope['result'] = result
            return envelope
        self.modify = modify
        reply = self.invoke('-Tool', 'agent_start_action', '-WaitSeconds', '10', arguments=START)
        self.assertEqual(reply['diagnostic_code'], 'secret_blocked')
        self.assertEqual(len(self.requests), 2)

    def test_standalone_get_and_cancel_reject_a_different_action_id(self):
        for tool in ['agent_get_action', 'agent_cancel_action']:
            with self.subTest(tool=tool):
                def modify(request, envelope):
                    if request['method'] == 'tools/call':
                        data = status() if tool == 'agent_get_action' else dict(schema_version=1,
                            action_id=ACTION, cancel_requested=True, state_at_request='running')
                        data['action_id'] = ACTION[:-1] + '2'
                        envelope['result'] = tool_result(data)
                    return envelope
                self.modify = modify
                reply = self.invoke('-Tool', tool, arguments={'action_id': ACTION})
                self.assertEqual(reply['diagnostic_code'], 'action_id_mismatch')

    def test_disallowed_endpoint_and_token_never_leave_the_client(self):
        self.endpoint = 'http://example.invalid:8765/mcp'
        reply = self.invoke('-Check')
        self.assertFalse(reply['ok'])
        self.assertEqual(len(self.requests), 0)
        self.token_path.write_text('not-a-token', encoding='utf-8')
        reply = self.invoke('-Check')
        self.assertEqual(reply['diagnostic_code'], 'token_unavailable')

    def test_transport_sends_exact_utf8_request_bytes(self):
        path = Path(self.temp.name) / 'utf8.ps1'
        path.write_text("""
$ErrorActionPreference = 'Stop'
. './tools/mcp/McmcpTransport.ps1'
$null = Invoke-McmcpTransportRequest -Endpoint $args[0] `
    -Bearer ([IO.File]::ReadAllText($args[1])) -RequestId 1 -Method 'server/discover' `
    -Parameters @{ _meta = (Get-McpMeta -ClientName '日本語のクライアント') }
""", encoding='utf-8')
        process = subprocess.run([PWSH, '-NoProfile', '-File', str(path), self.endpoint,
                                  str(self.token_path)], cwd=ROOT, capture_output=True, timeout=10)
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertEqual(process.stdout, b'')
        self.assertIn('日本語のクライアント'.encode('utf-8'), self.requests[0][2])

    def test_consent_without_action_id_is_success_but_does_not_poll(self):
        def modify(request, envelope):
            if request['method'] == 'tools/call':
                envelope['result'] = tool_result(dict(schema_version=1, state='AWAITING_CONSENT',
                    policy_binding_hash='sha256:'+'0'*64, approval_request_state=None,
                    approval_scope_summary='承認対象の説明', action_reserved=False, input_acquired=False))
            return envelope
        self.modify = modify
        reply = self.invoke('-Tool', 'agent_start_action', '-WaitSeconds', '10', arguments=START)
        self.assertTrue(reply['ok'], reply)
        self.assertIn('承認対象', reply['result']['approval_scope_summary'])
        self.assertEqual(len(self.requests), 2)

    def test_bad_inputs_never_send_requests(self):
        for arguments in [{}, {'action_id': ''}, {'action_id': ACTION, 'wait_timeout_ms': -1},
                          {'action_id': ACTION, 'wait_timeout_ms': 25001},
                          {'action_id': ACTION, 'wait_timeout_ms': '100'}]:
            reply = self.invoke('-Tool', 'agent_get_action', arguments=arguments)
            self.assertEqual(reply['diagnostic_code'], 'invalid_tool_arguments')
            self.assertEqual(len(self.requests), 0)

    def test_http_failures_redirects_and_rate_limits_do_not_retry(self):
        for code in [401, 403, 429, 500, 302]:
            with self.subTest(code=code):
                self.http_status = code
                self.requests.clear()
                reply = self.invoke('-Check')
                self.assertFalse(reply['ok'])
                self.assertEqual(reply['failure_kind'], 'http_status')
                self.assertEqual(reply['http_status'], code)
                self.assertEqual(len(self.requests), 1)

    def test_envelope_and_mime_fail_closed(self):
        for mode in ['version', 'id', 'both', 'error_code', 'json', 'content_type']:
            with self.subTest(mode=mode):
                self.requests.clear()
                self.content_type = 'text/plain' if mode == 'content_type' else 'application/json; charset=utf-8'
                def modify(_, envelope):
                    if mode == 'version': envelope['jsonrpc'] = '1.0'
                    if mode == 'id': envelope['id'] = str(envelope['id'])
                    if mode == 'both': envelope['error'] = {'code': -1, 'message': 'error'}
                    if mode == 'error_code':
                        del envelope['result']
                        envelope['error'] = {'code': TOKEN, 'message': 'error'}
                    return 'invalid JSON' if mode == 'json' else envelope
                self.modify = modify
                reply = self.invoke('-Check')
                self.assertFalse(reply['ok'])
                self.assertEqual(reply['failure_kind'], 'protocol_validation')
                self.assertEqual(len(self.requests), 1)

    def test_wait_timeout_keeps_known_id_and_does_not_restart_action(self):
        self.modify = lambda request, envelope: dict(envelope, result=tool_result(status('running'))) \
            if request.get('params', {}).get('name') == 'agent_get_action' else envelope
        reply = self.invoke('-Tool', 'agent_start_action', '-WaitSeconds', '3', arguments=START)
        self.assertEqual(reply['diagnostic_code'], 'action_wait_timeout')
        self.assertEqual(reply['action_id'], ACTION)
        self.assertEqual(sum(r[0].get('params', {}).get('name') == 'agent_start_action'
                             for r in self.requests), 1)

    def test_shared_evaluator_wrapper_http_timeout_is_bounded(self):
        # Load only definitions from the runner: do not launch Codex or the game.
        self.delay = 2
        path = Path(self.temp.name) / 'timeout.ps1'
        path.write_text("""
$ErrorActionPreference = 'Stop'
. './tools/mcp/McmcpTransport.ps1'
$Endpoint = $args[0]
$script:Bearer = [IO.File]::ReadAllText($args[1])
$script:McpRequestId = 0L
$script:EvaluationLeaseAcquired = $false
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path (Get-Location) 'tools/eval/Invoke-McmcpFreshEval.ps1'), [ref]$tokens, [ref]$errors)
$function = $ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and
    $n.Name -eq 'Invoke-McmcpJsonRpc'}, $true)
Invoke-Expression $function.Extent.Text
try {
    $null = Invoke-McmcpJsonRpc -Method 'server/discover' -Parameters @{_meta=(Get-McpMeta)} `
        -TimeoutSeconds 1 -PacingAlreadyApplied
    throw 'unexpected success'
} catch { [Console]::Out.Write($_.Exception.Data['diagnostic_code']) }
""", encoding='utf-8')
        before = time.monotonic()
        process = subprocess.run([PWSH, '-NoProfile', '-File', str(path), self.endpoint,
                                  str(self.token_path)], cwd=ROOT, capture_output=True, timeout=10)
        self.assertLess(time.monotonic() - before, 8)
        self.assertEqual(process.stdout, b'request_timeout', process.stderr)
        self.assertEqual(process.stderr, b'')
        self.assertEqual(len(self.requests), 1)


if __name__ == '__main__':
    unittest.main()
