#!/usr/bin/env python3
"""Exercise the tools shipped in the engine against local, credential-free fixtures."""
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, "/opt/hermes/hermes-agent")
import hermes_bootstrap  # noqa: E402,F401


def chromium():
    from hermes_cli.browser_runtime import chromium_executable

    binary = chromium_executable(allow_override=False)
    if not binary:
        raise RuntimeError("The managed Chromium executable is missing")
    links = subprocess.check_output(["ldd", binary], text=True, timeout=30)
    missing = [line.strip() for line in links.splitlines() if "not found" in line]
    if missing:
        raise RuntimeError("\n".join(missing))
    print(subprocess.check_output([binary, "--version"], text=True, timeout=30).strip())


def tool_output(raw, marker, passed):
    result = json.loads(raw)
    if not passed(result) or marker not in result.get("output", ""):
        raise RuntimeError(result)


def browser():
    from tools.browser_session_lifecycle import cleanup_browser
    from tools.browser_use_cli import browser_exec

    task = "hermes-local-runtime-check"
    try:
        # Run in a clean browser tab and verify DOM state across two real tool calls.
        first = """print(js("document.title = 'HL_BROWSER_OK'"))"""
        second = """print(js("document.title"))"""
        for code in (first, second):
            tool_output(browser_exec(code, task_id=task, local=True, timeout_s=40),
                        "HL_BROWSER_OK", lambda result: result.get("success"))
    finally:
        cleanup_browser(task)


def terminal():
    from tools.terminal_tool import terminal_tool

    tool_output(terminal_tool("printf HL_TERMINAL_OK", timeout=15), "HL_TERMINAL_OK",
                lambda result: result.get("exit_code") == 0)


def code():
    from tools.code_execution_tool import execute_code

    tool_output(execute_code("print('HL_CODE_OK')", task_id="hermes-local-runtime-check"), "HL_CODE_OK",
                lambda result: result.get("status") == "success")


async def mcp_roundtrip():
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    from pm import installed_package

    node = installed_package("node")
    if node is None or node.binary is None:
        raise RuntimeError("Hermes managed Node.js is missing")
    # A minimal stdio MCP server using the same managed Node runtime as Hermes' npm-backed MCPs.
    script = """
const readline = require('node:readline');
readline.createInterface({input: process.stdin}).on('line', line => {
  const {id, method, params} = JSON.parse(line);
  if (id === undefined) return;
  const result = method === 'initialize'
    ? {protocolVersion: params.protocolVersion, capabilities: {tools: {}}, serverInfo: {name: 'hl-test', version: '1'}}
    : method === 'tools/list'
    ? {tools: [{name: 'probe', inputSchema: {type: 'object'}}]}
    : {content: [{type: 'text', text: 'HL_MCP_OK'}]};
  console.log(JSON.stringify({jsonrpc: '2.0', id, result}));
});
"""
    async with stdio_client(StdioServerParameters(command=str(node.binary), args=["-e", script])) as streams:
        async with ClientSession(*streams) as session:
            await session.initialize()
            tools = await session.list_tools()
            if [tool.name for tool in tools.tools] != ["probe"]:
                raise RuntimeError(tools)
            reply = await session.call_tool("probe", {})
            if reply.isError or reply.content[0].text != "HL_MCP_OK":
                raise RuntimeError(reply)


def mcp():
    asyncio.run(asyncio.wait_for(mcp_roundtrip(), 25))


def antigravity():
    from providers import get_provider_profile

    profile = get_provider_profile("antigravity-oauth")
    if profile is None:
        raise RuntimeError("The Antigravity plugin did not register")
    with tempfile.TemporaryDirectory() as temp:
        agy = Path(temp) / "agy"
        agy.write_text(
            '#!/bin/sh\n[ "$1" = models ] || exit 2\n'
            'printf "gemini-4-argon-high\\tGemini 4 Argon High\\nclaude-sonnet-4-6\\tSonnet\\n"\n'
        )
        agy.chmod(0o700)
        os.environ["ANTIGRAVITY_COMMAND"] = str(agy)
        ids = profile.fetch_models(timeout=10)
        if ids != ["gemini-4-argon-high", "claude-sonnet-4-6"]:
            raise RuntimeError(f"Antigravity changed the account's native IDs: {ids}")
    if profile.build_api_kwargs_extras(
        model=ids[0], reasoning_config={"effort": "medium"}
    )[1].get("reasoning_effort"):
        raise RuntimeError("Antigravity overrode a native model's effort")


if __name__ == "__main__":
    checks = {
        "chromium": chromium, "browser": browser, "terminal": terminal, "code": code,
        "mcp": mcp, "antigravity": antigravity,
    }
    name = sys.argv[1]
    checks[name]()
    print(f"HL_RUNTIME_OK {name}")
